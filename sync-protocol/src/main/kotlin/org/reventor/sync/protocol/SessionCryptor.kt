package org.reventor.sync.protocol

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM session codec for one direction of one connection.
 *
 * Frame layout on the wire (both plaintext handshake and encrypted phase):
 * `[u32 bodyLen][body]` where body = `[u64 seq][payload]`.
 * Encrypted payload = AES-GCM ciphertext + 16-byte tag, nonce = 4-byte
 * connection prefix ++ 8-byte big-endian seq, AAD = the 8-byte seq.
 */
class SessionCryptor(
    sendKey: ByteArray,
    recvKey: ByteArray,
) {
    init {
        require(sendKey.size == 32 && recvKey.size == 32) { "Session keys must be 32 bytes" }
    }

    private val sendKeyBytes = sendKey.copyOf()
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val recvKey = SecretKeySpec(recvKey, "AES")

    // Each direction's nonce prefix is derived from that direction's send key,
    // so the receiver derives the same prefix from its recv key. Keys are
    // per-connection random (fresh ephemeral ECDH), so nonces never repeat
    // under one key.
    private val noncePrefixSend = noncePrefixFor(sendKeyBytes)
    private val noncePrefixRecv = noncePrefixFor(recvKey)
    private var lastRecvSeq: Long = -1

    fun encrypt(seq: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(noncePrefixSend, seq)))
        cipher.updateAAD(seqBytes(seq))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(seq: Long, ciphertext: ByteArray): ByteArray {
        if (seq <= lastRecvSeq) throw SecurityException("Frame sequence replayed or out of order: $seq")
        lastRecvSeq = seq
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(noncePrefixRecv, seq)))
        cipher.updateAAD(seqBytes(seq))
        return cipher.doFinal(ciphertext)
    }

    private fun nonce(prefix: ByteArray, seq: Long): ByteArray {
        val n = ByteArray(12)
        prefix.copyInto(n, 0)
        for (i in 0 until 8) n[4 + i] = (seq ushr ((7 - i) * 8)).toByte()
        return n
    }

    companion object {
        private fun noncePrefixFor(key: ByteArray): ByteArray =
            Hkdf.derive(key, ByteArray(32), "reventor-sync-nonce".toByteArray(), 4)

        fun seqBytes(seq: Long): ByteArray = ByteArray(8) { i -> (seq ushr ((7 - i) * 8)).toByte() }

        /**
         * Derives the direction-separated session keys from the ECDH shared
         * secret and the handshake transcript hash. The initiator sends with
         * the first key, the responder with the second.
         */
        fun forRoles(
            initiator: Boolean,
            sharedSecret: ByteArray,
            transcriptHash: ByteArray,
        ): SessionCryptor {
            val okm = Hkdf.derive(
                ikm = sharedSecret,
                salt = transcriptHash,
                info = "reventor-sync-v1 session".toByteArray(),
                outLength = 64,
            )
            val a = okm.copyOfRange(0, 32)
            val b = okm.copyOfRange(32, 64)
            return if (initiator) SessionCryptor(a, b) else SessionCryptor(b, a)
        }
    }
}
