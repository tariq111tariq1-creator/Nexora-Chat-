package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ChannelMode {
    ONLINE,
    OFFLINE
}

enum class TransportKind {
    WIFI_DIRECT,
    HOTSPOT,
    LAN,
    INTERNET
}

enum class MessageType {
    TEXT,
    VOICE_NOTE,
    IMAGE,
    VIDEO,
    FILE,
    CALL_EVENT,
    SYSTEM
}

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class TransferStatus {
    QUEUED,
    TRANSFERRING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val ipAddress: String = "",
    val macAddress: String = "",
    val port: Int = 8988,
    val transportKind: TransportKind = TransportKind.WIFI_DIRECT,
    val avatarColorIndex: Int = 0,
    val isConnected: Boolean = false,
    val verificationPin: String = "",
    val lastSeenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val isGroup: Boolean = false,
    val peerId: String? = null,
    val participantIdsCsv: String = "",
    val lastMessagePreview: String = "",
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val avatarColorIndex: Int = 0,
    val channelMode: ChannelMode = ChannelMode.OFFLINE,
    val remoteUid: String? = null,
    val remoteShareCode: String? = null,
    val onlinePresence: Boolean = false
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "timestamp"])]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val type: MessageType,
    val textContent: String = "",
    val mediaLocalPath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val transferId: String? = null,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val reactionEmoji: String? = null,
    val status: MessageStatus = MessageStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "file_transfers",
    indices = [Index(value = ["conversationId", "updatedAt"])]
)
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val localPath: String? = null,
    val sourceUri: String? = null,
    val partialPath: String? = null,
    val sha256Checksum: String = "",
    val checksumVerified: Boolean = false,
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.QUEUED,
    val channelMode: ChannelMode = ChannelMode.OFFLINE,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val chunkSizeBytes: Int = 393216,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
