package org.reventor.sync.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * A device's long-lived P-256 identity. The key signs the handshake transcript
 * (proof of possession) and its public half is what pairing trusts.
 *
 * Persistence is the platform's job: both platforms store the X.509 public
 * encoding alongside the (wrapped or file-protected) PKCS#8 private encoding,
 * and restore via [fromEncodings] — no public-key reconstruction needed.
 */
class SyncIdentity(
    val deviceId: String,
    var deviceName: String,
    val keyPair: KeyPair,
) {
    /** X.509/SubjectPublicKeyInfo encoding, shared on the wire during hello. */
    fun publicKeyBytes(): ByteArray = keyPair.public.encoded

    /** PKCS#8 encoding for platform-side persistence. */
    fun privateKeyBytes(): ByteArray = keyPair.private.encoded

    fun sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(data)
        return sig.sign()
    }

    companion object {
        /** Fresh P-256 keypair; used both for identities and per-connection ECDH keys. */
        fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), random)
            return generator.generateKeyPair()
        }

        fun generate(deviceId: String, deviceName: String, random: SecureRandom = SecureRandom()): SyncIdentity =
            SyncIdentity(deviceId, deviceName, generateKeyPair(random))

        fun fromEncodings(deviceId: String, deviceName: String, pkcs8: ByteArray, x509: ByteArray): SyncIdentity {
            val factory = KeyFactory.getInstance("EC")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val publicKey: PublicKey = factory.generatePublic(X509EncodedKeySpec(x509))
            return SyncIdentity(deviceId, deviceName, KeyPair(publicKey, privateKey))
        }

        fun decodePublic(encoded: ByteArray): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

        fun verify(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(decodePublic(publicKeyBytes))
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
}
