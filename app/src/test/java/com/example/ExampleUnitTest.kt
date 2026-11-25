package com.example

import com.example.data.local.SUPPORTED_LANGUAGES
import com.example.network.HotspotJoinPayload
import com.example.network.MeshPacket
import com.example.network.PacketKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `ten languages are configured with rtl support for arabic`() {
        assertEquals(10, SUPPORTED_LANGUAGES.size)
        val arabic = SUPPORTED_LANGUAGES.first { it.tag == "ar" }
        assertTrue(arabic.isRtl)
    }

    @Test
    fun `mesh packet and hotspot qr payload serialize cleanly`() {
        val packet = MeshPacket(
            kind = PacketKind.TEXT_MESSAGE,
            senderId = "OC-1234",
            senderName = "Tariq",
            conversationId = "conv-1",
            messageId = "msg-1",
            textBody = "Hello offline!"
        )
        val decoded = MeshPacket.fromJson(packet.toJson())
        assertEquals(packet.textBody, decoded?.textBody)

        val qrPayload = HotspotJoinPayload(
            ssid = "AndroidShare_1234",
            passphrase = "secret",
            hostIp = "192.168.43.1",
            hostId = "OC-1234",
            hostName = "Tariq"
        )
        val decodedQr = HotspotJoinPayload.fromQrString(qrPayload.toQrString())
        assertEquals("192.168.43.1", decodedQr?.hostIp)
    }

    @Test
    fun `read receipt and call signal packets serialize cleanly`() {
        val readPacket = MeshPacket(
            kind = PacketKind.MESSAGE_READ,
            senderId = "OC-9999",
            senderName = "Nadia",
            conversationId = "conv-99",
            timestamp = 1700000000000L
        )
        val decodedRead = MeshPacket.fromJson(readPacket.toJson())
        assertEquals(PacketKind.MESSAGE_READ, decodedRead?.kind)
        assertEquals("conv-99", decodedRead?.conversationId)

        val callPacket = MeshPacket(
            kind = PacketKind.CALL_SIGNAL,
            senderId = "OC-9999",
            senderName = "Nadia",
            callId = "call-1",
            callSignal = com.example.network.CallSignalKind.OFFER_VIDEO
        )
        val decodedCall = MeshPacket.fromJson(callPacket.toJson())
        assertEquals(com.example.network.CallSignalKind.OFFER_VIDEO, decodedCall?.callSignal)
    }

    @Test
    fun `resumable file packets and friend qr payloads serialize cleanly`() {
        val query = MeshPacket(
            kind = PacketKind.FILE_RESUME_QUERY,
            senderId = "OC-1111",
            senderName = "Omar",
            fileId = "file-abc",
            fileName = "archive.zip",
            fileSizeBytes = 10_485_760L,
            sha256 = "deadbeef"
        )
        val decodedQuery = MeshPacket.fromJson(query.toJson())
        assertEquals(PacketKind.FILE_RESUME_QUERY, decodedQuery?.kind)
        assertEquals("file-abc", decodedQuery?.fileId)

        val offset = MeshPacket(
            kind = PacketKind.FILE_RESUME_OFFSET,
            senderId = "OC-2222",
            senderName = "Lina",
            fileId = "file-abc",
            byteOffset = 4_194_304L
        )
        val decodedOffset = MeshPacket.fromJson(offset.toJson())
        assertEquals(PacketKind.FILE_RESUME_OFFSET, decodedOffset?.kind)
        assertEquals(4_194_304L, decodedOffset?.byteOffset)

        val friendQr = com.example.data.remote.FriendQrPayload(
            shareCode = "OC-A1B2C3D4",
            displayName = "Omar"
        )
        assertEquals(
            "OC-A1B2C3D4",
            com.example.data.remote.FriendQrPayload.fromQrOrCode(friendQr.toQrString())
        )
        assertEquals(
            "OC-A1B2C3D4",
            com.example.data.remote.FriendQrPayload.fromQrOrCode("  oc-a1b2c3d4 ")
        )
        assertEquals(com.example.data.local.ChannelMode.ONLINE, com.example.data.local.ChannelMode.valueOf("ONLINE"))
        assertEquals(com.example.data.local.TransferStatus.PAUSED, com.example.data.local.TransferStatus.valueOf("PAUSED"))
    }
}
