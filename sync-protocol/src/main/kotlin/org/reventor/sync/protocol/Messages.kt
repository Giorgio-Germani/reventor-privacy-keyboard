package org.reventor.sync.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Plaintext hello exchanged before encryption is established. */
@Serializable
data class HandshakeHello(
    val protocolVersion: Int = SyncProtocol.VERSION,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    /** Hex-encoded X.509 SubjectPublicKeyInfo of the sender's identity key. */
    val identityPublicKey: String,
    /** Hex-encoded X.509 SubjectPublicKeyInfo of a fresh ephemeral ECDH key. */
    val ephemeralPublicKey: String,
    /** Hex-encoded 16 random bytes, replay protection input. */
    val nonce: String,
)

/** Messages sent as AES-GCM-encrypted frames once the session is established. */
@Serializable
sealed class SyncMessage {
    /** ECDSA over the handshake transcript; proves possession of the identity key. */
    @Serializable
    @SerialName("proof")
    data class Proof(val signature: String) : SyncMessage()

    /** User on this side accepted the pairing (SAS verified). */
    @Serializable
    @SerialName("pairAccept")
    object PairAccept : SyncMessage()

    /** User on this side rejected the pairing; the sender closes right after. */
    @Serializable
    @SerialName("pairReject")
    object PairReject : SyncMessage()

    @Serializable
    @SerialName("clip")
    data class ClipText(
        val id: String,
        /** Sender's local wall-clock when the clip was captured; display info only. */
        val timestamp: Long,
        val originDeviceId: String,
        val text: String,
    ) : SyncMessage()

    @Serializable
    @SerialName("ping")
    object Ping : SyncMessage()
}

val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
