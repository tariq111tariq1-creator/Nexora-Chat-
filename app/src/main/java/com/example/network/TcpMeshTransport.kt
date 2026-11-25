package com.example.network

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class IncomingFileProgress(
    val transferId: String,
    val transferredBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val localFile: File? = null,
    val sha256: String = "",
    val completed: Boolean = false,
    val paused: Boolean = false,
    val failed: Boolean = false
)

sealed interface TransportEvent {
    data class PeerConnected(
        val peerId: String,
        val peerName: String,
        val ipAddress: String,
        val avatarColorIndex: Int,
        val transportKind: com.example.data.local.TransportKind
    ) : TransportEvent

    data class PeerDisconnected(val peerId: String) : TransportEvent
    data class PacketReceived(val peerId: String, val remoteIp: String, val packet: MeshPacket) : TransportEvent
    data class FileTransferProgress(val progress: IncomingFileProgress) : TransportEvent
}

class TcpMeshTransport(private val context: Context) {

    companion object {
        const val MESH_PORT = 8988
        private const val FRAME_JSON: Byte = 1
        private const val FRAME_FILE_CHUNK: Byte = 2
        private const val FRAME_CALL_AUDIO: Byte = 3
        private const val FRAME_CALL_VIDEO: Byte = 4
        private const val CHUNK_SIZE = 64 * 1024
        private const val SOCKET_READ_TIMEOUT_MS = 18_000
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var heartbeatJob: Job? = null

    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val connectingIps = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()
    private val pausedTransfers = ConcurrentHashMap.newKeySet<String>()
    private val activeIncomingFiles = ConcurrentHashMap<String, ActiveFileWriter>()
    private val pendingResumeQueries = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _connectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeerIds: StateFlow<Set<String>> = _connectedPeerIds.asStateFlow()

    var localIdentityProvider: (() -> Triple<String, String, Int>)? = null
    var roomPinProvider: (() -> String)? = null
    var onCallAudioReceived: ((peerId: String, pcmBytes: ByteArray) -> Unit)? = null
    var onCallVideoReceived: ((peerId: String, jpegBytes: ByteArray) -> Unit)? = null

    private class ActiveFileWriter(
        val fileId: String,
        val partFile: File,
        val finalFile: File,
        val output: FileOutputStream,
        val digest: MessageDigest,
        val totalBytes: Long,
        var receivedBytes: Long = 0L,
        var lastReportAt: Long = System.currentTimeMillis(),
        var bytesSinceLastReport: Long = 0L
    )

    private inner class PeerSession(
        val socket: Socket,
        val remoteIp: String,
        val transportKind: com.example.data.local.TransportKind
    ) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val writeLock = Any()

        @Volatile
        var peerId: String = ""

        @Volatile
        var peerName: String = remoteIp

        @Volatile
        var avatarColorIndex: Int = 0

        @Volatile
        var isClosed: Boolean = false

        fun sendJson(packet: MeshPacket): Boolean {
            if (isClosed || socket.isClosed) return false
            val bytes = packet.toJson().toByteArray(Charsets.UTF_8)
            return synchronized(writeLock) {
                runCatching {
                    output.writeByte(FRAME_JSON.toInt())
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                    true
                }.getOrElse {
                    Log.w("TcpMeshTransport", "sendJson failed to $remoteIp: ${it.message}")
                    false
                }
            }
        }

        fun sendFileChunk(fileId: String, chunk: ByteArray, length: Int): Boolean {
            if (isClosed || socket.isClosed) return false
            val idBytes = fileId.toByteArray(Charsets.UTF_8)
            return synchronized(writeLock) {
                runCatching {
                    output.writeByte(FRAME_FILE_CHUNK.toInt())
                    output.writeInt(2 + idBytes.size + length)
                    output.writeShort(idBytes.size)
                    output.write(idBytes)
                    output.write(chunk, 0, length)
                    output.flush()
                    true
                }.getOrElse { false }
            }
        }

        fun sendRawFrame(frameType: Byte, data: ByteArray, length: Int): Boolean {
            if (isClosed || socket.isClosed) return false
            return synchronized(writeLock) {
                runCatching {
                    output.writeByte(frameType.toInt())
                    output.writeInt(length)
                    output.write(data, 0, length)
                    output.flush()
                    true
                }.getOrElse { false }
            }
        }

        fun close() {
            isClosed = true
            runCatching { socket.close() }
        }
    }

