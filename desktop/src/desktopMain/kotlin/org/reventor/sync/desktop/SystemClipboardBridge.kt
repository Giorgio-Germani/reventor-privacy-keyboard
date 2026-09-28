package org.reventor.sync.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.reventor.sync.protocol.PeerConnection
import org.reventor.sync.protocol.SyncPeer
import org.reventor.sync.protocol.TrustedDevice
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

/**
 * Bridges the JVM system clipboard (AWT) to the sync engine, and owns the
 * app-level state shown in the settings window.
 *
 * AWT offers no clipboard-change notification on Windows/macOS, so the local
 * side is a poll loop driven by Main; remote clips are applied directly.
 * Echoes are suppressed by the engine's content-hash cache.
 */
class SystemClipboardBridge : SyncPeer.Callbacks {
    private var engine: SyncPeer? = null
    private var config: DesktopConfig? = null
    private var saveConfig: ((DesktopConfig) -> Unit)? = null

    @Volatile
    private var lastSeenText: String? = null

    /** Set by Main to re-register mDNS after a rename. */
    var mdnsRestarter: (String) -> Unit = {}

    private val _trusted = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val trustedFlow: StateFlow<List<TrustedDevice>> = _trusted

    private val _enabled = MutableStateFlow(true)
    val enabledFlow: StateFlow<Boolean> = _enabled

    val statusesFlow get() = engine?.statuses

    val deviceName: String get() = config?.deviceName ?: "Desktop"

    fun attach(engine: SyncPeer, config: DesktopConfig, saveConfig: (DesktopConfig) -> Unit) {
        this.engine = engine
        this.config = config
        this.saveConfig = saveConfig
        _enabled.value = config.syncEnabled
        refreshTrusted()
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        mutateConfig { it.copy(syncEnabled = value) }
    }

    fun renameDevice(name: String) {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return
        mutateConfig { it.copy(deviceName = trimmed) }
        engine?.identity?.deviceName = trimmed
        mdnsRestarter(trimmed)
    }

    fun unpair(deviceId: String) {
        engine?.unpair(deviceId)
        refreshTrusted()
    }

    fun refreshTrusted() {
        _trusted.value = engine?.trust?.all() ?: emptyList()
    }

    /** TCP port the engine listens on; 0 while not listening. */
    fun listenPort(): Int = engine?.actualListenPort?.value ?: 0

    /** Site-local IPv4 addresses other devices can reach this machine on. */
    fun localAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .map { (it as java.net.Inet4Address).hostAddress ?: "" }
            .filter { it.isNotEmpty() }
            .toList()
    }.getOrDefault(emptyList())

    private fun mutateConfig(transform: (DesktopConfig) -> DesktopConfig) {
        val current = config ?: return
        val updated = transform(current)
        config = updated
        saveConfig?.invoke(updated)
    }

    /** One poll tick; called from the poll coroutine. */
    fun poll() {
        if (!_enabled.value) return
        val text = readText() ?: return
        if (text == lastSeenText) return
        lastSeenText = text
        engine?.onLocalClipChanged(text, System.currentTimeMillis())
    }

    fun readText(): String? {
        return try {
            val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
            // Skip non-text copies (files, images) in V1.
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
                !contents.isDataFlavorSupported(DataFlavor.stringFlavor)
            ) return null
            if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
            val text = contents.getTransferData(DataFlavor.stringFlavor) as? String
            text?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    override fun currentClipSnapshot(): SyncPeer.ClipSnapshot? {
        val text = readText() ?: return null
        return SyncPeer.ClipSnapshot(text, System.currentTimeMillis())
    }

    override fun applyRemoteClip(text: String, timestamp: Long, fromDeviceId: String) {
        if (!_enabled.value) return
        SwingUtilities.invokeLater {
            try {
                lastSeenText = text
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            } catch (_: Exception) {
            }
        }
    }

    override fun onPairingRequested(connection: PeerConnection) {
        SwingUtilities.invokeLater {
            val message = "Device \"${connection.peerHello?.deviceName}\" wants to sync clipboards.\n\n" +
                "Confirm this security code is shown there too:\n\n    ${connection.sas}"
            val options = arrayOf("Pair", "Cancel")
            val choice = javax.swing.JOptionPane.showOptionDialog(
                null, message, "Clipboard sync pairing",
                javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, options, options[0],
            )
            if (choice == 0) connection.acceptPairing() else connection.rejectPairing()
            refreshTrusted()
        }
    }

    override fun onPairingCompleted(device: TrustedDevice) {
        refreshTrusted()
    }
}
