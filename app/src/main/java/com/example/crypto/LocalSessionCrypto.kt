package com.example.crypto

import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object LocalSessionCrypto {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val DEFAULT_MESH_SALT = "offline-chat-mesh-session-v1"

    fun deriveKey(roomPin: String = ""): SecretKey {
        val seed = if (roomPin.isBlank()) DEFAULT_MESH_SALT else "$DEFAULT_MESH_SALT:$roomPin"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encryptText(plainText: String, roomPin: String = ""): String {
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(roomPin), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = nonce + cipherBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptText(encodedPayload: String, roomPin: String = ""): String? {
        return runCatching {
            val combined = Base64.decode(encodedPayload, Base64.NO_WRAP)
            if (combined.size <= NONCE_BYTES) return null
            val nonce = combined.copyOfRange(0, NONCE_BYTES)
            val cipherBytes = combined.copyOfRange(NONCE_BYTES, combined.size)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(roomPin), GCMParameterSpec(GCM_TAG_BITS, nonce))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun generatePairingPin(peerIdA: String, peerIdB: String): String {
        val sorted = listOf(peerIdA, peerIdB).sorted().joinToString(":")
        val hash = MessageDigest.getInstance("SHA-256").digest(sorted.toByteArray(Charsets.UTF_8))
        val num = ((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)
        return "%04d".format(num % 10000)
    }
}
