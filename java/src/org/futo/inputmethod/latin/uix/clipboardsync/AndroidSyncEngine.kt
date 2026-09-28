package org.futo.inputmethod.latin.uix.clipboardsync

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistorySaveSensitive
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.reventor.sync.protocol.PeerConnection
import org.reventor.sync.protocol.SyncPeer
import org.reventor.sync.protocol.SyncProtocol
import org.reventor.sync.protocol.TrustedDevice
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android side of clipboard sync. Runs inside the IME process (which has
 * clipboard access as the default IME — same reason the clipboard history
 * works), connect-only: desktops listen, the phone dials them.
 */
class AndroidSyncEngine(private val appContext: Context) : SyncPeer.Callbacks {
    data class DiscoveredDevice(
        val deviceId: String,
        val displayName: String,
        val host: String,
        val port: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboardManager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trustStore = DataStoreTrustStore(appContext)

    private var peer: SyncPeer? = null
    private val started = AtomicBoolean(false)

    private val _pairingRequests = MutableStateFlow<List<PeerConnection>>(emptyList())

    /** Live pairing handshakes awaiting user confirmation (SAS shown to the user). */
    val pairingRequests: StateFlow<List<PeerConnection>> = _pairingRequests

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())

    /** Desktops currently visible via mDNS (populated while the pairing screen is open). */
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered

    val statuses get() = peer?.statuses
    val pairedDevices get() = trustStore.all()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val engine = SyncPeer(
            scope = scope,
            identityProvider = { AndroidIdentity.loadOrCreate(appContext) },
            trust = trustStore,
            platformName = "android",
            callbacks = this,
            listenEnabled = false,
        )
        peer = engine
        engine.start()
        mainHandler.post {
            try {
                clipboardManager.addPrimaryClipChangedListener(clipListener)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        mainHandler.post {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipListener)
            } catch (_: Exception) {
            }
        }
        peer?.stop()
        peer = null
        _pairingRequests.value = emptyList()
        _discovered.value = emptyList()
    }

    /** Called when a text field gains focus; refreshes connections opportunistically. */
    fun reconnect() {
        if (started.get()) peer?.connectToKnownPeers()
    }

    fun unpair(deviceId: String) {
        peer?.unpair(deviceId)
    }

    fun acceptPairing(connection: PeerConnection) {
        _pairingRequests.update { list -> list.filterNot { it === connection } }
        connection.acceptPairing()
    }

    fun rejectPairing(connection: PeerConnection) {
        _pairingRequests.update { list -> list.filterNot { it === connection } }
        connection.rejectPairing()
    }

    // ---- pairing screen discovery (NSD/mDNS browse) ----

    fun startDiscovery() {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        if (discoveryListener != null) return
        _discovered.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val deviceId = info.serviceName ?: return
                        val host = info.host?.hostAddress ?: return
                        val name = info.attributes["name"]?.toString(Charsets.UTF_8) ?: deviceId
                        _discovered.update { current ->
                            val updated = current.filterNot {
                                it.deviceId == deviceId || (it.host == host && it.port == info.port)
                            }
                            updated + DiscoveredDevice(deviceId, name, host, info.port)
                        }
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discovered.update { current -> current.filterNot { it.deviceId == serviceInfo.serviceName } }
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
        }
        discoveryListener = listener
        nsd.discoverServices(SyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        _discovered.value = emptyList()
    }

    fun connectTo(device: DiscoveredDevice) {
        peer?.connectTo(device.host, device.port)
    }

    /** Manual address entry, shown in the desktop app's settings window. */
    fun connectToAddress(host: String, port: Int) {
        peer?.connectTo(host, port)
    }

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // ---- clipboard capture ----

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onLocalClipPossiblyChanged()
    }

    private fun onLocalClipPossiblyChanged() {
        val engine = peer ?: return
        if (!appContext.getSettingBlocking(CLIPBOARD_SYNC_ENABLED)) return
        try {
            val clip = clipboardManager.primaryClip ?: return
            if (clip.itemCount < 1) return
            val text = clip.getItemAt(0)?.coerceToText(appContext)?.toString() ?: return
            if (text.isEmpty() || text.length > SyncProtocol.MAX_CLIP_CHARS) return

            val isSensitive = clip.description?.extras?.getBoolean(
                ClipDescription.EXTRA_IS_SENSITIVE, false
            ) == true
            if (isSensitive && !appContext.getSettingBlocking(ClipboardHistorySaveSensitive)) return

            val timestamp = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clip.description?.timestamp
            } else null)
                ?: System.currentTimeMillis()

            engine.onLocalClipChanged(text, timestamp)
        } catch (_: Exception) {
        }
    }

    // ---- SyncPeer.Callbacks ----

    override fun currentClipSnapshot(): SyncPeer.ClipSnapshot? {
        return try {
            val clip = clipboardManager.primaryClip ?: return null
            if (clip.itemCount < 1) return null
            val text = clip.getItemAt(0)?.coerceToText(appContext)?.toString() ?: return null
            if (text.isEmpty()) return null
            val timestamp = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clip.description?.timestamp
            } else null)
                ?: System.currentTimeMillis()
            SyncPeer.ClipSnapshot(text, timestamp)
        } catch (e: Exception) {
            null
        }
    }

    override fun applyRemoteClip(text: String, timestamp: Long, fromDeviceId: String) {
        mainHandler.post {
            try {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("clipboard-sync", text))
            } catch (_: Exception) {
            }
        }
    }

    override fun onPairingRequested(connection: PeerConnection) {
        _pairingRequests.update { list ->
            if (list.any { it.peerDeviceId == connection.peerDeviceId }) list else list + connection
        }
    }

    override fun onPairingCompleted(device: TrustedDevice) {}
}

/**
 * Process-level handle for the sync engine. The engine lives as long as the
 * IME process, but is only active (listener + connections) while the user has
 * clipboard sync enabled and the clipboard history state exists to drive it.
 */
object ClipboardSync {
    @Volatile
    private var engine: AndroidSyncEngine? = null

    @Synchronized
    fun obtain(context: Context): AndroidSyncEngine =
        engine ?: AndroidSyncEngine(context.applicationContext).also { engine = it }

    /**
     * Refreshes connections when a text field gains focus; no-op unless the
     * engine is running (i.e. clipboard sync is enabled).
     */
    fun onInputStarted() {
        engine?.reconnect()
    }
}
