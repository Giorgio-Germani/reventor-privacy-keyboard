package org.reventor.sync.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test
    fun identity_signAndVerify() {
        val identity = SyncIdentity.generate("device-1", "Test Device")
        val data = "transcript".toByteArray()
        val signature = identity.sign(data)
        assertTrue(SyncIdentity.verify(identity.publicKeyBytes(), data, signature))
        assertFalse(SyncIdentity.verify(identity.publicKeyBytes(), "tampered".toByteArray(), signature))
    }

    @Test
    fun identity_persistsThroughEncodings() {
        val identity = SyncIdentity.generate("device-1", "Test Device")
        val restored = SyncIdentity.fromEncodings(
            "device-1", "Test Device",
            identity.privateKeyBytes(), identity.publicKeyBytes(),
        )
        val data = "transcript".toByteArray()
        assertTrue(SyncIdentity.verify(restored.publicKeyBytes(), data, restored.sign(data)))
        // Same underlying key, so the original public half verifies too.
        assertTrue(SyncIdentity.verify(identity.publicKeyBytes(), data, restored.sign(data)))
    }

    @Test
    fun session_encryptDecryptRoundTrip() {
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        val sender = SessionCryptor(sendKey = k1, recvKey = k2)
        val receiver = SessionCryptor(sendKey = k2, recvKey = k1)

        val plaintext = "a clip of text \u00e9\u4e2d\u6587".toByteArray()
        val ciphertext = sender.encrypt(0, plaintext)
        assertFalse(ciphertext.contentEquals(plaintext))
        assertTrue(receiver.decrypt(0, ciphertext).contentEquals(plaintext))
    }

    @Test(expected = SecurityException::class)
    fun session_rejectsReplayedSequence() {
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        val sender = SessionCryptor(sendKey = k1, recvKey = k2)
        val receiver = SessionCryptor(sendKey = k2, recvKey = k1)

        val frame = sender.encrypt(7, "x".toByteArray())
        receiver.decrypt(7, frame)
        receiver.decrypt(7, frame)
    }

    @Test(expected = Exception::class)
    fun session_rejectsTamperedCiphertext() {
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        val sender = SessionCryptor(sendKey = k1, recvKey = k2)
        val receiver = SessionCryptor(sendKey = k2, recvKey = k1)

        val frame = sender.encrypt(0, "x".toByteArray())
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
        receiver.decrypt(0, frame)
    }

    @Test
    fun roleKeys_differByDirection() {
        val secret = ByteArray(32) { 3 }
        val hash = ByteArray(32) { 4 }
        val initiator = SessionCryptor.forRoles(initiator = true, sharedSecret = secret, transcriptHash = hash)
        val responder = SessionCryptor.forRoles(initiator = false, sharedSecret = secret, transcriptHash = hash)

        val frame = initiator.encrypt(0, "ping".toByteArray())
        assertTrue(responder.decrypt(0, frame).contentEquals("ping".toByteArray()))
        val reply = responder.encrypt(0, "pong".toByteArray())
        assertTrue(initiator.decrypt(0, reply).contentEquals("pong".toByteArray()))
    }
}
