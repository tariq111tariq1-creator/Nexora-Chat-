package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY isConnected DESC, lastSeenAt DESC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isConnected = 1")
    fun observeConnectedPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeer(peerId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isConnected = :connected, ipAddress = :ip, lastSeenAt = :timestamp WHERE peerId = :peerId")
    suspend fun updateConnectionState(peerId: String, connected: Boolean, ip: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE peers SET isConnected = 0, lastSeenAt = :timestamp WHERE peerId = :peerId")
    suspend fun markDisconnected(peerId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE peers SET isConnected = 0")
    suspend fun markAllDisconnected()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :id LIMIT 1")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE conversationId = :id LIMIT 1")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :id")
    suspend fun clearUnread(id: String)

    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageTimestamp = :timestamp, unreadCount = unreadCount + :unreadDelta WHERE conversationId = :id")
    suspend fun updateLastMessage(id: String, preview: String, timestamp: Long, unreadDelta: Int)

    @Query("UPDATE conversations SET isPinned = NOT isPinned WHERE conversationId = :id")
    suspend fun togglePin(id: String)

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations WHERE channelMode = 'ONLINE'")
    suspend fun deleteAllOnlineConversations()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND textContent LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    fun searchMessages(conversationId: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE transferId = :transferId LIMIT 1")
    suspend fun getMessageByTransferId(transferId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isOutgoing = 1 AND (status = 'PENDING' OR status = 'FAILED') ORDER BY timestamp ASC")
    suspend fun getUnsentOutgoingMessages(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE messageId = :messageId AND status IN ('PENDING', 'SENT')")
    suspend fun markMessageDelivered(messageId: String)

    @Query("UPDATE messages SET status = 'READ' WHERE conversationId = :conversationId AND isOutgoing = 1 AND status != 'FAILED' AND timestamp <= :upToTimestamp")
    suspend fun markConversationOutgoingRead(conversationId: String, upToTimestamp: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0)")
    suspend fun hasIncomingMessages(conversationId: String): Boolean

    @Query("SELECT MAX(timestamp) FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0")
    suspend fun getLatestIncomingTimestamp(conversationId: String): Long?

    @Query("UPDATE messages SET status = :newStatus WHERE conversationId = :conversationId AND isOutgoing = 1 AND status != :newStatus")
    suspend fun markAllOutgoingAs(conversationId: String, newStatus: MessageStatus)

    @Query("UPDATE messages SET reactionEmoji = :emoji WHERE messageId = :messageId")
    suspend fun updateReaction(messageId: String, emoji: String?)

    @Query("UPDATE messages SET mediaLocalPath = :path WHERE transferId = :transferId")
    suspend fun attachMediaPathByTransfer(transferId: String, path: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC")
    fun observeAllTransfers(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransfer(transferId: String): FileTransferEntity?

    @Query("SELECT * FROM file_transfers WHERE channelMode = :mode AND status IN ('PAUSED', 'FAILED', 'QUEUED') ORDER BY updatedAt ASC")
    suspend fun getResumableTransfers(mode: ChannelMode): List<FileTransferEntity>

    @Query("SELECT * FROM file_transfers WHERE conversationId = :conversationId AND direction = 'OUTGOING' AND status IN ('PAUSED', 'FAILED', 'QUEUED') ORDER BY updatedAt ASC")
    suspend fun getResumableOutgoingForConversation(conversationId: String): List<FileTransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: FileTransferEntity)

    @Query("UPDATE file_transfers SET transferredBytes = :transferred, bytesPerSecond = :speed, status = :status, completedChunks = :completedChunks, partialPath = COALESCE(:partialPath, partialPath), updatedAt = :updatedAt WHERE transferId = :transferId")
    suspend fun updateProgress(
        transferId: String,
        transferred: Long,
        speed: Long,
        status: TransferStatus,
        completedChunks: Int = 0,
        partialPath: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE file_transfers SET localPath = :localPath, sha256Checksum = :checksum, checksumVerified = :verified, transferredBytes = totalBytes, completedChunks = totalChunks, status = :status, updatedAt = :updatedAt WHERE transferId = :transferId")
    suspend fun completeTransfer(
        transferId: String,
        localPath: String,
        checksum: String,
        verified: Boolean,
        status: TransferStatus = TransferStatus.COMPLETED,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE file_transfers SET status = :status, updatedAt = :updatedAt WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: String, status: TransferStatus, updatedAt: Long = System.currentTimeMillis())
}