    fun startServer() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            runCatching {
                val ss = ServerSocket(MESH_PORT).apply { reuseAddress = true }
                serverSocket = ss
                while (isActive && !ss.isClosed) {
                    val client = ss.accept()
                    configureSocket(client)
                    handleSocket(client, com.example.data.local.TransportKind.WIFI_DIRECT)
                }
            }.onFailure {
                if (it !is CancellationException) {
                    Log.w("TcpMeshTransport", "Server stopped: ${it.message}")
                }
            }
        }
        startHeartbeatLoop()
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val (localId, localName, avatarIdx) = localIdentityProvider?.invoke()
                    ?: Triple("OC-LOCAL", "Device", 0)
                val ping = MeshPacket(
                    kind = PacketKind.PING,
                    senderId = localId,
                    senderName = localName,
                    avatarColorIndex = avatarIdx
                )
                sessions.values.toList().forEach { session ->
                    if (!session.sendJson(ping)) {
                        session.close()
                    }
                }
            }
        }
    }

    private fun configureSocket(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        }
    }

    fun hasActiveSessionForIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        return sessions.values.any { it.remoteIp == ip && !it.isClosed && !it.socket.isClosed }
    }

    fun hasActiveSessionForPeer(peerId: String): Boolean {
        val s = sessions[peerId] ?: return false
        return !s.isClosed && !s.socket.isClosed
    }

    fun connectToHost(
        hostIp: String,
        port: Int = MESH_PORT,
        transportKind: com.example.data.local.TransportKind = com.example.data.local.TransportKind.LAN,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (hostIp.isBlank()) {
            onResult(false)
            return
        }
        if (hasActiveSessionForIp(hostIp)) {
            onResult(true)
            return
        }
        if (!connectingIps.add(hostIp)) {
            return
        }

        scope.launch {
            val ok = try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostIp, port), 4500)
                configureSocket(socket)
                handleSocket(socket, transportKind)
                true
            } catch (e: Exception) {
                Log.w("TcpMeshTransport", "Connect to $hostIp:$port failed: ${e.message}")
                false
            } finally {
                connectingIps.remove(hostIp)
            }
            onResult(ok)
        }
    }

    suspend fun connectToHostSuspending(
        hostIp: String,
        port: Int = MESH_PORT,
        transportKind: com.example.data.local.TransportKind = com.example.data.local.TransportKind.LAN
    ): Boolean = withContext(Dispatchers.IO) {
        if (hostIp.isBlank()) return@withContext false
        if (hasActiveSessionForIp(hostIp)) return@withContext true
        if (!connectingIps.add(hostIp)) {
            repeat(12) {
                delay(100L)
                if (hasActiveSessionForIp(hostIp)) return@withContext true
            }
            return@withContext hasActiveSessionForIp(hostIp)
        }
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(hostIp, port), 3500)
            configureSocket(socket)
            handleSocket(socket, transportKind)
            repeat(15) {
                if (hasActiveSessionForIp(hostIp)) return@withContext true
                delay(60L)
            }
            hasActiveSessionForIp(hostIp)
        } catch (e: Exception) {
            Log.w("TcpMeshTransport", "Fast connect to $hostIp:$port failed: ${e.message}")
            false
        } finally {
            connectingIps.remove(hostIp)
        }
    }

    private fun handleSocket(
        socket: Socket,
        transportKind: com.example.data.local.TransportKind
    ) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
        val session = PeerSession(socket, remoteIp, transportKind)

        scope.launch {
            val (localId, localName, avatarIdx) = localIdentityProvider?.invoke()
                ?: Triple("OC-LOCAL", "Device", 0)
            val pin = roomPinProvider?.invoke() ?: ""

            session.sendJson(
                MeshPacket(
                    kind = PacketKind.HANDSHAKE,
                    senderId = localId,
                    senderName = localName,
                    avatarColorIndex = avatarIdx,
                    roomPin = pin
                )
            )

            try {
                while (isActive && !socket.isClosed && !session.isClosed) {
                    val frameType = session.input.readByte()
                    val length = session.input.readInt()
                    if (length <= 0 || length > 8 * 1024 * 1024) break

                    when (frameType) {
                        FRAME_JSON -> {
                            val payload = ByteArray(length)
                            session.input.readFully(payload)
                            val packet = MeshPacket.fromJson(String(payload, Charsets.UTF_8)) ?: continue

                            if (packet.kind == PacketKind.HANDSHAKE || packet.kind == PacketKind.HANDSHAKE_ACK) {
                                session.peerId = packet.senderId
                                session.peerName = packet.senderName
                                session.avatarColorIndex = packet.avatarColorIndex

                                val previous = sessions.put(packet.senderId, session)
                                if (previous != null && previous !== session) {
                                    previous.close()
                                }
                                _connectedPeerIds.value = sessions.keys.toSet()

                                if (packet.kind == PacketKind.HANDSHAKE) {
                                    session.sendJson(
                                        MeshPacket(
                                            kind = PacketKind.HANDSHAKE_ACK,
                                            senderId = localId,
                                            senderName = localName,
                                            avatarColorIndex = avatarIdx,
                                            roomPin = pin
                                        )
                                    )
                                }
                                _events.emit(
                                    TransportEvent.PeerConnected(
                                        peerId = packet.senderId,
                                        peerName = packet.senderName,
                                        ipAddress = remoteIp,
                                        avatarColorIndex = packet.avatarColorIndex,
                                        transportKind = transportKind
                                    )
                                )
                            } else if (packet.kind == PacketKind.PING) {
                                // Heartbeat OK
                            } else if (packet.kind == PacketKind.FILE_RESUME_QUERY) {
                                val existingOffset = findExistingPartialOffset(packet.fileId, packet.fileName)
                                session.sendJson(
                                    MeshPacket(
                                        kind = PacketKind.FILE_RESUME_OFFSET,
                                        senderId = localId,
                                        senderName = localName,
                                        fileId = packet.fileId,
                                        byteOffset = existingOffset
                                    )
                                )
                            } else if (packet.kind == PacketKind.FILE_RESUME_OFFSET) {
                                pendingResumeQueries[packet.fileId]?.complete(packet.byteOffset)
                            } else {
                                if (packet.kind == PacketKind.FILE_OFFER) {
                                    prepareIncomingFile(packet)
                                } else if (packet.kind == PacketKind.FILE_COMPLETE) {
                                    finalizeIncomingFile(packet.fileId, packet.sha256)
                                } else if (packet.kind == PacketKind.FILE_CANCEL) {
                                    abortIncomingFile(packet.fileId, deletePartial = true)
                                }
                                _events.emit(
                                    TransportEvent.PacketReceived(
                                        peerId = session.peerId.ifEmpty { packet.senderId },
                                        remoteIp = remoteIp,
                                        packet = packet
                                    )
                                )
                            }
                        }

                        FRAME_FILE_CHUNK -> {
                            val idLen = session.input.readUnsignedShort()
                            val idBytes = ByteArray(idLen)
                            session.input.readFully(idBytes)
                            val fileId = String(idBytes, Charsets.UTF_8)
                            val chunkLen = length - 2 - idLen
                            if (chunkLen > 0) {
                                val chunk = ByteArray(chunkLen)
                                session.input.readFully(chunk)
                                writeIncomingFileChunk(fileId, chunk)
                            }
                        }

                        FRAME_CALL_AUDIO -> {
                            if (length <= 256 * 1024) {
                                val payload = ByteArray(length)
                                session.input.readFully(payload)
                                onCallAudioReceived?.invoke(session.peerId, payload)
                            } else {
                                break
                            }
                        }

                        FRAME_CALL_VIDEO -> {
                            if (length <= 1024 * 1024) {
                                val payload = ByteArray(length)
                                session.input.readFully(payload)
                                onCallVideoReceived?.invoke(session.peerId, payload)
                            } else {
                                break
                            }
                        }

                        else -> break
                    }
                }
            } catch (e: Exception) {
                Log.d("TcpMeshTransport", "Session ended ($remoteIp): ${e.message}")
            } finally {
                // Pause any in-flight incoming files without deleting .part_ so they resume seamlessly on reconnect
                activeIncomingFiles.values.toList().forEach { writer ->
                    pauseIncomingFile(writer.fileId)
                }
                if (session.peerId.isNotEmpty()) {
                    val removed = sessions.remove(session.peerId, session)
                    if (removed) {
                        _connectedPeerIds.value = sessions.keys.toSet()
                        _events.emit(TransportEvent.PeerDisconnected(session.peerId))
                    }
                }
                session.close()
            }
        }
    }

    private fun receivedFilesDir(): File = File(context.filesDir, "received_files").apply { mkdirs() }

    private fun sanitizeName(fileId: String, fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file_$fileId" }

    private fun findExistingPartialOffset(fileId: String, fileName: String): Long {
        val dir = receivedFilesDir()
        val safeName = sanitizeName(fileId, fileName)
        val finalFile = File(dir, "${fileId.take(6)}_$safeName")
        if (finalFile.exists() && finalFile.length() > 0L) {
            return finalFile.length()
        }
        val partFile = File(dir, ".part_${fileId}_$safeName")
        if (partFile.exists()) return partFile.length()
        val match = dir.listFiles()?.firstOrNull { it.name.startsWith(".part_${fileId}_") }
        return match?.length() ?: 0L
    }

    private fun prepareIncomingFile(packet: MeshPacket) {
        val dir = receivedFilesDir()
        val safeName = sanitizeName(packet.fileId, packet.fileName)
        val finalFile = File(dir, "${packet.fileId.take(6)}_$safeName")
        val partFile = File(dir, ".part_${packet.fileId}_$safeName")

        activeIncomingFiles.remove(packet.fileId)?.let { old ->
            runCatching { old.output.close() }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val resumeOffset = packet.byteOffset.coerceAtLeast(0L)
        val canAppend = resumeOffset > 0L && partFile.exists() && partFile.length() >= resumeOffset

        if (canAppend) {
            if (partFile.length() > resumeOffset) {
                FileOutputStream(partFile, true).use { it.channel.truncate(resumeOffset) }
            }
            // Hash existing prefix into SHA-256 so final checksum covers 0..EOF
            val buf = ByteArray(CHUNK_SIZE)
            var remaining = resumeOffset
            partFile.inputStream().use { input ->
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val r = input.read(buf, 0, toRead)
                    if (r <= 0) break
                    digest.update(buf, 0, r)
                    remaining -= r
                }
            }
            val fos = FileOutputStream(partFile, true)
            activeIncomingFiles[packet.fileId] = ActiveFileWriter(
                fileId = packet.fileId,
                partFile = partFile,
                finalFile = finalFile,
                output = fos,
                digest = digest,
                totalBytes = packet.fileSizeBytes,
                receivedBytes = resumeOffset
            )
        } else {
            val fos = FileOutputStream(partFile, false)
            activeIncomingFiles[packet.fileId] = ActiveFileWriter(
                fileId = packet.fileId,
                partFile = partFile,
                finalFile = finalFile,
                output = fos,
                digest = digest,
                totalBytes = packet.fileSizeBytes,
                receivedBytes = 0L
            )
        }
    }

    private fun writeIncomingFileChunk(fileId: String, chunk: ByteArray) {
        val writer = activeIncomingFiles[fileId] ?: return
        if (cancelledTransfers.contains(fileId) || pausedTransfers.contains(fileId)) return
        writer.output.write(chunk)
        writer.digest.update(chunk)
        writer.receivedBytes += chunk.size
        writer.bytesSinceLastReport += chunk.size

        val now = System.currentTimeMillis()
        val elapsedMs = (now - writer.lastReportAt).coerceAtLeast(1L)
        if (elapsedMs >= 250L || writer.receivedBytes >= writer.totalBytes) {
            val speed = (writer.bytesSinceLastReport * 1000L) / elapsedMs
            writer.lastReportAt = now
            writer.bytesSinceLastReport = 0L
            _events.tryEmit(
                TransportEvent.FileTransferProgress(
                    IncomingFileProgress(
                        transferId = fileId,
                        transferredBytes = writer.receivedBytes,
                        totalBytes = writer.totalBytes,
                        bytesPerSecond = speed,
                        localFile = writer.partFile
                    )
                )
            )
        }
    }

    private suspend fun finalizeIncomingFile(fileId: String, expectedSha256: String) {
        val writer = activeIncomingFiles.remove(fileId) ?: return
        runCatching {
            writer.output.flush()
            writer.output.close()
        }
        val actualSha = writer.digest.digest().joinToString("") { "%02x".format(it) }
        val verified = expectedSha256.isBlank() || expectedSha256.equals(actualSha, ignoreCase = true)
        if (verified) {
            if (!writer.partFile.renameTo(writer.finalFile)) {
                writer.partFile.copyTo(writer.finalFile, overwrite = true)
                writer.partFile.delete()
            }
        }
        _events.emit(
            TransportEvent.FileTransferProgress(
                IncomingFileProgress(
                    transferId = fileId,
                    transferredBytes = writer.receivedBytes,
                    totalBytes = writer.totalBytes,
                    bytesPerSecond = 0L,
                    localFile = if (verified) writer.finalFile else writer.partFile,
                    sha256 = actualSha,
                    completed = verified,
                    failed = !verified
                )
            )
        )
    }

    private suspend fun pauseIncomingFile(fileId: String) {
        val writer = activeIncomingFiles.remove(fileId) ?: return
        runCatching {
            writer.output.flush()
            writer.output.close()
        }
        _events.emit(
            TransportEvent.FileTransferProgress(
                IncomingFileProgress(
                    transferId = fileId,
                    transferredBytes = writer.receivedBytes,
                    totalBytes = writer.totalBytes,
                    bytesPerSecond = 0L,
                    localFile = writer.partFile,
                    paused = true
                )
            )
        )
    }

    private suspend fun abortIncomingFile(fileId: String, deletePartial: Boolean) {
        val writer = activeIncomingFiles.remove(fileId) ?: return
        runCatching { writer.output.close() }
        if (deletePartial) {
            runCatching { writer.partFile.delete() }
            runCatching { writer.finalFile.delete() }
        }
        _events.emit(
            TransportEvent.FileTransferProgress(
                IncomingFileProgress(
                    transferId = fileId,
                    transferredBytes = writer.receivedBytes,
                    totalBytes = writer.totalBytes,
                    bytesPerSecond = 0L,
                    failed = true
                )
            )
        )
    }

    fun sendCallMediaFrame(peerId: String, isVideo: Boolean, data: ByteArray, length: Int = data.size): Boolean {
        val session = sessions[peerId]
            ?: sessions.values.firstOrNull()?.takeIf { sessions.size == 1 }
            ?: return false
        return session.sendRawFrame(if (isVideo) FRAME_CALL_VIDEO else FRAME_CALL_AUDIO, data, length)
    }

    suspend fun sendPacketToPeer(peerId: String, packet: MeshPacket): Boolean = withContext(Dispatchers.IO) {
        val session = sessions[peerId]
            ?: sessions.values.firstOrNull()?.takeIf { sessions.size == 1 }
            ?: return@withContext false
        session.sendJson(packet)
    }

    suspend fun broadcastPacket(packet: MeshPacket, targetPeerIds: List<String>? = null): Int = withContext(Dispatchers.IO) {
        var sentCount = 0
        val targets = if (targetPeerIds.isNullOrEmpty()) {
            sessions.values.toList()
        } else {
            targetPeerIds.mapNotNull { sessions[it] }
                .ifEmpty { if (targetPeerIds.size == 1 && sessions.size == 1) sessions.values.toList() else emptyList() }
        }
        for (session in targets) {
            if (session.sendJson(packet)) sentCount++
        }
        sentCount
    }

    fun sendFileStream(
        targetPeerIds: List<String>,
        offerPacket: MeshPacket,
        uri: Uri,
        onProgress: (transferred: Long, total: Long, speedBps: Long) -> Unit,
        onComplete: (sha256: String, success: Boolean, paused: Boolean, bytesSent: Long) -> Unit
    ) {
        streamInput(
            targetPeerIds = targetPeerIds,
            offerPacket = offerPacket,
            inputProvider = { context.contentResolver.openInputStream(uri) },
            onProgress = onProgress,
            onComplete = onComplete
        )
    }

    fun sendLocalFileStream(
        targetPeerIds: List<String>,
        offerPacket: MeshPacket,
        file: File,
        onComplete: (Boolean) -> Unit
    ) {
        streamInput(
            targetPeerIds = targetPeerIds,
            offerPacket = offerPacket,
            inputProvider = { if (file.exists()) file.inputStream() else null },
            onProgress = { _, _, _ -> },
            onComplete = { _, ok, _, _ -> onComplete(ok) }
        )
    }

    private fun streamInput(
        targetPeerIds: List<String>,
        offerPacket: MeshPacket,
        inputProvider: () -> InputStream?,
        onProgress: (transferred: Long, total: Long, speedBps: Long) -> Unit,
        onComplete: (sha256: String, success: Boolean, paused: Boolean, bytesSent: Long) -> Unit
    ) {
        scope.launch {
            val fileId = offerPacket.fileId
            cancelledTransfers.remove(fileId)
            pausedTransfers.remove(fileId)

            val targets = targetPeerIds.mapNotNull { sessions[it] }.ifEmpty { sessions.values.toList() }
            if (targets.isEmpty()) {
                onComplete("", false, true, 0L)
                return@launch
            }

            // Ask single peer (or first target) for existing partial byte offset so we resume seamlessly
            val startOffset = if (targets.size == 1 && offerPacket.fileSizeBytes > 0L) {
                val deferred = CompletableDeferred<Long>()
                pendingResumeQueries[fileId] = deferred
                val (localId, localName, _) = localIdentityProvider?.invoke() ?: Triple("OC-LOCAL", "Device", 0)
                targets.first().sendJson(
                    MeshPacket(
                        kind = PacketKind.FILE_RESUME_QUERY,
                        senderId = localId,
                        senderName = localName,
                        fileId = fileId,
                        fileName = offerPacket.fileName
                    )
                )
                val confirmed = withTimeoutOrNull(750L) { deferred.await() } ?: 0L
                pendingResumeQueries.remove(fileId)
                confirmed.coerceIn(0L, offerPacket.fileSizeBytes)
            } else 0L

            if (offerPacket.fileSizeBytes > 0L && startOffset >= offerPacket.fileSizeBytes) {
                onProgress(offerPacket.fileSizeBytes, offerPacket.fileSizeBytes, 0L)
                onComplete("", true, false, offerPacket.fileSizeBytes)
                return@launch
            }

            targets.forEach { it.sendJson(offerPacket.copy(byteOffset = startOffset)) }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalSent = 0L
            val buffer = ByteArray(CHUNK_SIZE)
            var lastReportAt = System.currentTimeMillis()
            var bytesSinceReport = 0L
            var pausedByUser = false
            var cancelledByUser = false

            val success = runCatching {
                inputProvider()?.use { input ->
                    // Hash prefix 0 until startOffset locally without sending it over wire
                    while (totalSent < startOffset) {
                        val toRead = minOf(buffer.size.toLong(), startOffset - totalSent).toInt()
                        val r = input.read(buffer, 0, toRead)
                        if (r <= 0) break
                        digest.update(buffer, 0, r)
                        totalSent += r
                    }
                    if (totalSent > 0L) {
                        onProgress(totalSent, offerPacket.fileSizeBytes, 0L)
                    }

                    var read = input.read(buffer)
                    while (read > 0) {
                        if (cancelledTransfers.contains(fileId)) {
                            cancelledByUser = true
                            targets.forEach { it.sendJson(offerPacket.copy(kind = PacketKind.FILE_CANCEL)) }
                            return@use false
                        }
                        if (pausedTransfers.contains(fileId)) {
                            pausedByUser = true
                            return@use false
                        }

                        digest.update(buffer, 0, read)
                        val chunkDelivered = targets.any { it.sendFileChunk(fileId, buffer, read) }
                        if (!chunkDelivered) {
                            pausedByUser = true
                            return@use false
                        }

                        totalSent += read
                        bytesSinceReport += read

                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastReportAt).coerceAtLeast(1L)
                        if (elapsed >= 250L) {
                            val speed = (bytesSinceReport * 1000L) / elapsed
                            onProgress(totalSent, offerPacket.fileSizeBytes, speed)
                            lastReportAt = now
                            bytesSinceReport = 0L
                        }
                        read = input.read(buffer)
                    }
                    true
                } ?: false
            }.getOrDefault(false)

            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            if (success && !cancelledByUser && !pausedByUser) {
                targets.forEach {
                    it.sendJson(offerPacket.copy(kind = PacketKind.FILE_COMPLETE, sha256 = shaHex))
                }
                onProgress(offerPacket.fileSizeBytes, offerPacket.fileSizeBytes, 0L)
                onComplete(shaHex, true, false, totalSent)
            } else if (pausedByUser && !cancelledByUser) {
                onComplete("", false, true, totalSent)
            } else {
                onComplete(shaHex, false, false, totalSent)
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        pausedTransfers.add(transferId)
        scope.launch { pauseIncomingFile(transferId) }
    }

    fun cancelTransfer(transferId: String) {
        cancelledTransfers.add(transferId)
        pausedTransfers.remove(transferId)
        scope.launch { abortIncomingFile(transferId, deletePartial = true) }
    }

    fun getPeerIp(peerId: String): String? =
        sessions[peerId]?.remoteIp ?: sessions.values.firstOrNull()?.remoteIp

    fun disconnectAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        _connectedPeerIds.value = emptySet()
    }

    fun stopServer() {
        heartbeatJob?.cancel()
        disconnectAll()
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
