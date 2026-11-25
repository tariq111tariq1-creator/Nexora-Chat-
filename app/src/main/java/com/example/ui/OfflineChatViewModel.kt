package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.ImageProxy
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.OfflineChatApplication
import com.example.data.local.*
import com.example.data.remote.CloudUiState
import com.example.data.remote.CloudUserProfile
import com.example.data.repository.ActiveCallSession
import com.example.data.repository.VoicePlaybackState
import com.example.data.repository.VoiceRecordingState
import com.example.network.ActiveHotspotState
import com.example.network.DiscoveredLanPeer
import com.example.network.DiscoveredWifiDirectPeer
import com.example.network.HotspotJoinPayload
import com.example.notifications.InAppBannerEvent
import com.example.notifications.NotificationOrchestrator
import com.example.permissions.PermissionHelper
import com.example.permissions.PermissionStatusSnapshot
import com.example.service.MeshSessionService
import com.example.ui.theme.ThemePreference
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.VideoTrack

class OfflineChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineChatApplication
    private val repo = app.repository
    private val prefs = app.preferencesRepository

    val userSettings: StateFlow<UserProfileSettings> = repo.userSettings
    val authUser: StateFlow<FirebaseUser?> = repo.authUserFlow
    val cloudProfile: StateFlow<CloudUserProfile?> = repo.cloudProfile
    val cloudSyncState: StateFlow<CloudUiState<String>> = repo.cloudSyncState
    val isInternetAvailable: StateFlow<Boolean> = repo.isInternetAvailable

    private val _authInProgress = MutableStateFlow(false)
    val authInProgress: StateFlow<Boolean> = _authInProgress.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _permissionStatus = MutableStateFlow(PermissionHelper.snapshot(application))
    val permissionStatus: StateFlow<PermissionStatusSnapshot> = _permissionStatus.asStateFlow()

    val wifiDirectEnabled: StateFlow<Boolean> = repo.wifiDirectManager.wifiDirectEnabled
    val isScanning: StateFlow<Boolean> = repo.wifiDirectManager.isScanning
    val wifiDirectPeers: StateFlow<List<DiscoveredWifiDirectPeer>> = repo.wifiDirectManager.discoveredPeers
    val lanPeers: StateFlow<List<DiscoveredLanPeer>> = repo.hotspotManager.lanPeers
    val hotspotState: StateFlow<ActiveHotspotState> = repo.hotspotManager.hotspotState
    val connectedPeerIds: StateFlow<Set<String>> = repo.connectedPeerIds

    val savedPeers: StateFlow<List<PeerEntity>> = repo.peersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> = repo.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transfers: StateFlow<List<FileTransferEntity>> = repo.transfersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val typingPeers: StateFlow<Map<String, Boolean>> = repo.typingPeers
    val activeCall: StateFlow<ActiveCallSession> = repo.activeCall
    val remoteVideoFrame: StateFlow<Bitmap?> = repo.mediaCallEngine.remoteVideoFrame
    val localWebRtcTrack: StateFlow<VideoTrack?> = repo.localVideoTrack
    val remoteWebRtcTrack: StateFlow<VideoTrack?> = repo.remoteVideoTrack
    val webrtcEglContext: EglBase.Context get() = repo.webrtcEglContext

    val recordingState: StateFlow<VoiceRecordingState> = repo.voiceNoteManager.recordingState
    val playbackState: StateFlow<VoicePlaybackState> = repo.voiceNoteManager.playbackState
    val inAppBanners: SharedFlow<InAppBannerEvent> = NotificationOrchestrator.inAppBanners

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repo.feedbackMessages.collect { msg ->
                _statusBannerMessage.value = msg
            }
        }
    }

    fun refreshPermissions() {
        _permissionStatus.value = PermissionHelper.snapshot(getApplication())
    }

    fun onAppForegrounded() {
        viewModelScope.launch { repo.onForegroundResumed() }
    }

    fun startMeshService() {
        MeshSessionService.start(getApplication())
        repo.ensureMeshStarted()
    }

    fun attemptSilentSignIn(activityContext: Context) {
        if (authUser.value != null || _authInProgress.value) return
        viewModelScope.launch {
            repo.attemptSilentSignIn(activityContext)
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        if (_authInProgress.value) return
        _authInProgress.value = true
        _authError.value = null
        viewModelScope.launch {
            val res = repo.signInWithGoogle(activityContext)
            res.onFailure { err ->
                _authError.value = err.message
                _statusBannerMessage.value = err.message
            }
            _authInProgress.value = false
        }
    }

    fun continueOfflineOnly() {
        viewModelScope.launch {
            repo.continueOfflineOnly()
            startMeshService()
        }
    }

    fun setPreferredMode(mode: ChannelMode) {
        viewModelScope.launch { repo.setPreferredMode(mode) }
    }

    fun signOut() {
        viewModelScope.launch {
            repo.signOut()
            _authError.value = null
        }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun addOnlineFriendByCode(rawCodeOrQr: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val res = repo.addOnlineFriendByCode(rawCodeOrQr)
            res.onSuccess { convId ->
                onReady(convId)
            }.onFailure { err ->
                _statusBannerMessage.value = err.message
            }
        }
    }

    fun createOnlineGroup(title: String, selectedFriendConvIds: List<String>, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val res = repo.createOnlineGroupConversation(title, selectedFriendConvIds)
            res.onSuccess { convId ->
                onReady(convId)
            }.onFailure { err ->
                _statusBannerMessage.value = err.message
            }
        }
    }

    fun enterChatRoom(conversationId: String) {
        repo.enterChatRoom(conversationId)
    }

    fun exitChatRoom() {
        repo.exitChatRoom()
    }

    fun completeOnboarding(displayName: String, languageTag: String) {
        viewModelScope.launch {
            prefs.completeOnboarding(displayName, languageTag)
            applyAppLocale(languageTag)
            startMeshService()
        }
    }

    fun setAppLanguage(languageTag: String) {
        viewModelScope.launch {
            prefs.updateLanguageTag(languageTag)
            applyAppLocale(languageTag)
        }
    }

    fun applyAppLocale(languageTag: String) {
        val locales = LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            repo.updateProfileAndSync(name, userSettings.value.avatarColorIndex)
        }
    }

    fun updateAvatarColorIndex(index: Int) {
        viewModelScope.launch {
            repo.updateProfileAndSync(userSettings.value.displayName, index)
        }
    }

    fun updateThemePreference(pref: ThemePreference) {
        viewModelScope.launch { prefs.updateThemePreference(pref) }
    }

    fun updateSecuritySettings(encryptionEnabled: Boolean, roomPin: String) {
        viewModelScope.launch { prefs.updateSecuritySettings(encryptionEnabled, roomPin) }
    }

    fun startNearbyScan() {
        refreshPermissions()
        MeshSessionService.start(getApplication())
        repo.startNearbyScan()
    }

    fun stopNearbyScan() {
        repo.stopNearbyScan()
    }

    fun startLocalHotspot() {
        refreshPermissions()
        MeshSessionService.start(getApplication())
        repo.startLocalHotspot { errorMsg ->
            _statusBannerMessage.value = errorMsg
        }
    }

    fun stopLocalHotspot() {
        repo.stopLocalHotspot()
    }

    fun connectWifiDirectPeer(deviceAddress: String) {
        MeshSessionService.start(getApplication())
        repo.connectWifiDirectPeer(deviceAddress) { ok ->
            if (!ok) _statusBannerMessage.value = "Wi-Fi Direct connection failed"
        }
    }

    fun connectDirectIp(ip: String, port: Int = 8988) {
        MeshSessionService.start(getApplication())
        repo.connectDirectIp(ip, port) { ok ->
            if (!ok) _statusBannerMessage.value = "Could not reach $ip:$port"
        }
    }

    fun connectFromQr(rawQr: String): HotspotJoinPayload? {
        val payload = HotspotJoinPayload.fromQrString(rawQr) ?: return null
        repo.hotspotManager.connectToHotspotWifi(
            ssid = payload.ssid,
            passphrase = payload.passphrase,
            onConnected = { connectDirectIp(payload.hostIp, payload.port) },
            onFallbackNeeded = {
                _statusBannerMessage.value = "${payload.ssid} • ${payload.passphrase}"
                connectDirectIp(payload.hostIp, payload.port)
            }
        )
        return payload
    }

    fun disconnectAll() {
        viewModelScope.launch(Dispatchers.IO) { repo.disconnectAll() }
    }

    fun openPeerChat(peer: PeerEntity, onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val convId = repo.ensureDirectConversationForPeer(peer)
            withContext(Dispatchers.Main) { onReady(convId) }
        }
    }

    fun createGroupChat(title: String, peerIds: List<String>, onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val convId = repo.createLocalGroupConversation(title, peerIds)
            withContext(Dispatchers.Main) { onReady(convId) }
        }
    }

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        repo.observeConversation(conversationId)

    fun observeMessages(conversationId: String, query: String): Flow<List<MessageEntity>> =
        repo.observeMessages(conversationId, query)

    fun clearUnread(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.clearUnread(conversationId) }
    }

    fun togglePinConversation(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.togglePinConversation(conversationId) }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteConversation(conversationId) }
    }

    fun sendTextMessage(conversationId: String, text: String, replyTo: MessageEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) { repo.sendTextMessage(conversationId, text, replyTo) }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.retryMessage(messageId) }
    }

    fun sendTypingState(conversationId: String, isTyping: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repo.sendTypingState(conversationId, isTyping) }
    }

    fun reactToMessage(conversationId: String, messageId: String, emoji: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.reactToMessage(conversationId, messageId, emoji) }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteMessage(messageId) }
    }

    fun startVoiceNoteRecording(): Boolean = repo.voiceNoteManager.startRecording()

    fun stopAndSendVoiceNote(conversationId: String) {
        val result = repo.voiceNoteManager.stopRecording(discard = false) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.sendVoiceNote(conversationId, result.first, result.second)
        }
    }

    fun cancelVoiceNoteRecording() {
        repo.voiceNoteManager.stopRecording(discard = true)
    }

    fun toggleVoicePlayback(messageId: String, filePath: String) {
        repo.voiceNoteManager.togglePlayback(messageId, filePath)
    }

    fun sendFileUri(conversationId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { repo.sendUriFile(conversationId, uri) }
    }

    fun pauseTransfer(transferId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.pauseTransfer(transferId) }
    }

    fun resumeTransfer(transferId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.resumeTransfer(transferId) }
    }

    fun resumeAllTransfers() {
        viewModelScope.launch(Dispatchers.IO) { repo.resumeAllResumableTransfers() }
    }

    fun cancelTransfer(transferId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.cancelTransfer(transferId) }
    }

    fun openFile(pathOrUri: String, mimeType: String?) {
        repo.openLocalFile(pathOrUri, mimeType)
    }

    fun saveToDownloads(localPath: String, fileName: String, mimeType: String): Boolean {
        return repo.saveToDownloads(localPath, fileName, mimeType)
    }

    fun startCall(conversationId: String, isVideo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repo.startOutgoingCall(conversationId, isVideo) }
    }

    fun acceptIncomingCall() {
        val call = activeCall.value
        viewModelScope.launch(Dispatchers.IO) { repo.acceptIncomingCall(call.callId, call.peerId, call.isVideo) }
    }

    fun declineIncomingCall() {
        val call = activeCall.value
        viewModelScope.launch(Dispatchers.IO) { repo.declineIncomingCall(call.callId, call.peerId) }
    }

    fun endCall() {
        viewModelScope.launch(Dispatchers.IO) { repo.endActiveCall() }
    }

    fun toggleCallMute() = repo.toggleCallMute()
    fun toggleCallSpeaker() = repo.toggleCallSpeaker()
    fun toggleCallCamera() = repo.toggleCallCamera()
    fun flipCallCamera() = repo.flipCallCamera()
    fun restartCallMediaIfPermitted() = repo.restartCallMediaIfPermitted()

    fun forwardCameraFrame(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        repo.mediaCallEngine.sendCameraFrame(imageProxy, isFrontCamera)
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }
}
