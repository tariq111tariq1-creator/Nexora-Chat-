package com.example.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class PacketKind {
    HANDSHAKE,
    HANDSHAKE_ACK,
    TEXT_MESSAGE,
    VOICE_NOTE_META,
    FILE_OFFER,
    FILE_RESUME_QUERY,
    FILE_RESUME_OFFSET,
    FILE_COMPLETE,
    FILE_CANCEL,
    MESSAGE_ACK,
    MESSAGE_READ,
    TYPING_STATE,
    REACTION,
    CALL_SIGNAL,
    GROUP_INVITE,
    PING
}

@Serializable
enum class CallSignalKind {
    OFFER_AUDIO,
    OFFER_VIDEO,
    ACCEPT,
    REJECT,
    BUSY,
    END,
    MUTE_TOGGLE,
    CAMERA_TOGGLE
}

@Serializable
data class MeshPacket(
    val kind: PacketKind,
    val senderId: String,
    val senderName: String,
    val avatarColorIndex: Int = 0,
    val conversationId: String = "",
    val isGroup: Boolean = false,
    val groupTitle: String = "",
    val messageId: String = "",
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val textBody: String = "",
    val encrypted: Boolean = false,
    val emoji: String = "",
    val fileId: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val fileSizeBytes: Long = 0L,
    val byteOffset: Long = 0L,
    val durationMs: Long = 0L,
    val sha256: String = "",
    val callId: String = "",
    val callSignal: CallSignalKind? = null,
    val callFlag: Boolean = false,
    val isTyping: Boolean = false,
    val roomPin: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = meshJson.encodeToString(this)

    companion object {
        val meshJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(raw: String): MeshPacket? =
            runCatching { meshJson.decodeFromString<MeshPacket>(raw) }.getOrNull()
    }
}

@Serializable
data class HotspotJoinPayload(
    val ssid: String,
    val passphrase: String,
    val hostIp: String,
    val port: Int = 8988,
    val hostId: String,
    val hostName: String,
    val roomPin: String = ""
) {
    fun toQrString(): String = MeshPacket.meshJson.encodeToString(this)

    companion object {
        fun fromQrString(raw: String): HotspotJoinPayload? =
            runCatching { MeshPacket.meshJson.decodeFromString<HotspotJoinPayload>(raw) }.getOrNull()
    }
}
