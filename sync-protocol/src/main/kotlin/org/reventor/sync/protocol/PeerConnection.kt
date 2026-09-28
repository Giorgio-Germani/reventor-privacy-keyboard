package org.reventor.sync.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.KeyPair
import java.security.SecureRandom
import javax.crypto.KeyAgreement

/**
 * One TCP connection to one peer: hello exchange, key derivation, identity
 * proof, optional SAS pairing, then the encrypted message loop.
 */
class PeerConnection(
    val socket: Socket,
    val role: Role,
    private val identity: SyncIdentity,
    private val trust: TrustStore,
    private val engine: SyncPeer,
    private val scope: CoroutineScope,
) {
    enum class Role { INITIATOR, RESPONDER }
    enum class State { HANDSHAKE, PENDING_PAIRING, LIVE, CLOSED }

    val state = MutableStateFlow(State.HANDSHAKE)
    var sas: String = ""
        private set
    var peerHello: HandshakeHello? = null
        private set
    val peerDeviceId: String? get() = peerHello?.deviceId
    val isLive: Boolean get() = state.value == State.LIVE

    private val input by lazy { DataInputStream(BufferedInputStream(socket.getInputStream())) }
    private val output by lazy { DataOutputStream(BufferedOutputStream(socket.getOutputStream())) }
    private var session: SessionCryptor? = null
    private var sendSeq = 0L
    private val sendMutex = Mutex()

    private var localAccepted = false
    private var remoteAccepted = false
    private var closing = false
    private var watchdog: Job? = null

    private inline fun <reified T> encodeJson(value: T): ByteArray =
        SyncJson.encodeToString(value).toByteArray(Charsets.UTF_8)

    private inline fun <reified T> decodeJson(bytes: ByteArray): T =
        SyncJson.decodeFromString(String(bytes, Charsets.UTF_8))

    /** Runs the connection to completion; blocks the calling coroutine. */
    suspend fun run() {
        try {
            socket.tcpNoDelay = true
            handshake()
            readLoop()
        } catch (e: Exception) {
            close("connection error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun handshake() {
        val ephemeral: KeyPair = SyncIdentity.generateKeyPair()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        println("SyncPeer: handshake as $role")
        val myHello = HandshakeHello(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            platform = engine.platformName,
            identityPublicKey = Hex.encode(identity.publicKeyBytes()),
            ephemeralPublicKey = Hex.encode(ephemeral.public.encoded),
            nonce = Hex.encode(nonce),
        )
        val myHelloBytes = encodeJson(myHello)

        val initiatorBytes: ByteArray
        val responderBytes: ByteArray
        if (role == Role.INITIATOR) {
            FrameStreams.writeFrame(output, myHelloBytes)
            initiatorBytes = myHelloBytes
            responderBytes = FrameStreams.readFrame(input)
        } else {
            responderBytes = myHelloBytes
            initiatorBytes = FrameStreams.readFrame(input)
            FrameStreams.writeFrame(output, myHelloBytes)
        }

        val peerBytes = decodeJson<HandshakeHello>(
            if (role == Role.INITIATOR) responderBytes else initiatorBytes
        )
        if (peerBytes.protocolVersion != SyncProtocol.VERSION) {
            close("protocol version mismatch (peer ${peerBytes.protocolVersion}, us ${SyncProtocol.VERSION})")
            return
        }
        peerHello = peerBytes
        if (!engine.register(this)) {
            close("duplicate connection to ${peerBytes.deviceName}")
            return
        }

        val transcript = initiatorBytes + responderBytes
        val transcriptHash = MessageDigest.getInstance("SHA-256").digest(transcript)

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(SyncIdentity.decodePublic(Hex.decode(peerBytes.ephemeralPublicKey)), true)
        val sharedSecret = agreement.generateSecret()
        session = SessionCryptor.forRoles(role == Role.INITIATOR, sharedSecret, transcriptHash)

        val sasValue = Hkdf.derive(
            sharedSecret,
            transcriptHash,
            "reventor-sync-sas".toByteArray(),
            8,
        )
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (sasValue[i].toLong() and 0xFF)
        sas = "%06d".format(v % 1_000_000)

        // Proof of possession: ECDSA over the transcript, verified with the
        // identity key advertised in the peer's hello.
        sendEncrypted(SyncMessage.Proof(Hex.encode(identity.sign(transcriptHash))))
        val proof = receiveEncrypted() as? SyncMessage.Proof
        if (proof == null || !SyncIdentity.verify(
                Hex.decode(peerBytes.identityPublicKey),
                transcriptHash,
                Hex.decode(proof.signature),
            )
        ) {
            close("identity proof verification failed")
            return
        }

        val trusted = trust.find(peerBytes.deviceId)
        if (trusted != null) {
            if (trusted.identityPublicKey != peerBytes.identityPublicKey) {
                close("paired device re-appeared with a different identity key; unpair and pair again")
                return
            }
            goLive()
        } else {
            state.value = State.PENDING_PAIRING
            startPairingWatchdog()
            engine.callbacks.onPairingRequested(this)
        }
    }

    private fun startPairingWatchdog() {
        watchdog = scope.launch {
            delay(SyncProtocol.PAIRING_TIMEOUT_SECONDS * 1000L)
            if (state.value != State.LIVE && state.value != State.CLOSED) {
                close("pairing timed out")
            }
        }
    }

    /** User on this device confirmed the SAS code matches the peer's. */
    fun acceptPairing() {
        scope.launch(Dispatchers.IO) {
            localAccepted = true
            try {
                sendEncrypted(SyncMessage.PairAccept)
            } catch (e: Exception) {
                close("failed to send pairing accept: ${e.message}")
                return@launch
            }
            maybeCompletePairing()
        }
    }

    /** User on this device rejected the pairing. */
    fun rejectPairing() {
        scope.launch(Dispatchers.IO) {
            try {
                sendEncrypted(SyncMessage.PairReject)
            } catch (_: Exception) {
            }
            close("pairing rejected locally")
        }
    }

    private suspend fun maybeCompletePairing() {
        val peer = peerHello ?: return
        if (!(localAccepted && remoteAccepted)) return

        // Only the initiator knows a dialable address/port for the peer (the
        // responder's socket carries the client's ephemeral source port), so
        // reconnect hints are recorded accordingly.
        val address: String?
        val port: Int?
        if (role == Role.INITIATOR) {
            address = socket.inetAddress?.hostAddress
            port = socket.port
        } else {
            address = null
            port = engine.actualListenPort.value.takeIf { it != 0 }
        }
        trust.upsert(
            TrustedDevice(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                platform = peer.platform,
                identityPublicKey = peer.identityPublicKey,
                pairedAt = System.currentTimeMillis(),
                lastAddress = address,
                lastPort = port,
                lastSeen = System.currentTimeMillis(),
            )
        )
        goLive()
        engine.callbacks.onPairingCompleted(trust.find(peer.deviceId) ?: return)
    }

    private fun goLive() {
        watchdog?.cancel()
        try {
            // Once live, a silent peer is a dead peer; detect within 90 s.
            socket.soTimeout = 90_000
        } catch (_: Exception) {
        }
        state.value = State.LIVE
        engine.onConnectionLive(this)
    }

    private suspend fun readLoop() {
        while (true) {
            val body = try {
                FrameStreams.readFrame(input)
            } catch (e: SocketTimeoutException) {
                close("keepalive timeout")
                return
            }
            val (seq, ciphertext) = FrameStreams.splitBody(body)
            val plain = session?.decrypt(seq, ciphertext) ?: run {
                close("no session")
                return
            }
            when (val message = decodeJson<SyncMessage>(plain)) {
                is SyncMessage.Ping -> {}
                is SyncMessage.Proof -> {
                    close("unexpected duplicate proof")
                    return
                }
                is SyncMessage.PairAccept -> {
                    remoteAccepted = true
                    maybeCompletePairing()
                }
                is SyncMessage.PairReject -> {
                    close("pairing rejected by peer")
                    return
                }
                is SyncMessage.ClipText -> {
                    if (state.value == State.LIVE) engine.onClipReceived(this, message)
                }
            }
        }
    }

    suspend fun sendClip(id: String, timestamp: Long, originDeviceId: String, text: String) {
        sendEncrypted(SyncMessage.ClipText(id, timestamp, originDeviceId, text))
    }

    suspend fun sendPing() {
        sendEncrypted(SyncMessage.Ping)
    }

    private suspend fun sendEncrypted(message: SyncMessage) {
        val session = session ?: throw IllegalStateException("not encrypted yet")
        sendMutex.withLock {
            val seq = ++sendSeq
            val payload = encodeJson(message)
            val body = FrameStreams.joinBody(seq, session.encrypt(seq, payload))
            withContext(Dispatchers.IO) {
                FrameStreams.writeFrame(output, body)
            }
        }
    }

    private suspend fun receiveEncrypted(): SyncMessage? {
        val body = FrameStreams.readFrame(input)
        val (seq, ciphertext) = FrameStreams.splitBody(body)
        val plain = session?.decrypt(seq, ciphertext) ?: return null
        return decodeJson<SyncMessage>(plain)
    }

    fun close(reason: String) {
        synchronized(this) {
            if (closing) return
            closing = true
        }
        println("SyncPeer: connection to ${peerHello?.deviceName ?: "peer"} closed: $reason")
        state.value = State.CLOSED
        try {
            socket.close()
        } catch (_: Exception) {
        }
        engine.onConnectionClosed(this, reason)
    }
}
