package org.reventor.sync.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.reventor.sync.protocol.SyncPeer
import org.reventor.sync.protocol.SyncProtocol
import java.awt.GraphicsEnvironment
import kotlin.system.exitProcess

fun main() {
    val config = DesktopConfig.loadOrCreate()
    val trust = FileTrustStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val bridge = SystemClipboardBridge()
    val peer = SyncPeer(
        scope = scope,
        identityProvider = { config.identity() },
        trust = trust,
        platformName = "desktop",
        callbacks = bridge,
        listenEnabled = true,
        listenPort = SyncProtocol.DEFAULT_PORT,
    )
    bridge.attach(peer, config) { DesktopConfig.save(it) }

    val mdns = MdnsDiscovery { peer }
    bridge.mdnsRestarter = { newName ->
        val port = peer.actualListenPort.value
        mdns.stop()
        if (port != 0) mdns.start(port, config.deviceId, newName)
    }

    peer.start()

    // Start advertising once the server socket has bound.
    scope.launch {
        val port = peer.actualListenPort.first { it != 0 }
        mdns.start(port, config.deviceId, config.deviceName)
    }

    // Poll the system clipboard; AWT has no change listener on Windows/macOS.
    scope.launch {
        while (true) {
            bridge.poll()
            delay(800)
        }
    }

    val trayApp = TrayApp(bridge) {
        mdns.stop()
        peer.stop()
        exitProcess(0)
    }
    trayApp.startTray()

    if (GraphicsEnvironment.isHeadless()) {
        println("Reventor Clipboard Sync running (headless). Press Ctrl+C to stop.")
        Thread.currentThread().join()
    } else {
        runUi(trayApp)
    }
}
