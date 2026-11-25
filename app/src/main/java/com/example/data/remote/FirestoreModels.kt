package com.example.data.remote

import androidx.annotation.Keep
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isCanceled) {
            cont.cancel()
        } else if (task.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            cont.resume(task.result as T)
        } else {
            cont.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
        }
    }
}

@Keep
@IgnoreExtraProperties
data class CloudUserProfile(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shareCode: String = "",
    val displayName: String = "",
    val email: String = "",
    val avatarColorIndex: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudDirectoryEntry(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shareCode: String = "",
    val displayName: String = "",
    val avatarColorIndex: Int = 0,
    val onlineState: String = "ONLINE",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudConversation(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val participantColors: Map<String, Long> = emptyMap(),
    val title: String = "",
    val groupChat: Boolean = false,
    val createdBy: String = "",
    val lastMessagePreview: String = "",
    val lastSenderUid: String = "",
    val lastMessageAtMs: Long = 0L,
    val typingUids: List<String> = emptyList(),
    val readAtMsMap: Map<String, Long> = emptyMap(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudMessage(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val senderUid: String = "",
    val senderName: String = "",
    val msgType: String = "TEXT",
    val textBody: String = "",
    val replyToId: String = "",
    val replyToPreview: String = "",
    val reactionEmoji: String = "",
    val transferId: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val deliveredUids: List<String> = emptyList(),
    val readUids: List<String> = emptyList(),
    val clientTimestampMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudTransferMeta(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val senderUid: String = "",
    val senderName: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val totalBytes: Long = 0L,
    val chunkSizeBytes: Long = 393216L,
    val totalChunks: Long = 1L,
    val uploadedChunkCount: Long = 0L,
    val sha256: String = "",
    val transferState: String = "UPLOADING",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudCallSession(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val memberUids: List<String> = emptyList(),
    val callerUid: String = "",
    val callerName: String = "",
    val calleeUid: String = "",
    val videoCall: Boolean = false,
    val callState: String = "RINGING",
    val sdpOffer: String = "",
    val sdpAnswer: String = "",
    val startedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudIceCandidate(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Long = 0L,
    val candidate: String = ""
)

@Serializable
data class FriendQrPayload(
    val type: String = "OFFLINE_CHAT_FRIEND",
    val shareCode: String,
    val displayName: String
) {
    fun toQrString(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromQrOrCode(raw: String): String {
            val trimmed = raw.trim()
            val parsed = runCatching { json.decodeFromString<FriendQrPayload>(trimmed) }.getOrNull()
            return (parsed?.shareCode ?: trimmed).trim().uppercase()
        }
    }
}
