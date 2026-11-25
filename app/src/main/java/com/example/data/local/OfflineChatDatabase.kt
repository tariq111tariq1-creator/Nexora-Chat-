package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class OfflineChatConverters {
    @TypeConverter
    fun fromChannelMode(value: ChannelMode): String = value.name

    @TypeConverter
    fun toChannelMode(value: String): ChannelMode =
        runCatching { ChannelMode.valueOf(value) }.getOrDefault(ChannelMode.OFFLINE)

    @TypeConverter
    fun fromTransportKind(value: TransportKind): String = value.name

    @TypeConverter
    fun toTransportKind(value: String): TransportKind =
        runCatching { TransportKind.valueOf(value) }.getOrDefault(TransportKind.WIFI_DIRECT)

    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): MessageType =
        runCatching { MessageType.valueOf(value) }.getOrDefault(MessageType.TEXT)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.PENDING)

    @TypeConverter
    fun fromTransferDirection(value: TransferDirection): String = value.name

    @TypeConverter
    fun toTransferDirection(value: String): TransferDirection =
        runCatching { TransferDirection.valueOf(value) }.getOrDefault(TransferDirection.INCOMING)

    @TypeConverter
    fun fromTransferStatus(value: TransferStatus): String = value.name

    @TypeConverter
    fun toTransferStatus(value: String): TransferStatus =
        runCatching { TransferStatus.valueOf(value) }.getOrDefault(TransferStatus.QUEUED)
}

@Database(
    entities = [
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        FileTransferEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(OfflineChatConverters::class)
abstract class OfflineChatDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun fileTransferDao(): FileTransferDao

    companion object {
        @Volatile
        private var INSTANCE: OfflineChatDatabase? = null

        fun getInstance(context: Context): OfflineChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineChatDatabase::class.java,
                    "offline_chat_db"
                ).fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
