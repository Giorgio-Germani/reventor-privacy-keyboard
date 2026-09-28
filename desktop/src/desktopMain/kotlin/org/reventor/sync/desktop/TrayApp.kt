package org.reventor.sync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.reventor.sync.protocol.PeerConnection
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** The system tray icon + popup (AWT — reliable on Windows/macOS/X11), and owns the Compose window. */
class TrayApp(
    private val bridge: SystemClipboardBridge,
    private val onQuit: () -> Unit,
) {
    val settingsVisible = mutableStateOf(false)
    private var trayIcon: TrayIcon? = null

    fun startTray() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
        val icon = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { image ->
            val g = image.createGraphics()
            g.color = Color(30, 30, 34)
            g.fillRoundRect(2, 2, 28, 28, 10, 10)
            g.color = Color(120, 200, 255)
            g.fillRoundRect(8, 12, 16, 4, 2, 2)
            g.fillRoundRect(8, 20, 12, 4, 2, 2)
            g.dispose()
        }

        val menu = PopupMenu()
        val settings = MenuItem("Settings").apply {
            addActionListener { settingsVisible.value = true }
        }
        val quit = MenuItem("Quit").apply { addActionListener { onQuit() } }
        menu.add(settings)
        menu.addSeparator()
        menu.add(quit)

        val tray = TrayIcon(icon, "Reventor Clipboard Sync", menu)
        tray.isImageAutoSize = true
        tray.addActionListener { settingsVisible.value = true }
        SystemTray.getSystemTray().add(tray)
        trayIcon = tray
    }

    fun stopTray() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }

    @Composable
    fun WindowContent() {
        Window(
            onCloseRequest = { settingsVisible.value = false },
            visible = settingsVisible.value,
            title = "Reventor Clipboard Sync",
            state = rememberWindowState(width = 480.dp, height = 600.dp),
        ) {
            SettingsWindow()
        }
    }

    @Composable
    private fun SettingsWindow() {
        val statuses = bridge.statusesFlow?.collectAsState()?.value ?: emptyMap()
        val trusted by bridge.trustedFlow.collectAsState()
        val enabled by bridge.enabledFlow.collectAsState()
        var nameDraft by remember { mutableStateOf(bridge.deviceName) }
        var autostart by remember { mutableStateOf(AutoStart.isEnabled()) }

        MaterialTheme {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reventor Clipboard Sync", style = MaterialTheme.typography.titleLarge)

                val port = bridge.listenPort()
                val addresses = bridge.localAddresses()
                if (port != 0 && addresses.isNotEmpty()) {
                    Text(
                        "Reachable at  " + addresses.joinToString("  or  ") { "$it:$port" } +
                            "\nEnter this address on the phone if it can't find this PC automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color.Gray,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync enabled", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { bridge.setEnabled(it) })
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("This device's name") },
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { bridge.renameDevice(nameDraft) }) { Text("Save") }
                }

                Text("Paired devices", style = MaterialTheme.typography.titleMedium)
                if (trusted.isEmpty()) {
                    Text(
                        "No paired devices yet. Pair from the keyboard: Settings → Clipboard Sync → " +
                            "Pair new device, while both are on the same network.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                trusted.forEach { device ->
                    val status = statuses[device.deviceId]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.deviceName)
                            Text(
                                text = when (status?.state) {
                                    PeerConnection.State.LIVE -> "connected"
                                    PeerConnection.State.PENDING_PAIRING -> "pairing…"
                                    PeerConnection.State.HANDSHAKE -> "connecting…"
                                    else -> "offline" + (device.lastAddress?.let { " (last seen at $it)" } ?: "")
                                } + "  ·  ${device.platform}",
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color.Gray,
                            )
                        }
                        OutlinedButton(onClick = { bridge.unpair(device.deviceId) }) { Text("Unpair") }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Launch at login", Modifier.weight(1f))
                    Checkbox(checked = autostart, onCheckedChange = {
                        AutoStart.setEnabled(it)
                        autostart = AutoStart.isEnabled()
                    })
                }
            }
        }
    }
}

/** Runs the Compose window host on the calling thread; never returns. */
fun runUi(trayApp: TrayApp) {
    application(exitProcessOnExit = false) {
        trayApp.WindowContent()
    }
}
