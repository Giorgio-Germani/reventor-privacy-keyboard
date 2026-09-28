package org.reventor.sync.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer-to-peer clipboard sync engine, shared by the Android IME and the
 * desktop tray app.
 *
 * The platform supplies clipboard access ([Callbacks]), a persisted
 * [TrustStore], and (optionally) a listen port. Discovery is also a platform
 * concern — the engine connects to whatever address it is given plus the
 * last-seen addresses of paired devices.
 */
class SyncPeer(
    val scope: CoroutineScope,
    private val identityProvider: suspend () -> SyncIdentity?,
    val trust: TrustStore,
    val platformName: String,
    val callbacks: Callbacks,
    /** Whether to accept inbound connections (desktops yes, phone no). */
    private val listenEnabled: Boolean = false,
    /** Port to listen on; 0 binds an ephemeral port (tests). */
    private val listenPort: Int = SyncProtocol.DEFAULT_PORT,
) {
    interface Callbacks {
        /** Current system clipboard contents, or null if unavailable. */
        fun currentClipSnapshot(): ClipSnapshot?

        /** Apply a clip received from a peer to the system clipboard. */
        fun applyRemoteClip(text: String, timestamp: Long, fromDeviceId: String)

        /** A new device finished its handshake and needs the user to compare SAS codes. */
        fun onPairingRequested(connection: PeerConnection)

        fun onPairingCompleted(device: TrustedDevice)
    }

    data class ClipSnapshot(val text: String, val timestamp: Long) {
        val hash: String get() = ClipHashing.contentHash(text)
    }

    data class PeerStatus(
        val deviceId: String,
        val deviceName: String,
        val state: PeerConnection.State,
        val sas: String? = null,
    )

    @Volatile
    var identity: SyncIdentity? = null
        private set

    private val recent = RecentClipCache()
    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingConnects = ConcurrentHashMap<String, Long>()
    private val lastSentHash = ConcurrentHashMap<String, String>()

    private val _statuses = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerStatus>> = _statuses

    private val _listenPortState = MutableStateFlow(0)

    /** Actual bound port after the server started; 0 while not listening. */
    val actualListenPort: StateFlow<Int> = _listenPortState

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        scope.launch(Dispatchers.IO) {
            if (identity == null) {
                identity = identityProvider()
                println("SyncPeer: identity ${identity?.deviceId ?: "UNAVAILABLE"}")
                if (identity == null) return@launch
            }
            if (stopped) return@launch
            if (listenEnabled) startServer()
            connectToKnownPeers()
        }
    }

    /** Re-attempt identity load (e.g. after Direct Boot unlock on Android). */
    fun ensureIdentity() = start()

    fun stop() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        connections.values.toList().forEach { it.close("engine stopped") }
        pendingConnects.clear()
    }

    fun connectTo(host: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            val id = identity ?: identityProvider() ?: run {
                println("SyncPeer: connectTo $host:$port aborted, no identity")
                return@launch
            }
            if (stopped) return@launch
            println("SyncPeer: connecting to $host:$port")
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
            } catch (e: Exception) {
                println("SyncPeer: connect to $host:$port failed: ${e.message}")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                return@launch
            }
            PeerConnection(socket, PeerConnection.Role.INITIATOR, id, trust, this@SyncPeer, scope).run()
        }
    }

    fun connectToKnownPeers() {
        val now = System.currentTimeMillis()
        for (device in trust.all()) {
            val address = device.lastAddress ?: continue
            val port = device.lastPort ?: SyncProtocol.DEFAULT_PORT
            if (device.deviceId == identity?.deviceId) continue
            val pendingSince = pendingConnects[device.deviceId]
            if (pendingSince != null && now - pendingSince < 30_000) continue
            if (connections[device.deviceId]?.let { it.state.value != PeerConnection.State.CLOSED } == true) continue
            pendingConnects[device.deviceId] = now
            connectTo(address, port)
        }
    }

    fun unpair(deviceId: String) {
        trust.remove(deviceId)
        connections[deviceId]?.close("unpaired")
    }

    /** Broadcast a locally captured clip to all live peers. */
    fun onLocalClipChanged(text: String, timestamp: Long) {
        val id = identity?.deviceId ?: return
        if (text.isEmpty() || text.length > SyncProtocol.MAX_CLIP_CHARS) return
        val hash = ClipHashing.contentHash(text)
        if (recent.isRecent(hash)) return
        recent.record(hash)

        scope.launch(Dispatchers.IO) {
            for (conn in connections.values.filter { it.isLive }) {
                val peerId = conn.peerDeviceId ?: continue
                try {
                    conn.sendClip(UUID.randomUUID().toString(), timestamp, id, text)
                    lastSentHash[peerId] = hash
                } catch (e: Exception) {
                    conn.close("failed to send clip: ${e.message}")
                }
            }
        }
    }

    internal fun register(conn: PeerConnection): Boolean {
        val peerId = conn.peerDeviceId ?: return false
        val myId = identity?.deviceId ?: return false
        synchronized(connections) {
            val existing = connections[peerId]
            if (existing != null && existing.state.value != PeerConnection.State.CLOSED) {
                // Dual simultaneous dials are resolved deterministically: the
                // canonical connection is the one whose initiator has the
                // lower device id. Everyone keeps exactly that one.
                val canonicalIsInitiator = myId < peerId
                val newIsCanonical = (conn.role == PeerConnection.Role.INITIATOR) == canonicalIsInitiator
                if (!newIsCanonical) return false
                existing.close("superseded by a newer connection")
            }
            connections[peerId] = conn
        }
        pendingConnects.remove(peerId)
        updateStatuses()
        return true
    }

    internal fun onConnectionLive(conn: PeerConnection) {
        val peerId = conn.peerDeviceId ?: return
        updateStatuses()
        scope.launch(Dispatchers.IO) {
            // Reconciliation: make sure the peer has our current clip. This is
            // what delivers clips captured while the peer was unreachable.
            val snapshot = callbacks.currentClipSnapshot()
            if (snapshot != null && snapshot.text.isNotEmpty() &&
                snapshot.text.length <= SyncProtocol.MAX_CLIP_CHARS &&
                lastSentHash[peerId] != snapshot.hash
            ) {
                try {
                    conn.sendClip(UUID.randomUUID().toString(), snapshot.timestamp, identity?.deviceId ?: return@launch, snapshot.text)
                    lastSentHash[peerId] = snapshot.hash
                } catch (e: Exception) {
                    conn.close("failed to send clip: ${e.message}")
                    return@launch
                }
            }

            while (conn.isLive) {
                delay(30_000)
                if (!conn.isLive) break
                try {
                    conn.sendPing()
                } catch (e: Exception) {
                    conn.close("keepalive send failed: ${e.message}")
                    break
                }
            }
        }
    }

    internal fun onClipReceived(conn: PeerConnection, message: SyncMessage.ClipText) {
        if (message.text.length > SyncProtocol.MAX_CLIP_CHARS) return
        val hash = ClipHashing.contentHash(message.text)
        if (recent.isRecent(hash)) return
        recent.record(hash)
        callbacks.applyRemoteClip(message.text, message.timestamp, message.originDeviceId)
    }

    internal fun onConnectionClosed(conn: PeerConnection, reason: String) {
        val peerId = conn.peerDeviceId ?: return updateStatuses()
        synchronized(connections) {
            if (connections[peerId] === conn) connections.remove(peerId)
        }
        updateStatuses()
    }

    private fun updateStatuses() {
        _statuses.value = connections.values
            .filter { it.state.value != PeerConnection.State.CLOSED }
            .associate {
                it.peerDeviceId!! to PeerStatus(
                    deviceId = it.peerDeviceId!!,
                    deviceName = it.peerHello?.deviceName ?: "Unknown device",
                    state = it.state.value,
                    sas = if (it.state.value == PeerConnection.State.PENDING_PAIRING) it.sas else null,
                )
            }
    }

    private fun startServer() {
        scope.launch(Dispatchers.IO) {
            val socket = ServerSocket()
            var bound = false
            for (attempt in 0 until 10) {
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(listenPort + attempt))
                    bound = true
                    break
                } catch (e: Exception) {
                    if (listenPort == 0) break // ephemeral bind cannot fail on port choice
                }
            }
            if (!bound || stopped) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                return@launch
            }
            serverSocket = socket
            _listenPortState.value = socket.localPort

            while (!stopped) {
                val accepted = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                val id = identity
                if (id == null) {
                    try {
                        accepted.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                scope.launch(Dispatchers.IO) {
                    PeerConnection(accepted, PeerConnection.Role.RESPONDER, id, trust, this@SyncPeer, scope).run()
                }
            }
        }
    }
}
