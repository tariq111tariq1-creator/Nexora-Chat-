package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CloudTransferOutcome {
    COMPLETED,
    PAUSED,
    CANCELLED,
    FAILED
}

class FirestoreChatRepository(private val context: Context) {

    companion object {
        const val CLOUD_CHUNK_SIZE = 384 * 1024 // 384 KB raw -> ~512 KB Base64 (< 1 MiB doc limit)
        const val MAX_CLOUD_FILE_BYTES = 25L * 1024L * 1024L // 25 MB per cloud file
    }

    private val db: FirebaseFirestore =
        FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _myProfile = MutableStateFlow<CloudUserProfile?>(null)
    val myProfile: StateFlow<CloudUserProfile?> = _myProfile.asStateFlow()

    private var conversationsReg: ListenerRegistration? = null
    private var callsReg: ListenerRegistration? = null
    private var activeRoomMessagesReg: ListenerRegistration? = null
    private var activeCallReg: ListenerRegistration? = null
    private var activeCandidatesReg: ListenerRegistration? = null

    private val pausedTransfers = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()

    fun currentUid(): String? = auth.currentUser?.uid

    fun shareCodeForUid(uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(8)
        return "OC-$hex"
    }

    suspend fun ensureUserProfile(
        user: FirebaseUser,
        fallbackName: String,
        avatarColorIndex: Int
    ): Result<CloudUserProfile> = withContext(Dispatchers.IO) {
        val uid = user.uid
        val shareCode = shareCodeForUid(uid)
        val cleanName = (user.displayName?.takeIf { it.isNotBlank() } ?: fallbackName)
            .trim().take(60).ifEmpty { "User" }
        val email = (user.email ?: "").trim().take(120)
        val color = avatarColorIndex.coerceIn(0, 10)

        try {
            val userDocRef = db.collection("users").document(uid)
            val snap = userDocRef.get().awaitResult()
            if (!snap.exists()) {
                val createMap = mapOf(
                    "ownerUid" to uid,
                    "shareCode" to shareCode,
                    "displayName" to cleanName,
                    "email" to email,
                    "avatarColorIndex" to color,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userDocRef.set(createMap).awaitResult()
            } else {
                userDocRef.update(
                    mapOf(
                        "displayName" to cleanName,
                        "email" to email,
                        "avatarColorIndex" to color,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }

            val dirRef = db.collection("directory").document(shareCode)
            val dirSnap = dirRef.get().awaitResult()
            if (!dirSnap.exists()) {
                dirRef.set(
                    mapOf(
                        "ownerUid" to uid,
                        "shareCode" to shareCode,
                        "displayName" to cleanName,
                        "avatarColorIndex" to color,
                        "onlineState" to "ONLINE",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            } else {
                dirRef.update(
                    mapOf(
                        "displayName" to cleanName,
                        "avatarColorIndex" to color,
                        "onlineState" to "ONLINE",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }

            val refreshed = userDocRef.get().awaitResult()
                .toObject(CloudUserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: CloudUserProfile(
                    id = uid,
                    ownerUid = uid,
                    shareCode = shareCode,
                    displayName = cleanName,
                    email = email,
                    avatarColorIndex = color
                )
            _myProfile.value = refreshed
            Result.success(refreshed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(handleFirestoreError(e, OperationType.WRITE, "users/$uid")))
        }
    }

    suspend fun updateProfileDisplay(displayName: String, avatarColorIndex: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val uid = currentUid() ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
            val shareCode = _myProfile.value?.shareCode?.ifBlank { shareCodeForUid(uid) } ?: shareCodeForUid(uid)
            val cleanName = displayName.trim().take(60).ifEmpty { "User" }
            val color = avatarColorIndex.coerceIn(0, 10)
            try {
                db.collection("users").document(uid).update(
                    mapOf(
                        "displayName" to cleanName,
                        "avatarColorIndex" to color,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
                db.collection("directory").document(shareCode).update(
                    mapOf(
                        "displayName" to cleanName,
                        "avatarColorIndex" to color,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
                _myProfile.value = _myProfile.value?.copy(displayName = cleanName, avatarColorIndex = color)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(Exception(handleFirestoreError(e, OperationType.UPDATE, "users/$uid")))
            }
        }

    suspend fun setOnlinePresence(online: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        val uid = currentUid() ?: return@withContext
        val shareCode = _myProfile.value?.shareCode?.ifBlank { shareCodeForUid(uid) } ?: shareCodeForUid(uid)
        runCatching {
            db.collection("directory").document(shareCode).update(
                mapOf(
                    "onlineState" to if (online) "ONLINE" else "OFFLINE",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()
        }
    }

    suspend fun lookupFriendByCode(rawCodeOrQr: String): Result<CloudDirectoryEntry?> =
        withContext(Dispatchers.IO) {
            currentUid() ?: return@withContext Result.failure(IllegalStateException("Sign in required"))
            val code = FriendQrPayload.fromQrOrCode(rawCodeOrQr)
            if (code.length < 4 || code.length > 32) {
                return@withContext Result.success(null)
            }
            try {
                val snap = db.collection("directory").document(code).get().awaitResult()
                if (!snap.exists()) return@withContext Result.success(null)
                val entry = snap.toObject(
                    CloudDirectoryEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                Result.success(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(Exception(handleFirestoreError(e, OperationType.GET, "directory/$code")))
            }
        }

    suspend fun createOrOpenDirectConversation(
        myName: String,
        myColor: Int,
        friend: CloudDirectoryEntry
    ): Result<CloudConversation> = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext Result.failure(IllegalStateException("Sign in required"))
        val members = listOf(myUid, friend.ownerUid).distinct().sorted()
        val convId = "ONL-" + members.joinToString("_")
        try {
            val docRef = db.collection("conversations").document(convId)
            val existing = docRef.get().awaitResult()
            if (!existing.exists()) {
                val namesMap = mapOf(
                    myUid to myName.trim().take(60).ifEmpty { "Me" },
                    friend.ownerUid to friend.displayName.trim().take(60).ifEmpty { friend.shareCode }
                )
                val colorsMap = mapOf(
                    myUid to myColor.coerceIn(0, 10),
                    friend.ownerUid to friend.avatarColorIndex.coerceIn(0, 10)
                )
                val createMap = mapOf(
                    "memberUids" to members,
                    "participantNames" to namesMap,
                    "participantColors" to colorsMap,
                    "title" to friend.displayName.trim().take(80),
                    "groupChat" to false,
                    "createdBy" to myUid,
                    "lastMessagePreview" to "",
                    "lastSenderUid" to "",
                    "lastMessageAtMs" to System.currentTimeMillis(),
                    "typingUids" to emptyList<String>(),
                    "readAtMsMap" to emptyMap<String, Long>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createMap).awaitResult()
            }
            val finalSnap = docRef.get().awaitResult()
            val model = finalSnap.toObject(
                CloudConversation::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: CloudConversation(
                id = convId,
                memberUids = members,
                title = friend.displayName,
                groupChat = false,
                createdBy = myUid
            )
            Result.success(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(handleFirestoreError(e, OperationType.WRITE, "conversations/$convId")))
        }
    }

    suspend fun createOnlineGroupConversation(
        myName: String,
        myColor: Int,
        title: String,
        friendUidsAndNames: Map<String, Pair<String, Int>>
    ): Result<CloudConversation> = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext Result.failure(IllegalStateException("Sign in required"))
        val convId = "OGRP-" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        val members = (listOf(myUid) + friendUidsAndNames.keys).distinct().take(50)
        val cleanTitle = title.trim().take(80).ifEmpty { "Online Group" }
        val namesMap = buildMap {
            put(myUid, myName.trim().take(60).ifEmpty { "Me" })
            friendUidsAndNames.forEach { (uid, pair) -> put(uid, pair.first.trim().take(60)) }
        }
        val colorsMap = buildMap {
            put(myUid, myColor.coerceIn(0, 10))
            friendUidsAndNames.forEach { (uid, pair) -> put(uid, pair.second.coerceIn(0, 10)) }
        }
        try {
            db.collection("conversations").document(convId).set(
                mapOf(
                    "memberUids" to members,
                    "participantNames" to namesMap,
                    "participantColors" to colorsMap,
                    "title" to cleanTitle,
                    "groupChat" to true,
                    "createdBy" to myUid,
                    "lastMessagePreview" to cleanTitle,
                    "lastSenderUid" to myUid,
                    "lastMessageAtMs" to System.currentTimeMillis(),
                    "typingUids" to emptyList<String>(),
                    "readAtMsMap" to emptyMap<String, Long>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()
            val snap = db.collection("conversations").document(convId).get().awaitResult()
            val model = snap.toObject(
                CloudConversation::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: CloudConversation(
                id = convId,
                memberUids = members,
                participantNames = namesMap,
                title = cleanTitle,
                groupChat = true,
                createdBy = myUid
            )
            Result.success(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(handleFirestoreError(e, OperationType.CREATE, "conversations/$convId")))
        }
    }

    fun startGlobalListeners(
        uid: String,
        onConversationsUpdated: (List<CloudConversation>) -> Unit,
        onCallsUpdated: (List<CloudCallSession>) -> Unit,
        onError: (String) -> Unit
    ) {
        stopGlobalListenersSync()

        conversationsReg = db.collection("conversations")
            .whereArrayContains("memberUids", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val msg = handleFirestoreError(error, OperationType.LIST, "conversations")
                    onError(msg)
                    return@addSnapshotListener
                }
                val items = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.toObject(
                        CloudConversation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }.sortedByDescending { it.lastMessageAtMs }
                onConversationsUpdated(items)
            }

        callsReg = db.collection("calls")
            .whereArrayContains("memberUids", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, "calls")
                    return@addSnapshotListener
                }
                val calls = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.toObject(
                        CloudCallSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                onCallsUpdated(calls)
            }
    }

    fun startRoomMessagesListener(
        convId: String,
        onMessagesUpdated: (List<CloudMessage>) -> Unit,
        onError: (String) -> Unit
    ) {
        activeRoomMessagesReg?.remove()
        activeRoomMessagesReg = db.collection("conversations")
            .document(convId)
            .collection("messages")
            .orderBy("clientTimestampMs", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(handleFirestoreError(error, OperationType.LIST, "conversations/$convId/messages"))
                    return@addSnapshotListener
                }
                val list = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.toObject(
                        CloudMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                onMessagesUpdated(list)
            }
    }

    fun stopRoomMessagesListener() {
        activeRoomMessagesReg?.remove()
        activeRoomMessagesReg = null
    }

    fun stopGlobalListenersSync() {
        conversationsReg?.remove()
        conversationsReg = null
        callsReg?.remove()
        callsReg = null
        activeRoomMessagesReg?.remove()
        activeRoomMessagesReg = null
        activeCallReg?.remove()
        activeCallReg = null
        activeCandidatesReg?.remove()
        activeCandidatesReg = null
    }

    suspend fun detachAllOnSignOut() = withContext(NonCancellable) {
        setOnlinePresence(false)
        stopGlobalListenersSync()
        _myProfile.value = null
    }

    suspend fun sendCloudMessage(
        convId: String,
        memberUids: List<String>,
        messageId: String,
        senderName: String,
        msgType: String,
        textBody: String = "",
        replyToId: String = "",
        replyToPreview: String = "",
        transferId: String = "",
        fileName: String = "",
        mimeType: String = "",
        fileSizeBytes: Long = 0L,
        durationMs: Long = 0L,
        clientTimestampMs: Long = System.currentTimeMillis(),
        previewText: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext Result.failure(IllegalStateException("Sign in required"))
        val safeMembers = (memberUids + myUid).distinct().take(50)
        try {
            val msgRef = db.collection("conversations").document(convId)
                .collection("messages").document(messageId)
            msgRef.set(
                mapOf(
                    "memberUids" to safeMembers,
                    "senderUid" to myUid,
                    "senderName" to senderName.trim().take(60).ifEmpty { "Me" },
                    "msgType" to msgType,
                    "textBody" to textBody.take(4000),
                    "replyToId" to replyToId.take(128),
                    "replyToPreview" to replyToPreview.take(200),
                    "reactionEmoji" to "",
                    "transferId" to transferId.take(128),
                    "fileName" to fileName.take(256),
                    "mimeType" to mimeType.take(128),
                    "fileSizeBytes" to fileSizeBytes.coerceIn(0L, 52428800L),
                    "durationMs" to durationMs.coerceIn(0L, 3600000L),
                    "deliveredUids" to listOf(myUid),
                    "readUids" to listOf(myUid),
                    "clientTimestampMs" to clientTimestampMs,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()

            db.collection("conversations").document(convId).update(
                mapOf(
                    "lastMessagePreview" to previewText.take(300),
                    "lastSenderUid" to myUid,
                    "lastMessageAtMs" to clientTimestampMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(handleFirestoreError(e, OperationType.CREATE, "conversations/$convId/messages/$messageId")))
        }
    }

    suspend fun markCloudMessagesDeliveredOrRead(
        convId: String,
        messageIds: List<String>,
        markRead: Boolean
    ) = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext
        if (messageIds.isEmpty()) return@withContext
        runCatching {
            val batch = db.batch()
            messageIds.take(40).forEach { msgId ->
                val ref = db.collection("conversations").document(convId)
                    .collection("messages").document(msgId)
                val updates = mutableMapOf<String, Any>(
                    "deliveredUids" to FieldValue.arrayUnion(myUid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (markRead) {
                    updates["readUids"] = FieldValue.arrayUnion(myUid)
                }
                batch.update(ref, updates)
            }
            if (markRead) {
                val convRef = db.collection("conversations").document(convId)
                batch.update(
                    convRef,
                    mapOf(
                        "readAtMsMap.$myUid" to System.currentTimeMillis(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().awaitResult()
        }.onFailure {
            if (it is CancellationException) throw it
            Log.d("FirestoreChatRepo", "Receipt update skipped: ${it.message}")
        }
    }

    suspend fun updateCloudTypingState(convId: String, isTyping: Boolean) = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext
        runCatching {
            db.collection("conversations").document(convId).update(
                mapOf(
                    "typingUids" to if (isTyping) FieldValue.arrayUnion(myUid) else FieldValue.arrayRemove(myUid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()
        }
    }

    suspend fun reactToCloudMessage(convId: String, messageId: String, emoji: String) = withContext(Dispatchers.IO) {
        currentUid() ?: return@withContext
        runCatching {
            db.collection("conversations").document(convId)
                .collection("messages").document(messageId)
                .update(
                    mapOf(
                        "reactionEmoji" to emoji.take(16),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
        }
    }

    suspend fun deleteCloudMessage(convId: String, messageId: String) = withContext(Dispatchers.IO) {
        currentUid() ?: return@withContext
        runCatching {
            db.collection("conversations").document(convId)
                .collection("messages").document(messageId)
                .delete().awaitResult()
        }
    }

    suspend fun deleteCloudConversation(convId: String) = withContext(Dispatchers.IO) {
        currentUid() ?: return@withContext
        runCatching {
            db.collection("conversations").document(convId).delete().awaitResult()
        }
    }

    fun pauseCloudTransfer(transferId: String) {
        pausedTransfers.add(transferId)
    }

    fun cancelCloudTransfer(transferId: String) {
        cancelledTransfers.add(transferId)
        pausedTransfers.remove(transferId)
    }

    suspend fun uploadChunkedFile(
        convId: String,
        memberUids: List<String>,
        transferId: String,
        senderName: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        inputProvider: () -> InputStream?,
        onProgress: suspend (transferredBytes: Long, completedChunks: Int, totalChunks: Int, speedBps: Long) -> Unit,
        onFinished: suspend (outcome: CloudTransferOutcome, sha256: String, errorMsg: String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val myUid = currentUid()
        if (myUid == null) {
            onFinished(CloudTransferOutcome.FAILED, "", "Sign in required")
            return@withContext
        }
        if (totalBytes > MAX_CLOUD_FILE_BYTES) {
            onFinished(
                CloudTransferOutcome.FAILED,
                "",
                context.getString(R.string.cloud_file_limit_error)
            )
            return@withContext
        }
        pausedTransfers.remove(transferId)
        cancelledTransfers.remove(transferId)

        val safeMembers = (memberUids + myUid).distinct().take(50)
        val totalChunks = ((totalBytes + CLOUD_CHUNK_SIZE - 1) / CLOUD_CHUNK_SIZE).toInt().coerceAtLeast(1)
        val transferRef = db.collection("conversations").document(convId)
            .collection("transfers").document(transferId)

        try {
            val existingSnap = transferRef.get().awaitResult()
            val alreadyUploaded = if (existingSnap.exists()) {
                val meta = existingSnap.toObject(CloudTransferMeta::class.java)
                if (meta?.transferState == "READY" && meta.sha256.isNotBlank()) {
                    onProgress(totalBytes, totalChunks, totalChunks, 0L)
                    onFinished(CloudTransferOutcome.COMPLETED, meta.sha256, null)
                    return@withContext
                }
                (meta?.uploadedChunkCount ?: 0L).toInt().coerceIn(0, totalChunks)
            } else {
                transferRef.set(
                    mapOf(
                        "memberUids" to safeMembers,
                        "senderUid" to myUid,
                        "senderName" to senderName.trim().take(60).ifEmpty { "Me" },
                        "fileName" to fileName.take(256),
                        "mimeType" to mimeType.take(128),
                        "totalBytes" to totalBytes.coerceAtLeast(0L),
                        "chunkSizeBytes" to CLOUD_CHUNK_SIZE,
                        "totalChunks" to totalChunks,
                        "uploadedChunkCount" to 0,
                        "sha256" to "",
                        "transferState" to "UPLOADING",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
                0
            }

            if (alreadyUploaded > 0) {
                transferRef.update(
                    mapOf(
                        "transferState" to "UPLOADING",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var uploadedBytes = (alreadyUploaded.toLong() * CLOUD_CHUNK_SIZE).coerceAtMost(totalBytes)
            val buffer = ByteArray(CLOUD_CHUNK_SIZE)
            var currentIndex = 0
            var outcome = CloudTransferOutcome.COMPLETED

            inputProvider()?.use { stream ->
                while (currentIndex < totalChunks) {
                    if (cancelledTransfers.contains(transferId)) {
                        outcome = CloudTransferOutcome.CANCELLED
                        break
                    }
                    if (pausedTransfers.contains(transferId)) {
                        outcome = CloudTransferOutcome.PAUSED
                        break
                    }

                    val readTotal = readUpTo(stream, buffer)
                    if (readTotal <= 0) break
                    digest.update(buffer, 0, readTotal)

                    if (currentIndex >= alreadyUploaded) {
                        val tStart = System.currentTimeMillis()
                        val b64 = Base64.encodeToString(buffer, 0, readTotal, Base64.NO_WRAP)
                        transferRef.collection("chunks").document(currentIndex.toString()).set(
                            mapOf(
                                "chunkIndex" to currentIndex,
                                "dataBase64" to b64,
                                "byteCount" to readTotal,
                                "senderUid" to myUid,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        ).awaitResult()

                        uploadedBytes = (uploadedBytes + readTotal).coerceAtMost(totalBytes)
                        val elapsedMs = (System.currentTimeMillis() - tStart).coerceAtLeast(1L)
                        val speed = (readTotal * 1000L) / elapsedMs

                        transferRef.update(
                            mapOf(
                                "uploadedChunkCount" to (currentIndex + 1),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).awaitResult()

                        onProgress(uploadedBytes, currentIndex + 1, totalChunks, speed)
                    }
                    currentIndex++
                }
            } ?: run {
                onFinished(CloudTransferOutcome.FAILED, "", "Unable to read local file")
                return@withContext
            }

            when (outcome) {
                CloudTransferOutcome.COMPLETED -> {
                    val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
                    transferRef.update(
                        mapOf(
                            "uploadedChunkCount" to totalChunks,
                            "sha256" to shaHex,
                            "transferState" to "READY",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).awaitResult()
                    onProgress(totalBytes, totalChunks, totalChunks, 0L)
                    onFinished(CloudTransferOutcome.COMPLETED, shaHex, null)
                }

                CloudTransferOutcome.PAUSED -> {
                    runCatching {
                        transferRef.update(
                            mapOf(
                                "transferState" to "PAUSED",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).awaitResult()
                    }
                    onFinished(CloudTransferOutcome.PAUSED, "", null)
                }

                CloudTransferOutcome.CANCELLED -> {
                    runCatching {
                        transferRef.update(
                            mapOf(
                                "transferState" to "CANCELLED",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).awaitResult()
                    }
                    onFinished(CloudTransferOutcome.CANCELLED, "", null)
                }

                CloudTransferOutcome.FAILED -> {
                    onFinished(CloudTransferOutcome.FAILED, "", "Transfer failed")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = handleFirestoreError(e, OperationType.WRITE, "conversations/$convId/transfers/$transferId")
            runCatching {
                transferRef.update(
                    mapOf(
                        "transferState" to "PAUSED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }
            onFinished(CloudTransferOutcome.PAUSED, "", msg)
        }
    }

    suspend fun downloadChunkedFile(
        convId: String,
        transferId: String,
        fallbackFileName: String,
        onProgress: suspend (transferredBytes: Long, totalBytes: Long, completedChunks: Int, totalChunks: Int, speedBps: Long, partialPath: String) -> Unit,
        onFinished: suspend (outcome: CloudTransferOutcome, localFile: File?, sha256: String, verified: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (currentUid() == null) {
            onFinished(CloudTransferOutcome.FAILED, null, "", false)
            return@withContext
        }
        pausedTransfers.remove(transferId)
        cancelledTransfers.remove(transferId)

        val transferRef = db.collection("conversations").document(convId)
            .collection("transfers").document(transferId)
        val dir = File(context.filesDir, "received_files").apply { mkdirs() }
        val safeName = fallbackFileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file_$transferId" }
        val finalFile = File(dir, "${transferId.take(6)}_$safeName")
        val partFile = File(dir, ".part_${transferId}_$safeName")

        if (finalFile.exists() && finalFile.length() > 0L) {
            val sha = computeFileSha256(finalFile)
            onFinished(CloudTransferOutcome.COMPLETED, finalFile, sha, true)
            return@withContext
        }

        try {
            var meta: CloudTransferMeta? = null
            repeat(20) {
                val snap = transferRef.get().awaitResult()
                if (snap.exists()) {
                    meta = snap.toObject(CloudTransferMeta::class.java)
                    if (meta != null) return@repeat
                }
                delay(400L)
            }
            val currentMeta = meta ?: run {
                onFinished(CloudTransferOutcome.FAILED, null, "", false)
                return@withContext
            }

            val chunkSize = currentMeta.chunkSizeBytes.toInt().coerceAtLeast(1)
            val totalChunks = currentMeta.totalChunks.toInt().coerceAtLeast(1)
            val totalBytes = currentMeta.totalBytes.coerceAtLeast(1L)

            val existingLen = if (partFile.exists()) partFile.length() else 0L
            var nextChunk = (existingLen / chunkSize).toInt().coerceIn(0, totalChunks)
            val alignedBytes = nextChunk.toLong() * chunkSize
            if (partFile.exists() && partFile.length() != alignedBytes) {
                FileOutputStream(partFile, true).use { fos ->
                    fos.channel.truncate(alignedBytes)
                }
            }

            var downloadedBytes = alignedBytes
            FileOutputStream(partFile, true).use { fos ->
                while (nextChunk < totalChunks && isActive) {
                    if (cancelledTransfers.contains(transferId)) {
                        partFile.delete()
                        onFinished(CloudTransferOutcome.CANCELLED, null, "", false)
                        return@withContext
                    }
                    if (pausedTransfers.contains(transferId)) {
                        onFinished(CloudTransferOutcome.PAUSED, partFile, "", false)
                        return@withContext
                    }

                    val latestSnap = transferRef.get().awaitResult()
                    val latestMeta = latestSnap.toObject(CloudTransferMeta::class.java)
                    if (latestMeta?.transferState == "CANCELLED") {
                        partFile.delete()
                        onFinished(CloudTransferOutcome.CANCELLED, null, "", false)
                        return@withContext
                    }
                    val uploadedCount = (latestMeta?.uploadedChunkCount ?: 0L).toInt()
                    if (nextChunk >= uploadedCount && latestMeta?.transferState != "READY") {
                        if (latestMeta?.transferState == "PAUSED" || latestMeta?.transferState == "FAILED") {
                            onFinished(CloudTransferOutcome.PAUSED, partFile, "", false)
                            return@withContext
                        }
                        delay(600L)
                        continue
                    }

                    val tStart = System.currentTimeMillis()
                    val chunkSnap = transferRef.collection("chunks").document(nextChunk.toString()).get().awaitResult()
                    val b64 = chunkSnap.getString("dataBase64")
                    if (b64.isNullOrEmpty()) {
                        delay(500L)
                        continue
                    }
                    val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                    fos.write(rawBytes)
                    fos.flush()

                    downloadedBytes = (downloadedBytes + rawBytes.size).coerceAtMost(totalBytes)
                    nextChunk++
                    val elapsedMs = (System.currentTimeMillis() - tStart).coerceAtLeast(1L)
                    val speed = (rawBytes.size * 1000L) / elapsedMs
                    onProgress(downloadedBytes, totalBytes, nextChunk, totalChunks, speed, partFile.absolutePath)
                }
            }

            val finalMeta = transferRef.get().awaitResult().toObject(CloudTransferMeta::class.java)
            val actualSha = computeFileSha256(partFile)
            val expectedSha = finalMeta?.sha256.orEmpty()
            val verified = expectedSha.isBlank() || expectedSha.equals(actualSha, ignoreCase = true)
            if (partFile.renameTo(finalFile).not()) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            onFinished(CloudTransferOutcome.COMPLETED, finalFile, actualSha, verified)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "conversations/$convId/transfers/$transferId")
            onFinished(CloudTransferOutcome.PAUSED, partFile, "", false)
        }
    }

    // --- Online WebRTC Signaling via Firestore ---

    suspend fun createCloudCall(
        callId: String,
        conversationId: String,
        calleeUid: String,
        callerName: String,
        videoCall: Boolean,
        sdpOffer: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext Result.failure(IllegalStateException("Sign in required"))
        val members = listOf(myUid, calleeUid).distinct().sorted()
        try {
            db.collection("calls").document(callId).set(
                mapOf(
                    "conversationId" to conversationId.take(200),
                    "memberUids" to members,
                    "callerUid" to myUid,
                    "callerName" to callerName.trim().take(60).ifEmpty { "Caller" },
                    "calleeUid" to calleeUid,
                    "videoCall" to videoCall,
                    "callState" to "RINGING",
                    "sdpOffer" to sdpOffer.take(65000),
                    "sdpAnswer" to "",
                    "startedAtMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(handleFirestoreError(e, OperationType.CREATE, "calls/$callId")))
        }
    }

    suspend fun answerCloudCall(callId: String, sdpAnswer: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            currentUid() ?: return@withContext Result.failure(IllegalStateException("Sign in required"))
            try {
                db.collection("calls").document(callId).update(
                    mapOf(
                        "callState" to "ACTIVE",
                        "sdpAnswer" to sdpAnswer.take(65000),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(Exception(handleFirestoreError(e, OperationType.UPDATE, "calls/$callId")))
            }
        }

    suspend fun updateCloudCallState(callId: String, callState: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            currentUid() ?: return@withContext
            runCatching {
                db.collection("calls").document(callId).update(
                    mapOf(
                        "callState" to callState,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }
        }

    suspend fun addIceCandidate(
        callId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ) = withContext(Dispatchers.IO) {
        val myUid = currentUid() ?: return@withContext
        runCatching {
            db.collection("calls").document(callId)
                .collection("candidates")
                .add(
                    mapOf(
                        "senderUid" to myUid,
                        "sdpMid" to sdpMid.take(64),
                        "sdpMLineIndex" to sdpMLineIndex.coerceAtLeast(0),
                        "candidate" to candidate.take(2000),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
        }
    }

    fun observeActiveCallSignaling(
        callId: String,
        myUid: String,
        onSessionChanged: (CloudCallSession) -> Unit,
        onRemoteIceCandidate: (CloudIceCandidate) -> Unit
    ) {
        activeCallReg?.remove()
        activeCandidatesReg?.remove()
        val seenCandIds = hashSetOf<String>()

        activeCallReg = db.collection("calls").document(callId)
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
                val model = snap.toObject(
                    CloudCallSession::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: return@addSnapshotListener
                onSessionChanged(model)
            }

        activeCandidatesReg = db.collection("calls").document(callId)
            .collection("candidates")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                snap.documents.forEach { doc ->
                    if (seenCandIds.add(doc.id)) {
                        val cand = doc.toObject(CloudIceCandidate::class.java) ?: return@forEach
                        if (cand.senderUid != myUid && cand.candidate.isNotBlank()) {
                            onRemoteIceCandidate(cand)
                        }
                    }
                }
            }
    }

    fun stopActiveCallSignaling() {
        activeCallReg?.remove()
        activeCallReg = null
        activeCandidatesReg?.remove()
        activeCandidatesReg = null
    }

    private fun readUpTo(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var read = input.read(buf)
            while (read > 0) {
                digest.update(buf, 0, read)
                read = input.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
