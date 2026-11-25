package com.example.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.R
import com.example.crypto.LocalSessionCrypto
import com.example.data.local.*
import com.example.data.remote.*
import com.example.network.*
import com.example.notifications.NotificationOrchestrator
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    ACTIVE,
    ENDED
}

data class ActiveCallSession(
    val callId: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val conversationId: String = "",
    val isVideo: Boolean = false,
    val isOutgoing: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val connectedAtMs: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraOn: Boolean = true,
    val isFrontCamera: Boolean = true,
    val channelMode: ChannelMode = ChannelMode.OFFLINE
)

class MeshChatRepository(
    private val context: Context,
    private val db: OfflineChatDatabase,
    val preferencesRepository: UserPreferencesRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val peerDao = db.peerDao()
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val fileTransferDao = db.fileTransferDao()

    val wifiDirectManager = WifiDirectManager(context)
    val hotspotManager = HotspotManager(context)
    val tcpTransport = TcpMeshTransport(context)
    val mediaCallEngine = MediaCallEngine(context)
    val voiceNoteManager = VoiceNoteManager(context)

    val googleAuthManager = GoogleAuthManager(context)
    val firestoreRepo = FirestoreChatRepository(context)
    val webRtcCallManager = WebRtcCallManager(context)

    val localVideoTrack: StateFlow<VideoTrack?> = webRtcCallManager.localVideoTrack
    val remoteVideoTrack: StateFlow<VideoTrack?> = webRtcCallManager.remoteVideoTrack
    val webrtcEglContext: EglBase.Context get() = webRtcCallManager.eglContext

    val userSettings: StateFlow<UserProfileSettings> = preferencesRepository.settingsFlow
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            UserProfileSettings(
                deviceId = "OC-INIT",
                displayName = Build.MODEL,
                avatarColorIndex = 0,
                languageTag = "en",
                themePreference = com.example.ui.theme.ThemePreference.DARK,
                encryptionEnabled = true,
                roomPin = "",
                onboardingCompleted = false,
                preferredMode = ChannelMode.ONLINE,
                offlineOnlyBypass = false,
                cloudShareCode = ""
            )
        )

    val authUserFlow: StateFlow<FirebaseUser?> = googleAuthManager.authStateFlow
        .stateIn(scope, SharingStarted.Eagerly, googleAuthManager.currentUser)

    val cloudProfile: StateFlow<CloudUserProfile?> = firestoreRepo.myProfile

    private val _cloudSyncState = MutableStateFlow<CloudUiState<String>>(CloudUiState.Idle)
    val cloudSyncState: StateFlow<CloudUiState<String>> = _cloudSyncState.asStateFlow()

    private val _isInternetAvailable = MutableStateFlow(checkCurrentInternet())
    val isInternetAvailable: StateFlow<Boolean> = _isInternetAvailable.asStateFlow()

    val peersFlow: Flow<List<PeerEntity>> = peerDao.observeAllPeers()
    val connectedPeersFlow: Flow<List<PeerEntity>> = peerDao.observeConnectedPeers()
    val conversationsFlow: Flow<List<ConversationEntity>> = conversationDao.observeConversations()
    val transfersFlow: Flow<List<FileTransferEntity>> = fileTransferDao.observeAllTransfers()
    val connectedPeerIds: StateFlow<Set<String>> = tcpTransport.connectedPeerIds

    private val _typingPeers = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typingPeers: StateFlow<Map<String, Boolean>> = _typingPeers.asStateFlow()

    private val _activeCall = MutableStateFlow(ActiveCallSession())
    val activeCall: StateFlow<ActiveCallSession> = _activeCall.asStateFlow()

    private val _feedbackMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val feedbackMessages: SharedFlow<String> = _feedbackMessages.asSharedFlow()

    private var outgoingCallTimeoutJob: Job? = null
    private var meshStarted = false
    private var activeCloudRoomId: String? = null

    private val cloudConversationsCache = ConcurrentHashMap<String, CloudConversation>()
    private val activeCloudTransferJobs = ConcurrentHashMap<String, Job>()
    private val handledCloudCallIds = ConcurrentHashMap.newKeySet<String>()
    private var pendingIncomingCloudCall: CloudCallSession? = null

    init {
        scope.launch { preferencesRepository.ensureDeviceIdInitialized() }

        tcpTransport.localIdentityProvider = {
            val s = userSettings.value
            Triple(s.deviceId, s.displayName, s.avatarColorIndex)
        }
        tcpTransport.roomPinProvider = { userSettings.value.roomPin }
        tcpTransport.onCallAudioReceived = { _, pcm -> mediaCallEngine.onIncomingAudioFrame(pcm) }
        tcpTransport.onCallVideoReceived = { _, jpeg -> mediaCallEngine.onIncomingVideoFrame(jpeg) }

        wifiDirectManager.onGroupOwnerConnected = { hostIp, isGroupOwner ->
            if (!isGroupOwner) {
                scope.launch {
                    repeat(5) { attempt ->
                        if (tcpTransport.connectToHostSuspending(hostIp, TcpMeshTransport.MESH_PORT, TransportKind.WIFI_DIRECT)) {
                            return@launch
                        }
                        delay(350L * (attempt + 1))
                    }
                }
            }
        }

        observeTransportEvents()
        observeNetworkConnectivity()
        observeAuthLifecycle()
    }

    private fun checkCurrentInternet(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching false
            val net = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private fun observeNetworkConnectivity() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !_isInternetAvailable.value
                    _isInternetAvailable.value = true
                    if (wasOffline && authUserFlow.value != null) {
                        scope.launch {
                            firestoreRepo.setOnlinePresence(true)
                            autoResumeOnlineTransfers()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    _isInternetAvailable.value = checkCurrentInternet()
                }
            })
        }
    }

    private fun observeAuthLifecycle() {
        scope.launch {
            authUserFlow.collect { user ->
                if (user != null) {
                    onAuthenticatedUser(user)
                } else {
                    firestoreRepo.stopGlobalListenersSync()
                    cloudConversationsCache.clear()
                    activeCloudRoomId = null
                    _cloudSyncState.value = CloudUiState.Idle
                }
            }
        }
    }

    private suspend fun onAuthenticatedUser(user: FirebaseUser) {
        _cloudSyncState.value = CloudUiState.Loading
        val settings = userSettings.value
        val profileRes = firestoreRepo.ensureUserProfile(
            user = user,
            fallbackName = settings.displayName,
            avatarColorIndex = settings.avatarColorIndex
        )
        profileRes.onSuccess { profile ->
            preferencesRepository.updateCloudShareCode(profile.shareCode)
            if (settings.offlineOnlyBypass) {
                preferencesRepository.setOfflineOnlyBypass(false)
            }
            _cloudSyncState.value = CloudUiState.Success(profile.shareCode)

            firestoreRepo.startGlobalListeners(
                uid = user.uid,
                onConversationsUpdated = { list ->
                    scope.launch { syncCloudConversations(user.uid, list) }
                },
                onCallsUpdated = { calls ->
                    scope.launch { handleCloudCallsSnapshot(user.uid, calls) }
                },
                onError = { errMsg ->
                    _cloudSyncState.value = CloudUiState.Error(errMsg)
                }
            )

            activeCloudRoomId?.let { roomId ->
                openOnlineRoomListener(roomId)
            }
            autoResumeOnlineTransfers()
        }.onFailure { err ->
            val msg = err.message ?: "Cloud sync unavailable"
            _cloudSyncState.value = CloudUiState.Error(msg)
        }
    }

    suspend fun attemptSilentSignIn(activityContext: Context): Result<FirebaseUser?> {
        return googleAuthManager.attemptSilentSignIn(activityContext)
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> {
        _cloudSyncState.value = CloudUiState.Loading
        val res = googleAuthManager.signInWithGoogle(activityContext)
        res.onSuccess { user ->
            preferencesRepository.setOfflineOnlyBypass(false)
            onAuthenticatedUser(user)
        }.onFailure { err ->
            _cloudSyncState.value = CloudUiState.Error(err.message ?: "Google Sign-In failed")
        }
        return res
    }

    suspend fun continueOfflineOnly() {
        preferencesRepository.setOfflineOnlyBypass(true)
    }

    suspend fun setPreferredMode(mode: ChannelMode) {
        preferencesRepository.updatePreferredMode(mode)
    }

    suspend fun signOut() {
        withContext(NonCancellable) {
            if (_activeCall.value.channelMode == ChannelMode.ONLINE && _activeCall.value.phase != CallPhase.IDLE) {
                endActiveCall()
            }
            activeCloudTransferJobs.values.forEach { it.cancel() }
            activeCloudTransferJobs.clear()
            googleAuthManager.signOut {
                firestoreRepo.detachAllOnSignOut()
            }
            conversationDao.deleteAllOnlineConversations()
            cloudConversationsCache.clear()
            activeCloudRoomId = null
            _cloudSyncState.value = CloudUiState.Idle
        }
    }

    suspend fun updateProfileAndSync(displayName: String, avatarColorIndex: Int) {
        preferencesRepository.updateDisplayName(displayName)
        preferencesRepository.updateAvatarColorIndex(avatarColorIndex)
        if (authUserFlow.value != null) {
            firestoreRepo.updateProfileDisplay(displayName, avatarColorIndex)
        }
    }

    suspend fun addOnlineFriendByCode(rawCodeOrQr: String): Result<String> {
        val myUid = authUserFlow.value?.uid
            ?: return Result.failure(IllegalStateException(context.getString(R.string.cloud_sign_in_required)))
        val lookupRes = firestoreRepo.lookupFriendByCode(rawCodeOrQr)
        val entry = lookupRes.getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException(context.getString(R.string.friend_code_not_found)))
        if (entry.ownerUid == myUid) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.cannot_add_self_code)))
        }
        val s = userSettings.value
        val convRes = firestoreRepo.createOrOpenDirectConversation(
            myName = cloudProfile.value?.displayName ?: s.displayName,
            myColor = s.avatarColorIndex,
            friend = entry
        )
        val cloudConv = convRes.getOrElse { return Result.failure(it) }
        cloudConversationsCache[cloudConv.id] = cloudConv
        upsertLocalConversationFromCloud(myUid, cloudConv, emitNotification = false)
        return Result.success(cloudConv.id)
    }

    suspend fun createOnlineGroupConversation(title: String, selectedFriendConvIdsOrUids: List<String>): Result<String> {
        val myUid = authUserFlow.value?.uid
            ?: return Result.failure(IllegalStateException(context.getString(R.string.cloud_sign_in_required)))
        val friendsMap = mutableMapOf<String, Pair<String, Int>>()
        selectedFriendConvIdsOrUids.forEach { id ->
            val conv = conversationDao.getConversation(id)
            val uid = conv?.remoteUid ?: id
            if (uid.isNotBlank() && uid != myUid) {
                friendsMap[uid] = Pair(conv?.title ?: uid, conv?.avatarColorIndex ?: 0)
            }
        }
        if (friendsMap.isEmpty()) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.select_online_friends_hint)))
        }
        val s = userSettings.value
        val res = firestoreRepo.createOnlineGroupConversation(
            myName = cloudProfile.value?.displayName ?: s.displayName,
            myColor = s.avatarColorIndex,
            title = title,
            friendUidsAndNames = friendsMap
        )
        val cloudConv = res.getOrElse { return Result.failure(it) }
        cloudConversationsCache[cloudConv.id] = cloudConv
        upsertLocalConversationFromCloud(myUid, cloudConv, emitNotification = false)
        return Result.success(cloudConv.id)
    }

    private suspend fun syncCloudConversations(myUid: String, list: List<CloudConversation>) {
        list.forEach { cloudConv ->
            cloudConversationsCache[cloudConv.id] = cloudConv
            upsertLocalConversationFromCloud(myUid, cloudConv, emitNotification = true)
            val otherTyping = cloudConv.typingUids.any { it != myUid }
            _typingPeers.update { it + (cloudConv.id to otherTyping) }
        }
    }

    private suspend fun upsertLocalConversationFromCloud(
        myUid: String,
        cloudConv: CloudConversation,
        emitNotification: Boolean
    ) {
        val otherUid = cloudConv.memberUids.firstOrNull { it != myUid }
        val resolvedTitle = if (cloudConv.groupChat) {
            cloudConv.title.ifBlank { "Online Group" }
        } else {
            cloudConv.participantNames[otherUid] ?: cloudConv.title.ifBlank { "Online Chat" }
        }
        val color = (cloudConv.participantColors[otherUid]?.toInt() ?: resolvedTitle.hashCode()) and 0x7FFFFFFF
        val existing = conversationDao.getConversation(cloudConv.id)
        val isViewing = NotificationOrchestrator.isAppInForeground &&
            NotificationOrchestrator.activeForegroundConversationId == cloudConv.id

        val hasNewIncoming = existing == null ||
            (cloudConv.lastSenderUid.isNotBlank() &&
                cloudConv.lastSenderUid != myUid &&
                cloudConv.lastMessageAtMs > existing.lastMessageTimestamp)

        val nextUnread = when {
            isViewing -> 0
            existing == null && cloudConv.lastSenderUid != myUid && cloudConv.lastMessagePreview.isNotBlank() -> 1
            hasNewIncoming && existing != null -> (existing.unreadCount + 1).coerceAtLeast(1)
            else -> existing?.unreadCount ?: 0
        }

        val entity = ConversationEntity(
            conversationId = cloudConv.id,
            title = resolvedTitle,
            isGroup = cloudConv.groupChat,
            peerId = otherUid,
            participantIdsCsv = cloudConv.memberUids.joinToString(","),
            lastMessagePreview = cloudConv.lastMessagePreview,
            lastMessageTimestamp = maxOf(cloudConv.lastMessageAtMs, existing?.lastMessageTimestamp ?: 0L),
            unreadCount = nextUnread,
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false,
            avatarColorIndex = color % 6,
            channelMode = ChannelMode.ONLINE,
            remoteUid = otherUid,
            remoteShareCode = otherUid?.let { firestoreRepo.shareCodeForUid(it) },
            onlinePresence = true
        )
        conversationDao.upsertConversation(entity)

        if (emitNotification && hasNewIncoming && !isViewing && cloudConv.lastMessagePreview.isNotBlank()) {
            val senderName = cloudConv.participantNames[cloudConv.lastSenderUid] ?: resolvedTitle
            NotificationOrchestrator.showIncomingMessageNotification(
                context = context,
                conversationId = cloudConv.id,
                peerId = cloudConv.lastSenderUid,
                senderName = senderName,
                messageText = cloudConv.lastMessagePreview
            )
        }
    }

    fun enterChatRoom(conversationId: String) {
        scope.launch {
            val conv = conversationDao.getConversation(conversationId)
            if (conv?.channelMode == ChannelMode.ONLINE) {
                activeCloudRoomId = conversationId
                openOnlineRoomListener(conversationId)
            } else {
                activeCloudRoomId = null
                firestoreRepo.stopRoomMessagesListener()
            }
        }
    }

    fun exitChatRoom() {
        activeCloudRoomId = null
        firestoreRepo.stopRoomMessagesListener()
    }

    private fun openOnlineRoomListener(convId: String) {
        val myUid = authUserFlow.value?.uid ?: return
        firestoreRepo.startRoomMessagesListener(
            convId = convId,
            onMessagesUpdated = { cloudMsgs ->
                scope.launch { handleCloudRoomMessages(convId, myUid, cloudMsgs) }
            },
            onError = { err ->
                _feedbackMessages.tryEmit(err)
            }
        )
    }

    private suspend fun handleCloudRoomMessages(
        convId: String,
        myUid: String,
        cloudMsgs: List<CloudMessage>
    ) {
        val isViewing = NotificationOrchestrator.isAppInForeground &&
            NotificationOrchestrator.activeForegroundConversationId == convId
        val receiptsToAck = mutableListOf<String>()
        val conv = conversationDao.getConversation(convId)

        cloudMsgs.forEach { cm ->
            val isOutgoing = cm.senderUid == myUid
            val existing = messageDao.getMessage(cm.id)
            val msgType = runCatching { MessageType.valueOf(cm.msgType) }.getOrDefault(MessageType.TEXT)

            val status = if (isOutgoing) {
                when {
                    cm.readUids.any { it != myUid } -> MessageStatus.READ
                    cm.deliveredUids.any { it != myUid } -> MessageStatus.DELIVERED
                    else -> MessageStatus.SENT
                }
            } else {
                MessageStatus.DELIVERED
            }

            val entity = MessageEntity(
                messageId = cm.id,
                conversationId = convId,
                senderId = cm.senderUid,
                senderName = cm.senderName,
                isOutgoing = isOutgoing,
                type = msgType,
                textContent = cm.textBody,
                mediaLocalPath = existing?.mediaLocalPath,
                fileName = cm.fileName.ifBlank { null },
                mimeType = cm.mimeType.ifBlank { null },
                fileSizeBytes = cm.fileSizeBytes,
                durationMs = cm.durationMs,
                transferId = cm.transferId.ifBlank { null },
                replyToMessageId = cm.replyToId.ifBlank { null },
                replyToPreview = cm.replyToPreview.ifBlank { null },
                reactionEmoji = cm.reactionEmoji.ifBlank { null },
                status = status,
                timestamp = cm.clientTimestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            messageDao.insertMessage(entity)

            if (!isOutgoing) {
                val needDelivered = !cm.deliveredUids.contains(myUid)
                val needRead = isViewing && !cm.readUids.contains(myUid)
                if (needDelivered || needRead) {
                    receiptsToAck.add(cm.id)
                }
            }

            // Trigger resumable chunked download if incoming media/file hasn't finished locally
            val tId = cm.transferId
            if (!isOutgoing && tId.isNotBlank() &&
                msgType in setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.FILE, MessageType.VOICE_NOTE)
            ) {
                val existingPath = existing?.mediaLocalPath
                val alreadySaved = !existingPath.isNullOrBlank() && File(existingPath).exists()
                if (!alreadySaved) {
                    val existingTransfer = fileTransferDao.getTransfer(tId)
                    val shouldStart = existingTransfer == null ||
                        existingTransfer.status == TransferStatus.QUEUED ||
                        existingTransfer.status == TransferStatus.TRANSFERRING
                    if (existingTransfer == null && msgType != MessageType.VOICE_NOTE) {
                        fileTransferDao.upsertTransfer(
                            FileTransferEntity(
                                transferId = tId,
                                conversationId = convId,
                                peerId = cm.senderUid,
                                peerName = cm.senderName.ifBlank { conv?.title ?: "Online Peer" },
                                fileName = cm.fileName.ifBlank { "file_$tId" },
                                mimeType = cm.mimeType.ifBlank { "application/octet-stream" },
                                totalBytes = cm.fileSizeBytes,
                                direction = TransferDirection.INCOMING,
                                status = TransferStatus.TRANSFERRING,
                                channelMode = ChannelMode.ONLINE,
                                totalChunks = ((cm.fileSizeBytes + FirestoreChatRepository.CLOUD_CHUNK_SIZE - 1) /
                                    FirestoreChatRepository.CLOUD_CHUNK_SIZE).toInt().coerceAtLeast(1)
                            )
                        )
                    }
                    if (shouldStart && !activeCloudTransferJobs.containsKey(tId)) {
                        launchCloudDownload(convId, tId, cm.fileName.ifBlank { "file_$tId" }, msgType == MessageType.VOICE_NOTE)
                    }
                }
            }
        }

        if (receiptsToAck.isNotEmpty()) {
            firestoreRepo.markCloudMessagesDeliveredOrRead(
                convId = convId,
                messageIds = receiptsToAck,
                markRead = isViewing
            )
        }
    }

    private fun launchCloudDownload(
        convId: String,
        transferId: String,
        fileName: String,
        isVoiceNote: Boolean
    ) {
        if (activeCloudTransferJobs[transferId]?.isActive == true) return
        val job = scope.launch {
            try {
                firestoreRepo.downloadChunkedFile(
                    convId = convId,
                    transferId = transferId,
                    fallbackFileName = fileName,
                    onProgress = { transferred, total, completedChunks, totalChunks, speedBps, partialPath ->
                        if (!isVoiceNote) {
                            fileTransferDao.updateProgress(
                                transferId = transferId,
                                transferred = transferred,
                                speed = speedBps,
                                status = TransferStatus.TRANSFERRING,
                                completedChunks = completedChunks,
                                partialPath = partialPath
                            )
                            val pct = if (total > 0L) ((transferred * 100L) / total).toInt() else 0
                            NotificationOrchestrator.showFileTransferProgress(
                                context = context,
                                transferId = transferId,
                                fileName = fileName,
                                progressPercent = pct,
                                speedText = formatBytesPerSec(speedBps),
                                isIncoming = true
                            )
                        }
                    },
                    onFinished = { outcome, localFile, sha256, verified ->
                        when (outcome) {
                            CloudTransferOutcome.COMPLETED -> {
                                val path = localFile?.absolutePath.orEmpty()
                                if (path.isNotBlank()) {
                                    messageDao.attachMediaPathByTransfer(transferId, path)
                                }
                                if (!isVoiceNote) {
                                    fileTransferDao.completeTransfer(
                                        transferId = transferId,
                                        localPath = path,
                                        checksum = sha256,
                                        verified = verified,
                                        status = if (verified) TransferStatus.COMPLETED else TransferStatus.FAILED
                                    )
                                    NotificationOrchestrator.showFileTransferProgress(
                                        context = context,
                                        transferId = transferId,
                                        fileName = fileName,
                                        progressPercent = 100,
                                        speedText = "",
                                        isIncoming = true,
                                        isComplete = verified,
                                        isFailed = !verified
                                    )
                                }
                            }

                            CloudTransferOutcome.PAUSED -> {
                                if (!isVoiceNote) {
                                    fileTransferDao.updateStatus(transferId, TransferStatus.PAUSED)
                                }
                            }

                            CloudTransferOutcome.CANCELLED -> {
                                if (!isVoiceNote) {
                                    fileTransferDao.updateStatus(transferId, TransferStatus.CANCELLED)
                                }
                            }

                            CloudTransferOutcome.FAILED -> {
                                if (!isVoiceNote) {
                                    fileTransferDao.updateStatus(transferId, TransferStatus.FAILED)
                                }
                            }
                        }
                    }
                )
            } finally {
                activeCloudTransferJobs.remove(transferId)
            }
        }
        activeCloudTransferJobs[transferId] = job
    }

    fun ensureMeshStarted() {
        if (meshStarted) return
        meshStarted = true
        wifiDirectManager.register()
        tcpTransport.startServer()
        val s = userSettings.value
        hotspotManager.startLanDiscoveryAndBeacon(s.deviceId, s.displayName)
    }

    fun startNearbyScan() {
        ensureMeshStarted()
        wifiDirectManager.startDiscovery()
        val s = userSettings.value
        hotspotManager.startLanDiscoveryAndBeacon(s.deviceId, s.displayName)
    }

    fun stopNearbyScan() {
        wifiDirectManager.stopDiscovery()
    }

    fun connectWifiDirectPeer(deviceAddress: String, onResult: (Boolean) -> Unit) {
        ensureMeshStarted()
        wifiDirectManager.connectToPeer(deviceAddress, onResult)
    }

    fun connectDirectIp(ip: String, port: Int = TcpMeshTransport.MESH_PORT, onResult: (Boolean) -> Unit) {
        ensureMeshStarted()
        tcpTransport.connectToHost(ip.trim(), port, TransportKind.HOTSPOT, onResult)
    }

    fun startLocalHotspot(onError: (String) -> Unit) {
        ensureMeshStarted()
        val s = userSettings.value
        hotspotManager.startLocalHotspot(s.deviceId, s.displayName, s.roomPin, onError)
    }

    fun stopLocalHotspot() {
        hotspotManager.stopLocalHotspot()
    }

    suspend fun disconnectAll() = withContext(Dispatchers.IO) {
        tcpTransport.disconnectAll()
        wifiDirectManager.disconnect()
        hotspotManager.stopLocalHotspot()
        peerDao.markAllDisconnected()
        if (_activeCall.value.channelMode == ChannelMode.OFFLINE) {
            endActiveCall()
        }
    }

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        conversationDao.observeConversation(conversationId)

    fun observeMessages(conversationId: String, searchQuery: String = ""): Flow<List<MessageEntity>> =
        if (searchQuery.isBlank()) messageDao.observeMessages(conversationId)
        else messageDao.searchMessages(conversationId, searchQuery)

    suspend fun clearUnread(conversationId: String) {
        conversationDao.clearUnread(conversationId)
        NotificationOrchestrator.dismissNotification(context, conversationId.hashCode())
        sendReadReceiptIfNeeded(conversationId)
    }

    suspend fun onForegroundResumed() {
        if (authUserFlow.value != null && _isInternetAvailable.value) {
            firestoreRepo.setOnlinePresence(true)
        }
        val activeConvId = NotificationOrchestrator.activeForegroundConversationId ?: return
        clearUnread(activeConvId)
    }

    private suspend fun sendReadReceiptIfNeeded(conversationId: String) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (conv.channelMode == ChannelMode.ONLINE) {
            val myUid = authUserFlow.value?.uid ?: return
            val unreadIncomingIds = messageDao.observeMessages(conversationId).first()
                .filter { !it.isOutgoing && it.senderId != myUid }
                .takeLast(30)
                .map { it.messageId }
            if (unreadIncomingIds.isNotEmpty()) {
                firestoreRepo.markCloudMessagesDeliveredOrRead(
                    convId = conversationId,
                    messageIds = unreadIncomingIds,
                    markRead = true
                )
            }
            return
        }

        if (!messageDao.hasIncomingMessages(conversationId)) return
        val s = userSettings.value
        val latestIncomingTs = messageDao.getLatestIncomingTimestamp(conversationId) ?: 0L
        val receipt = MeshPacket(
            kind = PacketKind.MESSAGE_READ,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = conversationId,
            isGroup = conv.isGroup,
            timestamp = maxOf(System.currentTimeMillis(), latestIncomingTs)
        )
        val targets = resolveTargets(conv)
        if (tcpTransport.broadcastPacket(receipt, targets) == 0 && !conv.isGroup) {
            if (ensurePeerSessionConnected(conv.peerId, conv)) {
                tcpTransport.broadcastPacket(receipt, targets)
            }
        }
    }

    suspend fun togglePinConversation(conversationId: String) {
        conversationDao.togglePin(conversationId)
    }

    suspend fun deleteConversation(conversationId: String) {
        val conv = conversationDao.getConversation(conversationId)
        if (conv?.channelMode == ChannelMode.ONLINE) {
            firestoreRepo.deleteCloudConversation(conversationId)
        }
        messageDao.deleteMessagesForConversation(conversationId)
        conversationDao.deleteConversation(conversationId)
    }

    suspend fun ensureDirectConversationForPeer(peer: PeerEntity): String {
        val convId = directConversationId(userSettings.value.deviceId, peer.peerId)
        val existing = conversationDao.getConversation(convId)
        if (existing == null) {
            conversationDao.upsertConversation(
                ConversationEntity(
                    conversationId = convId,
                    title = peer.displayName,
                    isGroup = false,
                    peerId = peer.peerId,
                    participantIdsCsv = peer.peerId,
                    avatarColorIndex = peer.avatarColorIndex,
                    channelMode = ChannelMode.OFFLINE
                )
            )
        }
        return convId
    }

    suspend fun createLocalGroupConversation(title: String, peerIds: List<String>): String {
        val convId = "GRP-" + UUID.randomUUID().toString().take(8).uppercase()
        val cleanTitle = title.trim().ifEmpty { "Local Group" }
        conversationDao.upsertConversation(
            ConversationEntity(
                conversationId = convId,
                title = cleanTitle,
                isGroup = true,
                participantIdsCsv = peerIds.joinToString(","),
                lastMessagePreview = cleanTitle,
                avatarColorIndex = (cleanTitle.hashCode() and 0x7FFFFFFF) % 6,
                channelMode = ChannelMode.OFFLINE
            )
        )
        val s = userSettings.value
        val invitePacket = MeshPacket(
            kind = PacketKind.GROUP_INVITE,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = convId,
            isGroup = true,
            groupTitle = cleanTitle,
            textBody = peerIds.joinToString(",")
        )
        tcpTransport.broadcastPacket(invitePacket, peerIds)
        return convId
    }

    suspend fun sendTextMessage(
        conversationId: String,
        text: String,
        replyTo: MessageEntity? = null
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val s = userSettings.value
        val conv = conversationDao.getConversation(conversationId) ?: return
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        if (conv.channelMode == ChannelMode.ONLINE) {
            val myUid = authUserFlow.value?.uid ?: run {
                _feedbackMessages.tryEmit(context.getString(R.string.cloud_sign_in_required))
                return
            }
            val senderName = cloudProfile.value?.displayName ?: s.displayName
            val msg = MessageEntity(
                messageId = msgId,
                conversationId = conversationId,
                senderId = myUid,
                senderName = senderName,
                isOutgoing = true,
                type = MessageType.TEXT,
                textContent = trimmed,
                replyToMessageId = replyTo?.messageId,
                replyToPreview = replyTo?.textContent?.take(60),
                status = MessageStatus.PENDING,
                timestamp = now
            )
            messageDao.insertMessage(msg)
            conversationDao.updateLastMessage(conversationId, trimmed, now, 0)

            val members = resolveCloudMembers(conv, myUid)
            val res = firestoreRepo.sendCloudMessage(
                convId = conversationId,
                memberUids = members,
                messageId = msgId,
                senderName = senderName,
                msgType = MessageType.TEXT.name,
                textBody = trimmed,
                replyToId = replyTo?.messageId.orEmpty(),
                replyToPreview = replyTo?.textContent?.take(60).orEmpty(),
                clientTimestampMs = now,
                previewText = trimmed
            )
            messageDao.updateStatus(msgId, if (res.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
            res.onFailure { _feedbackMessages.tryEmit(it.message ?: "Send failed") }
            return
        }

        val msg = MessageEntity(
            messageId = msgId,
            conversationId = conversationId,
            senderId = s.deviceId,
            senderName = s.displayName,
            isOutgoing = true,
            type = MessageType.TEXT,
            textContent = trimmed,
            replyToMessageId = replyTo?.messageId,
            replyToPreview = replyTo?.textContent?.take(60),
            status = MessageStatus.PENDING,
            timestamp = now
        )
        messageDao.insertMessage(msg)
        conversationDao.updateLastMessage(conversationId, trimmed, now, 0)

        val wireBody = if (s.encryptionEnabled) {
            LocalSessionCrypto.encryptText(trimmed, s.roomPin)
        } else {
            trimmed
        }

        val packet = MeshPacket(
            kind = PacketKind.TEXT_MESSAGE,
            senderId = s.deviceId,
            senderName = s.displayName,
            avatarColorIndex = s.avatarColorIndex,
            conversationId = conversationId,
            isGroup = conv.isGroup,
            groupTitle = conv.title,
            messageId = msgId,
            replyToMessageId = replyTo?.messageId,
            replyToPreview = replyTo?.textContent?.take(60),
            textBody = wireBody,
            encrypted = s.encryptionEnabled,
            timestamp = now
        )

        val targets = resolveTargets(conv)
        var sentCount = tcpTransport.broadcastPacket(packet, targets)
        if (sentCount == 0 && ensurePeerSessionConnected(conv.peerId, conv)) {
            sentCount = tcpTransport.broadcastPacket(packet, targets)
        }
        messageDao.updateStatus(
            msgId,
            if (sentCount > 0) MessageStatus.SENT else MessageStatus.FAILED
        )
    }

    suspend fun retryMessage(messageId: String) {
        val msg = messageDao.getMessage(messageId) ?: return
        if (!msg.isOutgoing) return
        val conv = conversationDao.getConversation(msg.conversationId) ?: return
        val s = userSettings.value

        if (conv.channelMode == ChannelMode.ONLINE) {
            val myUid = authUserFlow.value?.uid ?: return
            val senderName = cloudProfile.value?.displayName ?: s.displayName
            val members = resolveCloudMembers(conv, myUid)
            if (msg.type == MessageType.TEXT) {
                val res = firestoreRepo.sendCloudMessage(
                    convId = msg.conversationId,
                    memberUids = members,
                    messageId = msg.messageId,
                    senderName = senderName,
                    msgType = MessageType.TEXT.name,
                    textBody = msg.textContent,
                    replyToId = msg.replyToMessageId.orEmpty(),
                    replyToPreview = msg.replyToPreview.orEmpty(),
                    clientTimestampMs = msg.timestamp,
                    previewText = msg.textContent
                )
                messageDao.updateStatus(msg.messageId, if (res.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
            } else if (!msg.transferId.isNullOrBlank()) {
                resumeTransfer(msg.transferId)
            }
            return
        }

        ensurePeerSessionConnected(conv.peerId, conv)
        val targets = resolveTargets(conv)

        when (msg.type) {
            MessageType.TEXT -> {
                val wireBody = if (s.encryptionEnabled) {
                    LocalSessionCrypto.encryptText(msg.textContent, s.roomPin)
                } else {
                    msg.textContent
                }
                val packet = MeshPacket(
                    kind = PacketKind.TEXT_MESSAGE,
                    senderId = s.deviceId,
                    senderName = s.displayName,
                    avatarColorIndex = s.avatarColorIndex,
                    conversationId = msg.conversationId,
                    isGroup = conv.isGroup,
                    groupTitle = conv.title,
                    messageId = msg.messageId,
                    replyToMessageId = msg.replyToMessageId,
                    replyToPreview = msg.replyToPreview,
                    textBody = wireBody,
                    encrypted = s.encryptionEnabled,
                    timestamp = msg.timestamp
                )
                val sent = tcpTransport.broadcastPacket(packet, targets)
                messageDao.updateStatus(msg.messageId, if (sent > 0) MessageStatus.SENT else MessageStatus.FAILED)
            }

            MessageType.VOICE_NOTE -> {
                val path = msg.mediaLocalPath ?: return
                val file = File(path)
                if (!file.exists()) return
                val fileId = msg.transferId ?: UUID.randomUUID().toString()
                val offer = MeshPacket(
                    kind = PacketKind.FILE_OFFER,
                    senderId = s.deviceId,
                    senderName = s.displayName,
                    conversationId = msg.conversationId,
                    isGroup = conv.isGroup,
                    groupTitle = conv.title,
                    messageId = msg.messageId,
                    fileId = fileId,
                    fileName = msg.fileName ?: file.name,
                    mimeType = msg.mimeType ?: "audio/mp4",
                    fileSizeBytes = file.length(),
                    durationMs = msg.durationMs,
                    timestamp = msg.timestamp
                )
                tcpTransport.sendLocalFileStream(targets, offer, file) { ok ->
                    scope.launch {
                        messageDao.updateStatus(msg.messageId, if (ok) MessageStatus.SENT else MessageStatus.FAILED)
                    }
                }
            }

            else -> {
                msg.transferId?.let { resumeTransfer(it) }
            }
        }
    }

    suspend fun sendTypingState(conversationId: String, isTyping: Boolean) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (conv.channelMode == ChannelMode.ONLINE) {
            firestoreRepo.updateCloudTypingState(conversationId, isTyping)
            return
        }
        val s = userSettings.value
        val packet = MeshPacket(
            kind = PacketKind.TYPING_STATE,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = conversationId,
            isTyping = isTyping
        )
        tcpTransport.broadcastPacket(packet, resolveTargets(conv))
    }

    suspend fun reactToMessage(conversationId: String, messageId: String, emoji: String) {
        messageDao.updateReaction(messageId, emoji)
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (conv.channelMode == ChannelMode.ONLINE) {
            firestoreRepo.reactToCloudMessage(conversationId, messageId, emoji)
            return
        }
        val s = userSettings.value
        val packet = MeshPacket(
            kind = PacketKind.REACTION,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = conversationId,
            messageId = messageId,
            emoji = emoji
        )
        tcpTransport.broadcastPacket(packet, resolveTargets(conv))
    }

    suspend fun deleteMessage(messageId: String) {
        val msg = messageDao.getMessage(messageId)
        messageDao.deleteMessage(messageId)
        if (msg != null) {
            val conv = conversationDao.getConversation(msg.conversationId)
            if (conv?.channelMode == ChannelMode.ONLINE) {
                firestoreRepo.deleteCloudMessage(msg.conversationId, messageId)
            }
        }
    }

    suspend fun sendVoiceNote(conversationId: String, audioFile: File, durationMs: Long) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        val s = userSettings.value
        val msgId = UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val preview = "🎤 ${formatDuration(durationMs)}"

        if (conv.channelMode == ChannelMode.ONLINE) {
            val myUid = authUserFlow.value?.uid ?: return
            val senderName = cloudProfile.value?.displayName ?: s.displayName
            val msg = MessageEntity(
                messageId = msgId,
                conversationId = conversationId,
                senderId = myUid,
                senderName = senderName,
                isOutgoing = true,
                type = MessageType.VOICE_NOTE,
                fileName = audioFile.name,
                mimeType = "audio/mp4",
                fileSizeBytes = audioFile.length(),
                durationMs = durationMs,
                mediaLocalPath = audioFile.absolutePath,
                transferId = fileId,
                status = MessageStatus.PENDING,
                timestamp = now
            )
            messageDao.insertMessage(msg)
            conversationDao.updateLastMessage(conversationId, preview, now, 0)
            val members = resolveCloudMembers(conv, myUid)

            firestoreRepo.uploadChunkedFile(
                convId = conversationId,
                memberUids = members,
                transferId = fileId,
                senderName = senderName,
                fileName = audioFile.name,
                mimeType = "audio/mp4",
                totalBytes = audioFile.length(),
                inputProvider = { if (audioFile.exists()) audioFile.inputStream() else null },
                onProgress = { _, _, _, _ -> },
                onFinished = { outcome, _, err ->
                    if (outcome == CloudTransferOutcome.COMPLETED) {
                        val msgRes = firestoreRepo.sendCloudMessage(
                            convId = conversationId,
                            memberUids = members,
                            messageId = msgId,
                            senderName = senderName,
                            msgType = MessageType.VOICE_NOTE.name,
                            transferId = fileId,
                            fileName = audioFile.name,
                            mimeType = "audio/mp4",
                            fileSizeBytes = audioFile.length(),
                            durationMs = durationMs,
                            clientTimestampMs = now,
                            previewText = preview
                        )
                        messageDao.updateStatus(msgId, if (msgRes.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
                    } else {
                        messageDao.updateStatus(msgId, MessageStatus.FAILED)
                        if (!err.isNullOrBlank()) _feedbackMessages.tryEmit(err)
                    }
                }
            )
            return
        }

        val msg = MessageEntity(
            messageId = msgId,
            conversationId = conversationId,
            senderId = s.deviceId,
            senderName = s.displayName,
            isOutgoing = true,
            type = MessageType.VOICE_NOTE,
            fileName = audioFile.name,
            mimeType = "audio/mp4",
            fileSizeBytes = audioFile.length(),
            durationMs = durationMs,
            mediaLocalPath = audioFile.absolutePath,
            transferId = fileId,
            status = MessageStatus.PENDING,
            timestamp = now
        )
        messageDao.insertMessage(msg)
        conversationDao.updateLastMessage(conversationId, preview, now, 0)

        val offer = MeshPacket(
            kind = PacketKind.FILE_OFFER,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = conversationId,
            isGroup = conv.isGroup,
            groupTitle = conv.title,
            messageId = msgId,
            fileId = fileId,
            fileName = audioFile.name,
            mimeType = "audio/mp4",
            fileSizeBytes = audioFile.length(),
            durationMs = durationMs,
            timestamp = now
        )

        ensurePeerSessionConnected(conv.peerId, conv)
        tcpTransport.sendLocalFileStream(
            targetPeerIds = resolveTargets(conv),
            offerPacket = offer,
            file = audioFile
        ) { ok ->
            scope.launch {
                messageDao.updateStatus(msgId, if (ok) MessageStatus.SENT else MessageStatus.FAILED)
            }
        }
    }

    suspend fun sendUriFile(conversationId: String, uri: Uri) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        val s = userSettings.value
        val (fileName, queriedSize, mimeType) = queryUriMetadata(uri)
        val fileId = UUID.randomUUID().toString()

        // Cache into durable internal outgoing storage so pause/resume across reconnects never loses read permissions
        val cacheDir = File(context.filesDir, "outgoing_cache").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file_$fileId" }
        val cacheFile = File(cacheDir, "${fileId}_$safeName")
        val copiedOk = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        val fileSize = if (copiedOk && cacheFile.length() > 0L) cacheFile.length() else queriedSize
        val durableSource = if (copiedOk && cacheFile.exists()) cacheFile.absolutePath else uri.toString()

        if (conv.channelMode == ChannelMode.ONLINE && fileSize > FirestoreChatRepository.MAX_CLOUD_FILE_BYTES) {
            runCatching { cacheFile.delete() }
            _feedbackMessages.tryEmit(context.getString(R.string.cloud_file_limit_error))
            return
        }

        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val msgType = when {
            mimeType.startsWith("image/") -> MessageType.IMAGE
            mimeType.startsWith("video/") -> MessageType.VIDEO
            else -> MessageType.FILE
        }

        val totalChunks = if (conv.channelMode == ChannelMode.ONLINE) {
            ((fileSize + FirestoreChatRepository.CLOUD_CHUNK_SIZE - 1) / FirestoreChatRepository.CLOUD_CHUNK_SIZE)
                .toInt().coerceAtLeast(1)
        } else 0

        val senderId = if (conv.channelMode == ChannelMode.ONLINE) (authUserFlow.value?.uid ?: s.deviceId) else s.deviceId
        val senderName = if (conv.channelMode == ChannelMode.ONLINE) (cloudProfile.value?.displayName ?: s.displayName) else s.displayName

        val transfer = FileTransferEntity(
            transferId = fileId,
            conversationId = conversationId,
            peerId = conv.peerId ?: "GROUP",
            peerName = conv.title,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = fileSize,
            localPath = durableSource,
            sourceUri = durableSource,
            direction = TransferDirection.OUTGOING,
            status = TransferStatus.TRANSFERRING,
            channelMode = conv.channelMode,
            totalChunks = totalChunks,
            completedChunks = 0
        )
        fileTransferDao.upsertTransfer(transfer)

        val msg = MessageEntity(
            messageId = msgId,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            isOutgoing = true,
            type = msgType,
            textContent = fileName,
            fileName = fileName,
            mimeType = mimeType,
            fileSizeBytes = fileSize,
            mediaLocalPath = durableSource,
            transferId = fileId,
            status = MessageStatus.PENDING,
            timestamp = now
        )
        messageDao.insertMessage(msg)
        conversationDao.updateLastMessage(conversationId, "📎 $fileName", now, 0)

        if (conv.channelMode == ChannelMode.ONLINE) {
            launchCloudUpload(conv, transfer, msg)
        } else {
            launchOfflineFileSend(conv, transfer, msg)
        }
    }

    private fun launchCloudUpload(
        conv: ConversationEntity,
        transfer: FileTransferEntity,
        msg: MessageEntity
    ) {
        val fileId = transfer.transferId
        if (activeCloudTransferJobs[fileId]?.isActive == true) return
        val myUid = authUserFlow.value?.uid ?: return
        val senderName = cloudProfile.value?.displayName ?: userSettings.value.displayName
        val members = resolveCloudMembers(conv, myUid)

        val job = scope.launch {
            try {
                firestoreRepo.uploadChunkedFile(
                    convId = conv.conversationId,
                    memberUids = members,
                    transferId = fileId,
                    senderName = senderName,
                    fileName = transfer.fileName,
                    mimeType = transfer.mimeType,
                    totalBytes = transfer.totalBytes,
                    inputProvider = { openDurableSource(transfer.sourceUri ?: transfer.localPath) },
                    onProgress = { transferredBytes, completedChunks, totalChunks, speedBps ->
                        fileTransferDao.updateProgress(
                            transferId = fileId,
                            transferred = transferredBytes,
                            speed = speedBps,
                            status = TransferStatus.TRANSFERRING,
                            completedChunks = completedChunks
                        )
                        val pct = if (transfer.totalBytes > 0L) ((transferredBytes * 100L) / transfer.totalBytes).toInt() else 0
                        NotificationOrchestrator.showFileTransferProgress(
                            context = context,
                            transferId = fileId,
                            fileName = transfer.fileName,
                            progressPercent = pct,
                            speedText = formatBytesPerSec(speedBps),
                            isIncoming = false
                        )
                    },
                    onFinished = { outcome, sha256, errorMsg ->
                        when (outcome) {
                            CloudTransferOutcome.COMPLETED -> {
                                fileTransferDao.completeTransfer(
                                    transferId = fileId,
                                    localPath = transfer.sourceUri ?: transfer.localPath.orEmpty(),
                                    checksum = sha256,
                                    verified = true
                                )
                                val msgRes = firestoreRepo.sendCloudMessage(
                                    convId = conv.conversationId,
                                    memberUids = members,
                                    messageId = msg.messageId,
                                    senderName = senderName,
                                    msgType = msg.type.name,
                                    textBody = transfer.fileName,
                                    transferId = fileId,
                                    fileName = transfer.fileName,
                                    mimeType = transfer.mimeType,
                                    fileSizeBytes = transfer.totalBytes,
                                    clientTimestampMs = msg.timestamp,
                                    previewText = "📎 ${transfer.fileName}"
                                )
                                messageDao.updateStatus(
                                    msg.messageId,
                                    if (msgRes.isSuccess) MessageStatus.SENT else MessageStatus.FAILED
                                )
                                NotificationOrchestrator.showFileTransferProgress(
                                    context = context,
                                    transferId = fileId,
                                    fileName = transfer.fileName,
                                    progressPercent = 100,
                                    speedText = "",
                                    isIncoming = false,
                                    isComplete = true
                                )
                            }

                            CloudTransferOutcome.PAUSED -> {
                                fileTransferDao.updateStatus(fileId, TransferStatus.PAUSED)
                                if (!errorMsg.isNullOrBlank()) {
                                    _feedbackMessages.tryEmit(errorMsg)
                                }
                            }

                            CloudTransferOutcome.CANCELLED -> {
                                fileTransferDao.updateStatus(fileId, TransferStatus.CANCELLED)
                                messageDao.updateStatus(msg.messageId, MessageStatus.FAILED)
                                deleteOutgoingCacheIfTemp(transfer.sourceUri)
                            }

                            CloudTransferOutcome.FAILED -> {
                                fileTransferDao.updateStatus(fileId, TransferStatus.FAILED)
                                messageDao.updateStatus(msg.messageId, MessageStatus.FAILED)
                                if (!errorMsg.isNullOrBlank()) _feedbackMessages.tryEmit(errorMsg)
                            }
                        }
                    }
                )
            } finally {
                activeCloudTransferJobs.remove(fileId)
            }
        }
        activeCloudTransferJobs[fileId] = job
    }

    private fun launchOfflineFileSend(
        conv: ConversationEntity,
        transfer: FileTransferEntity,
        msg: MessageEntity
    ) {
        val s = userSettings.value
        val fileId = transfer.transferId
        val offer = MeshPacket(
            kind = PacketKind.FILE_OFFER,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = conv.conversationId,
            isGroup = conv.isGroup,
            groupTitle = conv.title,
            messageId = msg.messageId,
            fileId = fileId,
            fileName = transfer.fileName,
            mimeType = transfer.mimeType,
            fileSizeBytes = transfer.totalBytes,
            timestamp = msg.timestamp
        )

        scope.launch {
            ensurePeerSessionConnected(conv.peerId, conv)
            val sourceStr = transfer.sourceUri ?: transfer.localPath ?: ""
            val cacheFile = if (!sourceStr.startsWith("content://") && sourceStr.isNotBlank()) File(sourceStr) else null

            val onProgress: (Long, Long, Long) -> Unit = { sent, total, speed ->
                scope.launch {
                    fileTransferDao.updateProgress(fileId, sent, speed, TransferStatus.TRANSFERRING)
                    val pct = if (total > 0L) ((sent * 100L) / total).toInt() else 0
                    NotificationOrchestrator.showFileTransferProgress(
                        context = context,
                        transferId = fileId,
                        fileName = transfer.fileName,
                        progressPercent = pct,
                        speedText = formatBytesPerSec(speed),
                        isIncoming = false
                    )
                }
            }
            val onComplete: (String, Boolean, Boolean, Long) -> Unit = { sha, ok, paused, bytesSent ->
                scope.launch {
                    when {
                        ok -> {
                            fileTransferDao.completeTransfer(fileId, sourceStr, sha, true)
                            messageDao.updateStatus(msg.messageId, MessageStatus.SENT)
                            NotificationOrchestrator.showFileTransferProgress(
                                context = context,
                                transferId = fileId,
                                fileName = transfer.fileName,
                                progressPercent = 100,
                                speedText = "",
                                isIncoming = false,
                                isComplete = true
                            )
                        }

                        paused -> {
                            fileTransferDao.updateProgress(fileId, bytesSent, 0L, TransferStatus.PAUSED)
                        }

                        else -> {
                            fileTransferDao.updateStatus(fileId, TransferStatus.FAILED)
                            messageDao.updateStatus(msg.messageId, MessageStatus.FAILED)
                            NotificationOrchestrator.showFileTransferProgress(
                                context = context,
                                transferId = fileId,
                                fileName = transfer.fileName,
                                progressPercent = 0,
                                speedText = "",
                                isIncoming = false,
                                isFailed = true
                            )
                        }
                    }
                }
            }

            if (cacheFile != null && cacheFile.exists()) {
                tcpTransport.sendFileStream(
                    targetPeerIds = resolveTargets(conv),
                    offerPacket = offer,
                    uri = Uri.fromFile(cacheFile),
                    onProgress = onProgress,
                    onComplete = onComplete
                )
            } else if (sourceStr.isNotBlank()) {
                tcpTransport.sendFileStream(
                    targetPeerIds = resolveTargets(conv),
                    offerPacket = offer,
                    uri = Uri.parse(sourceStr),
                    onProgress = onProgress,
                    onComplete = onComplete
                )
            } else {
                fileTransferDao.updateStatus(fileId, TransferStatus.FAILED)
            }
        }
    }

    suspend fun pauseTransfer(transferId: String) {
        val transfer = fileTransferDao.getTransfer(transferId) ?: return
        if (transfer.channelMode == ChannelMode.ONLINE) {
            firestoreRepo.pauseCloudTransfer(transferId)
        } else {
            tcpTransport.pauseTransfer(transferId)
        }
        fileTransferDao.updateStatus(transferId, TransferStatus.PAUSED)
    }

    suspend fun resumeTransfer(transferId: String) {
        val transfer = fileTransferDao.getTransfer(transferId) ?: return
        if (transfer.status == TransferStatus.COMPLETED || transfer.status == TransferStatus.CANCELLED) return
        val conv = conversationDao.getConversation(transfer.conversationId) ?: return
        fileTransferDao.updateStatus(transferId, TransferStatus.TRANSFERRING)

        if (transfer.channelMode == ChannelMode.ONLINE) {
            if (authUserFlow.value == null || !_isInternetAvailable.value) {
                fileTransferDao.updateStatus(transferId, TransferStatus.PAUSED)
                return
            }
            if (transfer.direction == TransferDirection.OUTGOING) {
                val msg = messageDao.getMessageByTransferId(transferId) ?: return
                launchCloudUpload(conv, transfer, msg)
            } else {
                val msg = messageDao.getMessageByTransferId(transferId)
                launchCloudDownload(
                    convId = conv.conversationId,
                    transferId = transferId,
                    fileName = transfer.fileName,
                    isVoiceNote = msg?.type == MessageType.VOICE_NOTE
                )
            }
        } else {
            if (transfer.direction == TransferDirection.OUTGOING) {
                val msg = messageDao.getMessageByTransferId(transferId) ?: return
                launchOfflineFileSend(conv, transfer, msg)
            }
        }
    }

    suspend fun resumeAllResumableTransfers() {
        autoResumeOnlineTransfers()
        autoResumeOfflineTransfers()
    }

    private suspend fun autoResumeOnlineTransfers() {
        if (authUserFlow.value == null || !_isInternetAvailable.value) return
        val pending = fileTransferDao.getResumableTransfers(ChannelMode.ONLINE)
        pending.forEach { t ->
            if (!activeCloudTransferJobs.containsKey(t.transferId)) {
                resumeTransfer(t.transferId)
            }
        }
    }

    private suspend fun autoResumeOfflineTransfers(peerIdFilter: String? = null) {
        val pending = fileTransferDao.getResumableTransfers(ChannelMode.OFFLINE)
        pending.filter { it.direction == TransferDirection.OUTGOING }
            .forEach { t ->
                if (peerIdFilter == null || t.peerId == peerIdFilter || t.peerId == "GROUP") {
                    resumeTransfer(t.transferId)
                }
            }
    }

    suspend fun cancelTransfer(transferId: String) {
        val transfer = fileTransferDao.getTransfer(transferId)
        if (transfer?.channelMode == ChannelMode.ONLINE) {
            firestoreRepo.cancelCloudTransfer(transferId)
            activeCloudTransferJobs.remove(transferId)?.cancel()
        } else {
            tcpTransport.cancelTransfer(transferId)
        }
        deleteOutgoingCacheIfTemp(transfer?.sourceUri)
        fileTransferDao.updateStatus(transferId, TransferStatus.CANCELLED)
    }

    private fun openDurableSource(source: String?): java.io.InputStream? {
        if (source.isNullOrBlank()) return null
        return runCatching {
            if (source.startsWith("content://") || source.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(source))
            } else {
                val f = File(source)
                if (f.exists()) f.inputStream() else null
            }
        }.getOrNull()
    }

    private fun deleteOutgoingCacheIfTemp(sourcePath: String?) {
        if (sourcePath.isNullOrBlank()) return
        runCatching {
            val file = File(sourcePath)
            if (file.exists() && file.parentFile?.name == "outgoing_cache") {
                file.delete()
            }
        }
    }

    // --- Calls (Hybrid Online WebRTC + Offline Mesh) ---

    suspend fun startOutgoingCall(conversationId: String, isVideo: Boolean) = withContext(Dispatchers.IO) {
        val conv = conversationDao.getConversation(conversationId) ?: return@withContext
        val s = userSettings.value

        if (conv.channelMode == ChannelMode.ONLINE) {
            val myUid = authUserFlow.value?.uid ?: run {
                _feedbackMessages.tryEmit(context.getString(R.string.cloud_sign_in_required))
                return@withContext
            }
            val calleeUid = conv.remoteUid ?: conv.peerId
            if (calleeUid.isNullOrBlank()) {
                _feedbackMessages.tryEmit(context.getString(R.string.peer_unreachable_error))
                return@withContext
            }
            val callId = UUID.randomUUID().toString()
            handledCloudCallIds.add(callId)
            val callerName = cloudProfile.value?.displayName ?: s.displayName

            try {
                webRtcCallManager.startSession(
                    isVideo = isVideo,
                    isFrontCamera = true,
                    onIceCandidate = { cand ->
                        scope.launch {
                            firestoreRepo.addIceCandidate(
                                callId = callId,
                                sdpMid = cand.sdpMid ?: "0",
                                sdpMLineIndex = cand.sdpMLineIndex,
                                candidate = cand.sdp
                            )
                        }
                    },
                    onConnectionState = { state ->
                        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                            scope.launch {
                                mediaCallEngine.stopTones()
                                val cur = _activeCall.value
                                if (cur.callId == callId && cur.phase != CallPhase.ACTIVE) {
                                    _activeCall.value = cur.copy(
                                        phase = CallPhase.ACTIVE,
                                        connectedAtMs = System.currentTimeMillis()
                                    )
                                }
                            }
                        } else if (state == PeerConnection.PeerConnectionState.FAILED) {
                            scope.launch {
                                if (_activeCall.value.callId == callId) {
                                    _feedbackMessages.tryEmit(context.getString(R.string.peer_unreachable_error))
                                    endActiveCall()
                                }
                            }
                        }
                    }
                )
                val offerSdp = webRtcCallManager.createOffer(isVideo)
                val createRes = firestoreRepo.createCloudCall(
                    callId = callId,
                    conversationId = conversationId,
                    calleeUid = calleeUid,
                    callerName = callerName,
                    videoCall = isVideo,
                    sdpOffer = offerSdp
                )
                if (createRes.isFailure) {
                    webRtcCallManager.disposeCall()
                    _feedbackMessages.tryEmit(createRes.exceptionOrNull()?.message ?: "Call setup failed")
                    return@withContext
                }

                _activeCall.value = ActiveCallSession(
                    callId = callId,
                    peerId = calleeUid,
                    peerName = conv.title,
                    conversationId = conversationId,
                    isVideo = isVideo,
                    isOutgoing = true,
                    phase = CallPhase.OUTGOING_RINGING,
                    isSpeakerOn = isVideo,
                    isCameraOn = isVideo,
                    channelMode = ChannelMode.ONLINE
                )
                mediaCallEngine.startOutgoingRingback()

                firestoreRepo.observeActiveCallSignaling(
                    callId = callId,
                    myUid = myUid,
                    onSessionChanged = { session ->
                        scope.launch {
                            if (session.sdpAnswer.isNotBlank()) {
                                webRtcCallManager.applyRemoteAnswer(session.sdpAnswer)
                                val cur = _activeCall.value
                                if (cur.callId == callId && cur.phase == CallPhase.OUTGOING_RINGING) {
                                    mediaCallEngine.stopTones()
                                    _activeCall.value = cur.copy(
                                        phase = CallPhase.ACTIVE,
                                        connectedAtMs = System.currentTimeMillis()
                                    )
                                }
                            }
                            if (session.callState in setOf("REJECTED", "ENDED", "BUSY")) {
                                cleanUpLocalCloudCallState()
                            }
                        }
                    },
                    onRemoteIceCandidate = { cand ->
                        webRtcCallManager.addRemoteIceCandidate(
                            cand.sdpMid,
                            cand.sdpMLineIndex.toInt(),
                            cand.candidate
                        )
                    }
                )

                outgoingCallTimeoutJob?.cancel()
                outgoingCallTimeoutJob = scope.launch {
                    delay(45_000L)
                    val cur = _activeCall.value
                    if (cur.callId == callId && cur.phase == CallPhase.OUTGOING_RINGING) {
                        endActiveCall()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MeshChatRepo", "Online call start failed", e)
                webRtcCallManager.disposeCall()
                _activeCall.value = ActiveCallSession()
                _feedbackMessages.tryEmit(e.message ?: "Call error")
            }
            return@withContext
        }

        val peerId = conv.peerId
            ?: conv.participantIdsCsv.split(",").firstOrNull()?.takeIf { it.isNotBlank() }
            ?: tcpTransport.connectedPeerIds.value.firstOrNull()
        if (peerId.isNullOrBlank()) {
            _feedbackMessages.tryEmit(context.getString(R.string.peer_unreachable_error))
            return@withContext
        }
        val callId = UUID.randomUUID().toString()

        ensurePeerSessionConnected(peerId, conv)

        val signal = MeshPacket(
            kind = PacketKind.CALL_SIGNAL,
            senderId = s.deviceId,
            senderName = s.displayName,
            conversationId = conversationId,
            callId = callId,
            callSignal = if (isVideo) CallSignalKind.OFFER_VIDEO else CallSignalKind.OFFER_AUDIO
        )
        var sent = tcpTransport.sendPacketToPeer(peerId, signal)
        if (!sent && ensurePeerSessionConnected(peerId, conv)) {
            sent = tcpTransport.sendPacketToPeer(peerId, signal)
        }
        if (!sent) {
            mediaCallEngine.stopCallMedia()
            _activeCall.value = ActiveCallSession()
            _feedbackMessages.tryEmit(context.getString(R.string.peer_unreachable_error))
            return@withContext
        }

        _activeCall.value = ActiveCallSession(
            callId = callId,
            peerId = peerId,
            peerName = conv.title,
            conversationId = conversationId,
            isVideo = isVideo,
            isOutgoing = true,
            phase = CallPhase.OUTGOING_RINGING,
            isSpeakerOn = isVideo,
            isCameraOn = isVideo,
            channelMode = ChannelMode.OFFLINE
        )
        mediaCallEngine.startOutgoingRingback()
        outgoingCallTimeoutJob?.cancel()
        outgoingCallTimeoutJob = scope.launch {
            delay(40_000L)
            val current = _activeCall.value
            if (current.callId == callId && current.phase == CallPhase.OUTGOING_RINGING) {
                endActiveCall()
            }
        }
    }

    private suspend fun handleCloudCallsSnapshot(myUid: String, calls: List<CloudCallSession>) {
        val currentCall = _activeCall.value
        if (currentCall.channelMode == ChannelMode.ONLINE && currentCall.phase != CallPhase.IDLE) {
            val activeDoc = calls.firstOrNull { it.id == currentCall.callId }
            if (activeDoc != null && activeDoc.callState in setOf("REJECTED", "ENDED", "BUSY")) {
                cleanUpLocalCloudCallState()
            }
            return
        }

        val now = System.currentTimeMillis()
        val incoming = calls.firstOrNull {
            it.calleeUid == myUid &&
                it.callState == "RINGING" &&
                !handledCloudCallIds.contains(it.id) &&
                (now - it.startedAtMs) < 90_000L
        } ?: return

        handledCloudCallIds.add(incoming.id)
        pendingIncomingCloudCall = incoming
        _activeCall.value = ActiveCallSession(
            callId = incoming.id,
            peerId = incoming.callerUid,
            peerName = incoming.callerName,
            conversationId = incoming.conversationId,
            isVideo = incoming.videoCall,
            isOutgoing = false,
            phase = CallPhase.INCOMING_RINGING,
            isSpeakerOn = incoming.videoCall,
            isCameraOn = incoming.videoCall,
            channelMode = ChannelMode.ONLINE
        )
        mediaCallEngine.startIncomingRingtone()
        if (!NotificationOrchestrator.isAppInForeground) {
            NotificationOrchestrator.showIncomingCallNotification(
                context = context,
                callId = incoming.id,
                peerId = incoming.callerUid,
                callerName = incoming.callerName,
                isVideo = incoming.videoCall
            )
        }
    }

    suspend fun acceptIncomingCall(callId: String, peerId: String, isVideo: Boolean) = withContext(Dispatchers.IO) {
        outgoingCallTimeoutJob?.cancel()
        val current = _activeCall.value
        val s = userSettings.value
        NotificationOrchestrator.dismissCallNotification(context)
        mediaCallEngine.stopTones()

        if (current.channelMode == ChannelMode.ONLINE) {
            val myUid = authUserFlow.value?.uid ?: return@withContext
            val offerSdp = pendingIncomingCloudCall?.takeIf { it.id == callId }?.sdpOffer
            if (offerSdp.isNullOrBlank()) {
                cleanUpLocalCloudCallState()
                return@withContext
            }
            try {
                _activeCall.value = current.copy(
                    phase = CallPhase.CONNECTING,
                    isSpeakerOn = isVideo,
                    isCameraOn = isVideo
                )
                webRtcCallManager.startSession(
                    isVideo = isVideo,
                    isFrontCamera = true,
                    onIceCandidate = { cand ->
                        scope.launch {
                            firestoreRepo.addIceCandidate(
                                callId = callId,
                                sdpMid = cand.sdpMid ?: "0",
                                sdpMLineIndex = cand.sdpMLineIndex,
                                candidate = cand.sdp
                            )
                        }
                    },
                    onConnectionState = { state ->
                        if (state == PeerConnection.PeerConnectionState.FAILED) {
                            scope.launch { endActiveCall() }
                        }
                    }
                )
                firestoreRepo.observeActiveCallSignaling(
                    callId = callId,
                    myUid = myUid,
                    onSessionChanged = { session ->
                        if (session.callState in setOf("REJECTED", "ENDED", "BUSY")) {
                            scope.launch { cleanUpLocalCloudCallState() }
                        }
                    },
                    onRemoteIceCandidate = { cand ->
                        webRtcCallManager.addRemoteIceCandidate(
                            cand.sdpMid,
                            cand.sdpMLineIndex.toInt(),
                            cand.candidate
                        )
                    }
                )
                val answerSdp = webRtcCallManager.acceptOfferAndCreateAnswer(offerSdp, isVideo)
                firestoreRepo.answerCloudCall(callId, answerSdp)
                _activeCall.value = _activeCall.value.copy(
                    phase = CallPhase.ACTIVE,
                    connectedAtMs = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MeshChatRepo", "Online accept call failed", e)
                endActiveCall()
            }
            return@withContext
        }

        ensurePeerSessionConnected(peerId, null)
        val resolvedName = current.peerName.ifBlank { peerDao.getPeer(peerId)?.displayName ?: peerId }

        _activeCall.value = current.copy(
            callId = callId,
            peerId = peerId,
            peerName = resolvedName,
            isVideo = isVideo,
            phase = CallPhase.ACTIVE,
            connectedAtMs = System.currentTimeMillis(),
            isSpeakerOn = isVideo,
            isCameraOn = isVideo
        )

        tcpTransport.sendPacketToPeer(
            peerId,
            MeshPacket(
                kind = PacketKind.CALL_SIGNAL,
                senderId = s.deviceId,
                senderName = s.displayName,
                conversationId = current.conversationId,
                callId = callId,
                callSignal = CallSignalKind.ACCEPT
            )
        )

        mediaCallEngine.startCallMedia(isVideo) { video, bytes, len ->
            tcpTransport.sendCallMediaFrame(peerId, video, bytes, len)
        }
    }

    suspend fun declineIncomingCall(callId: String, peerId: String) = withContext(Dispatchers.IO) {
        outgoingCallTimeoutJob?.cancel()
        val current = _activeCall.value
        val s = userSettings.value
        NotificationOrchestrator.dismissCallNotification(context)

        if (current.channelMode == ChannelMode.ONLINE) {
            firestoreRepo.updateCloudCallState(callId, "REJECTED")
            cleanUpLocalCloudCallState()
            return@withContext
        }

        mediaCallEngine.stopCallMedia()
        tcpTransport.sendPacketToPeer(
            peerId,
            MeshPacket(
                kind = PacketKind.CALL_SIGNAL,
                senderId = s.deviceId,
                senderName = s.displayName,
                callId = callId,
                callSignal = CallSignalKind.REJECT
            )
        )
        _activeCall.value = ActiveCallSession()
    }

    suspend fun endActiveCall() = withContext(Dispatchers.IO) {
        outgoingCallTimeoutJob?.cancel()
        val current = _activeCall.value
        if (current.phase == CallPhase.IDLE) return@withContext
        val s = userSettings.value
        NotificationOrchestrator.dismissCallNotification(context)

        if (current.channelMode == ChannelMode.ONLINE) {
            if (current.callId.isNotBlank()) {
                firestoreRepo.updateCloudCallState(current.callId, "ENDED")
            }
            cleanUpLocalCloudCallState()
            return@withContext
        }

        mediaCallEngine.stopCallMedia()
        if (current.peerId.isNotBlank()) {
            tcpTransport.sendPacketToPeer(
                current.peerId,
                MeshPacket(
                    kind = PacketKind.CALL_SIGNAL,
                    senderId = s.deviceId,
                    senderName = s.displayName,
                    conversationId = current.conversationId,
                    callId = current.callId,
                    callSignal = CallSignalKind.END
                )
            )
        }
        _activeCall.value = ActiveCallSession()
    }

    private fun cleanUpLocalCloudCallState() {
        outgoingCallTimeoutJob?.cancel()
        NotificationOrchestrator.dismissCallNotification(context)
        mediaCallEngine.stopTones()
        firestoreRepo.stopActiveCallSignaling()
        webRtcCallManager.disposeCall()
        pendingIncomingCloudCall = null
        _activeCall.value = ActiveCallSession()
    }

    fun toggleCallMute() {
        val next = !_activeCall.value.isMuted
        if (_activeCall.value.channelMode == ChannelMode.ONLINE) {
            webRtcCallManager.setMuted(next)
        } else {
            mediaCallEngine.setMuted(next)
        }
        _activeCall.update { it.copy(isMuted = next) }
    }

    fun toggleCallSpeaker() {
        val next = !_activeCall.value.isSpeakerOn
        if (_activeCall.value.channelMode == ChannelMode.ONLINE) {
            webRtcCallManager.setSpeakerphone(next)
        } else {
            mediaCallEngine.setSpeakerphone(next)
        }
        _activeCall.update { it.copy(isSpeakerOn = next) }
    }

    fun toggleCallCamera() {
        val next = !_activeCall.value.isCameraOn
        if (_activeCall.value.channelMode == ChannelMode.ONLINE) {
            webRtcCallManager.setCameraEnabled(next)
        } else {
            mediaCallEngine.setCameraEnabled(next)
        }
        _activeCall.update { it.copy(isCameraOn = next) }
    }

    fun flipCallCamera() {
        if (_activeCall.value.channelMode == ChannelMode.ONLINE) {
            webRtcCallManager.switchCamera()
        }
        _activeCall.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    fun restartCallMediaIfPermitted() {
        if (_activeCall.value.channelMode == ChannelMode.OFFLINE) {
            mediaCallEngine.ensureAudioCaptureRunning()
        }
    }

    fun openLocalFile(pathOrUri: String, mimeType: String?) {
        runCatching {
            val uri = if (pathOrUri.startsWith("content://")) {
                Uri.parse(pathOrUri)
            } else {
                val file = File(pathOrUri)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun saveToDownloads(localPath: String, fileName: String, mimeType: String): Boolean {
        val source = File(localPath)
        if (!source.exists()) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/OfflineChat")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                true
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "OfflineChat"
                ).apply { mkdirs() }
                source.copyTo(File(dir, fileName), overwrite = true)
                true
            }
        }.getOrDefault(false)
    }

    private fun observeTransportEvents() {
        scope.launch {
            tcpTransport.events.collect { event ->
                when (event) {
                    is TransportEvent.PeerConnected -> {
                        val s = userSettings.value
                        val pin = LocalSessionCrypto.generatePairingPin(s.deviceId, event.peerId)
                        val peer = PeerEntity(
                            peerId = event.peerId,
                            displayName = event.peerName,
                            ipAddress = event.ipAddress,
                            transportKind = event.transportKind,
                            avatarColorIndex = event.avatarColorIndex,
                            isConnected = true,
                            verificationPin = pin,
                            lastSeenAt = System.currentTimeMillis()
                        )
                        peerDao.upsertPeer(peer)
                        val convId = ensureDirectConversationForPeer(peer)
                        flushUnsentMessagesForConversation(convId, listOf(event.peerId))
                        autoResumeOfflineTransfers(event.peerId)
                        if (NotificationOrchestrator.isAppInForeground &&
                            NotificationOrchestrator.activeForegroundConversationId == convId
                        ) {
                            sendReadReceiptIfNeeded(convId)
                        }
                    }

                    is TransportEvent.PeerDisconnected -> {
                        peerDao.markDisconnected(event.peerId)
                        if (_activeCall.value.channelMode == ChannelMode.OFFLINE &&
                            _activeCall.value.peerId == event.peerId &&
                            !tcpTransport.hasActiveSessionForPeer(event.peerId)
                        ) {
                            endActiveCall()
                        }
                    }

                    is TransportEvent.PacketReceived -> handleIncomingPacket(event.peerId, event.remoteIp, event.packet)

                    is TransportEvent.FileTransferProgress -> handleIncomingFileProgress(event.progress)
                }
            }
        }
    }

    private suspend fun handleIncomingPacket(peerId: String, remoteIp: String, packet: MeshPacket) {
        val s = userSettings.value
        val convId = if (packet.isGroup && packet.conversationId.isNotBlank()) {
            packet.conversationId
        } else {
            directConversationId(s.deviceId, packet.senderId)
        }

        if (conversationDao.getConversation(convId) == null && packet.kind != PacketKind.CALL_SIGNAL) {
            conversationDao.upsertConversation(
                ConversationEntity(
                    conversationId = convId,
                    title = if (packet.isGroup) packet.groupTitle.ifBlank { "Group" } else packet.senderName,
                    isGroup = packet.isGroup,
                    peerId = if (packet.isGroup) null else packet.senderId,
                    participantIdsCsv = packet.senderId,
                    avatarColorIndex = packet.avatarColorIndex,
                    channelMode = ChannelMode.OFFLINE
                )
            )
        }

        when (packet.kind) {
            PacketKind.TEXT_MESSAGE -> {
                val plain = if (packet.encrypted) {
                    LocalSessionCrypto.decryptText(packet.textBody, s.roomPin) ?: packet.textBody
                } else {
                    packet.textBody
                }
                val msg = MessageEntity(
                    messageId = packet.messageId,
                    conversationId = convId,
                    senderId = packet.senderId,
                    senderName = packet.senderName,
                    isOutgoing = false,
                    type = MessageType.TEXT,
                    textContent = plain,
                    replyToMessageId = packet.replyToMessageId,
                    replyToPreview = packet.replyToPreview,
                    status = MessageStatus.DELIVERED,
                    timestamp = packet.timestamp
                )
                messageDao.insertMessage(msg)
                val isViewing = NotificationOrchestrator.isAppInForeground &&
                    NotificationOrchestrator.activeForegroundConversationId == convId
                conversationDao.updateLastMessage(convId, plain, packet.timestamp, if (isViewing) 0 else 1)

                tcpTransport.sendPacketToPeer(
                    peerId,
                    MeshPacket(
                        kind = PacketKind.MESSAGE_ACK,
                        senderId = s.deviceId,
                        senderName = s.displayName,
                        conversationId = convId,
                        messageId = packet.messageId
                    )
                )

                if (isViewing) {
                    tcpTransport.sendPacketToPeer(
                        peerId,
                        MeshPacket(
                            kind = PacketKind.MESSAGE_READ,
                            senderId = s.deviceId,
                            senderName = s.displayName,
                            conversationId = convId,
                            isGroup = packet.isGroup,
                            timestamp = maxOf(System.currentTimeMillis(), packet.timestamp)
                        )
                    )
                }

                NotificationOrchestrator.showIncomingMessageNotification(
                    context = context,
                    conversationId = convId,
                    peerId = packet.senderId,
                    senderName = packet.senderName,
                    messageText = plain
                )

                if (packet.isGroup) {
                    val conv = conversationDao.getConversation(convId)
                    val relayTargets = conv?.participantIdsCsv?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() && it != packet.senderId && it != s.deviceId }
                        .orEmpty()
                    if (relayTargets.isNotEmpty()) {
                        tcpTransport.broadcastPacket(packet, relayTargets)
                    }
                }
            }

            PacketKind.MESSAGE_ACK -> {
                messageDao.markMessageDelivered(packet.messageId)
            }

            PacketKind.MESSAGE_READ -> {
                val upTo = if (packet.timestamp > 0L) packet.timestamp else System.currentTimeMillis()
                messageDao.markConversationOutgoingRead(convId, upTo)
            }

            PacketKind.TYPING_STATE -> {
                _typingPeers.update { it + (convId to packet.isTyping) }
            }

            PacketKind.REACTION -> {
                messageDao.updateReaction(packet.messageId, packet.emoji)
            }

            PacketKind.FILE_OFFER -> {
                val isVoiceNote = packet.durationMs >= 0L && packet.mimeType.startsWith("audio/")
                val msgType = when {
                    isVoiceNote -> MessageType.VOICE_NOTE
                    packet.mimeType.startsWith("image/") -> MessageType.IMAGE
                    packet.mimeType.startsWith("video/") -> MessageType.VIDEO
                    else -> MessageType.FILE
                }
                if (!isVoiceNote) {
                    fileTransferDao.upsertTransfer(
                        FileTransferEntity(
                            transferId = packet.fileId,
                            conversationId = convId,
                            peerId = packet.senderId,
                            peerName = packet.senderName,
                            fileName = packet.fileName,
                            mimeType = packet.mimeType,
                            totalBytes = packet.fileSizeBytes,
                            transferredBytes = packet.byteOffset,
                            direction = TransferDirection.INCOMING,
                            status = TransferStatus.TRANSFERRING,
                            channelMode = ChannelMode.OFFLINE
                        )
                    )
                }
                val existingMsg = messageDao.getMessage(packet.messageId)
                if (existingMsg == null) {
                    messageDao.insertMessage(
                        MessageEntity(
                            messageId = packet.messageId,
                            conversationId = convId,
                            senderId = packet.senderId,
                            senderName = packet.senderName,
                            isOutgoing = false,
                            type = msgType,
                            textContent = packet.fileName,
                            fileName = packet.fileName,
                            mimeType = packet.mimeType,
                            fileSizeBytes = packet.fileSizeBytes,
                            durationMs = packet.durationMs,
                            transferId = packet.fileId,
                            status = MessageStatus.DELIVERED,
                            timestamp = packet.timestamp
                        )
                    )
                    val preview = if (isVoiceNote) "🎤 ${formatDuration(packet.durationMs)}" else "📎 ${packet.fileName}"
                    val isViewing = NotificationOrchestrator.isAppInForeground &&
                        NotificationOrchestrator.activeForegroundConversationId == convId
                    conversationDao.updateLastMessage(convId, preview, packet.timestamp, if (isViewing) 0 else 1)

                    NotificationOrchestrator.showIncomingMessageNotification(
                        context = context,
                        conversationId = convId,
                        peerId = packet.senderId,
                        senderName = packet.senderName,
                        messageText = preview,
                        isFile = !isVoiceNote
                    )
                }

                tcpTransport.sendPacketToPeer(
                    peerId,
                    MeshPacket(
                        kind = PacketKind.MESSAGE_ACK,
                        senderId = s.deviceId,
                        senderName = s.displayName,
                        conversationId = convId,
                        messageId = packet.messageId
                    )
                )
            }

            PacketKind.CALL_SIGNAL -> handleCallSignal(peerId, remoteIp, convId, packet)

            PacketKind.GROUP_INVITE -> {
                conversationDao.upsertConversation(
                    ConversationEntity(
                        conversationId = packet.conversationId,
                        title = packet.groupTitle.ifBlank { "Local Group" },
                        isGroup = true,
                        participantIdsCsv = (packet.textBody.split(",") + packet.senderId).distinct().joinToString(","),
                        avatarColorIndex = packet.avatarColorIndex,
                        channelMode = ChannelMode.OFFLINE
                    )
                )
            }

            else -> Unit
        }
    }

    private suspend fun handleIncomingFileProgress(progress: IncomingFileProgress) {
        val transfer = fileTransferDao.getTransfer(progress.transferId)
        val localPath = progress.localFile?.absolutePath ?: ""
        if (progress.completed) {
            if (localPath.isNotBlank()) {
                messageDao.attachMediaPathByTransfer(progress.transferId, localPath)
            }
            if (transfer != null) {
                fileTransferDao.completeTransfer(
                    transferId = progress.transferId,
                    localPath = localPath,
                    checksum = progress.sha256,
                    verified = !progress.failed,
                    status = if (progress.failed) TransferStatus.FAILED else TransferStatus.COMPLETED
                )
                NotificationOrchestrator.showFileTransferProgress(
                    context = context,
                    transferId = progress.transferId,
                    fileName = transfer.fileName,
                    progressPercent = 100,
                    speedText = "",
                    isIncoming = true,
                    isComplete = !progress.failed,
                    isFailed = progress.failed
                )
            }
        } else if (progress.paused) {
            if (transfer != null) {
                fileTransferDao.updateProgress(
                    transferId = progress.transferId,
                    transferred = progress.transferredBytes,
                    speed = 0L,
                    status = TransferStatus.PAUSED,
                    partialPath = localPath.ifBlank { null }
                )
            }
        } else if (progress.failed) {
            if (transfer != null) {
                fileTransferDao.updateStatus(progress.transferId, TransferStatus.FAILED)
                NotificationOrchestrator.showFileTransferProgress(
                    context = context,
                    transferId = progress.transferId,
                    fileName = transfer.fileName,
                    progressPercent = 0,
                    speedText = "",
                    isIncoming = true,
                    isFailed = true
                )
            }
        } else if (transfer != null) {
            fileTransferDao.updateProgress(
                transferId = progress.transferId,
                transferred = progress.transferredBytes,
                speed = progress.bytesPerSecond,
                status = TransferStatus.TRANSFERRING,
                partialPath = localPath.ifBlank { null }
            )
            val pct = if (progress.totalBytes > 0) ((progress.transferredBytes * 100) / progress.totalBytes).toInt() else 0
            NotificationOrchestrator.showFileTransferProgress(
                context = context,
                transferId = progress.transferId,
                fileName = transfer.fileName,
                progressPercent = pct,
                speedText = formatBytesPerSec(progress.bytesPerSecond),
                isIncoming = true
            )
        }
    }

    private suspend fun ensurePeerSessionConnected(
        peerId: String?,
        conv: ConversationEntity? = null
    ): Boolean {
        if (!peerId.isNullOrBlank() && tcpTransport.hasActiveSessionForPeer(peerId)) {
            return true
        }
        if (conv != null && !conv.isGroup && tcpTransport.connectedPeerIds.value.size == 1) {
            return true
        }
        val candidateIps = linkedSetOf<String>()
        if (!peerId.isNullOrBlank()) {
            peerDao.getPeer(peerId)?.ipAddress?.takeIf { it.isNotBlank() }?.let { candidateIps.add(it) }
            hotspotManager.lanPeers.value.firstOrNull { it.peerId == peerId }?.ipAddress
                ?.takeIf { it.isNotBlank() }?.let { candidateIps.add(it) }
        }
        val p2pInfo = wifiDirectManager.connectionInfo.value
        if (p2pInfo != null && p2pInfo.groupFormed && !p2pInfo.isGroupOwner) {
            p2pInfo.groupOwnerAddress?.hostAddress?.takeIf { it.isNotBlank() }?.let { candidateIps.add(it) }
        }
        hotspotManager.lanPeers.value.forEach { lp ->
            if (lp.ipAddress.isNotBlank()) candidateIps.add(lp.ipAddress)
        }
        for (ip in candidateIps) {
            if (tcpTransport.connectToHostSuspending(ip, TcpMeshTransport.MESH_PORT, TransportKind.WIFI_DIRECT)) {
                return true
            }
        }
        return !peerId.isNullOrBlank() && tcpTransport.hasActiveSessionForPeer(peerId)
    }

    private suspend fun flushUnsentMessagesForConversation(conversationId: String, targets: List<String>) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (conv.channelMode == ChannelMode.ONLINE) return
        val unsent = messageDao.getUnsentOutgoingMessages(conversationId)
        if (unsent.isEmpty()) return
        val s = userSettings.value
        unsent.forEach { msg ->
            if (msg.type == MessageType.TEXT) {
                val wireBody = if (s.encryptionEnabled) {
                    LocalSessionCrypto.encryptText(msg.textContent, s.roomPin)
                } else {
                    msg.textContent
                }
                val packet = MeshPacket(
                    kind = PacketKind.TEXT_MESSAGE,
                    senderId = s.deviceId,
                    senderName = s.displayName,
                    avatarColorIndex = s.avatarColorIndex,
                    conversationId = conversationId,
                    isGroup = conv.isGroup,
                    groupTitle = conv.title,
                    messageId = msg.messageId,
                    replyToMessageId = msg.replyToMessageId,
                    replyToPreview = msg.replyToPreview,
                    textBody = wireBody,
                    encrypted = s.encryptionEnabled,
                    timestamp = msg.timestamp
                )
                if (tcpTransport.broadcastPacket(packet, targets) > 0) {
                    messageDao.updateStatus(msg.messageId, MessageStatus.SENT)
                }
            }
        }
    }

    private suspend fun handleCallSignal(peerId: String, remoteIp: String, convId: String, packet: MeshPacket) {
        when (packet.callSignal) {
            CallSignalKind.OFFER_AUDIO, CallSignalKind.OFFER_VIDEO -> {
                val existing = _activeCall.value
                if (existing.phase != CallPhase.IDLE && existing.callId != packet.callId) {
                    val s = userSettings.value
                    tcpTransport.sendPacketToPeer(
                        peerId,
                        MeshPacket(
                            kind = PacketKind.CALL_SIGNAL,
                            senderId = s.deviceId,
                            senderName = s.displayName,
                            conversationId = convId,
                            callId = packet.callId,
                            callSignal = CallSignalKind.BUSY
                        )
                    )
                    return
                }
                val isVideo = packet.callSignal == CallSignalKind.OFFER_VIDEO
                _activeCall.value = ActiveCallSession(
                    callId = packet.callId,
                    peerId = peerId,
                    peerName = packet.senderName,
                    conversationId = convId,
                    isVideo = isVideo,
                    isOutgoing = false,
                    phase = CallPhase.INCOMING_RINGING,
                    isSpeakerOn = isVideo,
                    isCameraOn = isVideo,
                    channelMode = ChannelMode.OFFLINE
                )
                mediaCallEngine.startIncomingRingtone()
                if (!NotificationOrchestrator.isAppInForeground) {
                    NotificationOrchestrator.showIncomingCallNotification(
                        context = context,
                        callId = packet.callId,
                        peerId = peerId,
                        callerName = packet.senderName,
                        isVideo = isVideo
                    )
                }
            }

            CallSignalKind.ACCEPT -> {
                outgoingCallTimeoutJob?.cancel()
                mediaCallEngine.stopTones()
                val current = _activeCall.value
                if (current.callId == packet.callId) {
                    _activeCall.value = current.copy(
                        phase = CallPhase.ACTIVE,
                        connectedAtMs = System.currentTimeMillis()
                    )
                    mediaCallEngine.startCallMedia(current.isVideo) { video, bytes, len ->
                        tcpTransport.sendCallMediaFrame(peerId, video, bytes, len)
                    }
                }
            }

            CallSignalKind.REJECT, CallSignalKind.END, CallSignalKind.BUSY -> {
                outgoingCallTimeoutJob?.cancel()
                NotificationOrchestrator.dismissCallNotification(context)
                mediaCallEngine.stopCallMedia()
                _activeCall.value = ActiveCallSession()
            }

            else -> Unit
        }
    }

    private fun resolveTargets(conv: ConversationEntity): List<String> {
        return if (conv.isGroup) {
            conv.participantIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOfNotNull(conv.peerId)
        }
    }

    private fun resolveCloudMembers(conv: ConversationEntity, myUid: String): List<String> {
        val cached = cloudConversationsCache[conv.conversationId]?.memberUids
        if (!cached.isNullOrEmpty()) return cached
        return (conv.participantIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
            listOfNotNull(conv.remoteUid, myUid)).distinct()
    }

    private fun queryUriMetadata(uri: Uri): Triple<String, Long, String> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
            ?: "application/octet-stream"
        return Triple(name, size, mime)
    }

    companion object {
        fun directConversationId(localId: String, peerId: String): String {
            return listOf(localId, peerId).sorted().joinToString("::")
        }

        fun formatDuration(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%02d:%02d".format(min, sec)
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
                bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
                else -> "$bytes B"
            }
        }

        fun formatBytesPerSec(bps: Long): String {
            return "${formatBytes(bps)}/s"
        }
    }
}
