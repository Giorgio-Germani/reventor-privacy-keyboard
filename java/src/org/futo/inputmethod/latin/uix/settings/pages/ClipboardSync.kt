package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.clipboardsync.CLIPBOARD_SYNC_DEVICE_NAME
import org.futo.inputmethod.latin.uix.clipboardsync.CLIPBOARD_SYNC_ENABLED
import org.futo.inputmethod.latin.uix.clipboardsync.ClipboardSync
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingTextField
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.reventor.sync.protocol.PeerConnection
import org.reventor.sync.protocol.SyncPeer
import org.reventor.sync.protocol.TrustedDevice

val ClipboardSyncMenu = UserSettingsMenu(
    title = R.string.clipboard_sync_settings_title,
    navPath = "clipboardSync", registerNavPath = true,
    settings = listOf(
        userSettingToggleDataStore(
            title = R.string.clipboard_sync_enable,
            subtitle = R.string.clipboard_sync_enable_subtitle,
            setting = CLIPBOARD_SYNC_ENABLED
        ),

        UserSetting(name = R.string.clipboard_sync_device_name) {
            SettingTextField(
                stringResource(R.string.clipboard_sync_device_name),
                stringResource(R.string.clipboard_sync_device_name),
                CLIPBOARD_SYNC_DEVICE_NAME,
            )
        },

        UserSetting(name = R.string.clipboard_sync_paired_devices) {
            PairedDevicesSection()
        },

        UserSetting(name = R.string.clipboard_sync_pair_new) {
            PairingSection()
        },
    )
)

@Composable
private fun PairedDevicesSection() {
    val context = LocalContext.current
    val engine = remember { ClipboardSync.obtain(context) }

    // Pairing must work from the settings alone, even if the IME service
    // (which normally owns the engine's lifecycle) hasn't run yet.
    val enabled = org.futo.inputmethod.latin.uix.settings.useDataStoreValue(CLIPBOARD_SYNC_ENABLED)
    androidx.compose.runtime.LaunchedEffect(enabled) {
        if (enabled) engine.start()
    }

    val statuses by produceState<Map<String, SyncPeer.PeerStatus>>(emptyMap(), engine) {
        engine.statuses?.collect { value = it } ?: run { value = emptyMap() }
    }
    val devices = remember(statuses) { engine.pairedDevices }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(stringResource(R.string.clipboard_sync_paired_devices))

        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.clipboard_sync_no_paired_devices),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        devices.forEach { device ->
            SettingItem(
                title = device.deviceName,
                subtitle = statusLine(statuses[device.deviceId]?.state, device.platform),
                onClick = null,
            ) {
                OutlinedButton(onClick = { engine.unpair(device.deviceId) }) {
                    Text(stringResource(R.string.clipboard_sync_unpair))
                }
            }
        }
    }
}

@Composable
private fun statusLine(state: PeerConnection.State?, platform: String): String =
    when (state) {
        PeerConnection.State.LIVE -> stringResource(R.string.clipboard_sync_status_connected)
        else -> stringResource(R.string.clipboard_sync_status_offline)
    } + "  ·  " + platform

@Composable
private fun PairingSection() {
    val context = LocalContext.current
    val engine = remember { ClipboardSync.obtain(context) }

    val discovered by engine.discovered.collectAsState()
    val pairingRequests by engine.pairingRequests.collectAsState()

    DisposableEffect(engine) {
        engine.startDiscovery()
        onDispose { engine.stopDiscovery() }
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(stringResource(R.string.clipboard_sync_pair_new))
        Text(
            stringResource(R.string.clipboard_sync_discovering),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        val candidates = discovered.filterNot { candidate ->
            engine.pairedDevices.any { it.deviceId == candidate.deviceId }
        }
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.clipboard_sync_discovery_none),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        candidates.forEach { device ->
            SettingItem(
                title = device.displayName,
                subtitle = "${device.host}:${device.port}",
                onClick = { engine.connectTo(device) },
            ) {}
        }

        ManualAddressEntry(engine)

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.clipboard_sync_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }

    pairingRequests.forEach { connection ->
        PairingConfirmDialog(
            connection,
            onAccept = { engine.acceptPairing(connection) },
            onReject = { engine.rejectPairing(connection) },
        )
    }
}

@Composable
private fun ManualAddressEntry(engine: org.futo.inputmethod.latin.uix.clipboardsync.AndroidSyncEngine) {
    var address by remember { mutableStateOf("") }
    val port = address.substringAfterLast(':', "").toIntOrNull()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            placeholder = { Text(stringResource(R.string.clipboard_sync_manual_label)) },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = port != null && address.substringBeforeLast(':').isNotBlank(),
            onClick = {
                engine.connectToAddress(
                    address.substringBeforeLast(':').trim(),
                    port ?: return@TextButton,
                )
            },
        ) {
            Text(stringResource(R.string.clipboard_sync_manual_connect))
        }
    }
}

@Composable
private fun PairingConfirmDialog(
    connection: PeerConnection,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val deviceName = connection.peerHello?.deviceName ?: "device"
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.clipboard_sync_confirm_pairing, deviceName)) },
        text = {
            Column {
                Text(stringResource(R.string.clipboard_sync_confirm_pairing_code))
                Spacer(Modifier.height(12.dp))
                Text(
                    connection.sas,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.clipboard_sync_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.clipboard_sync_cancel_button)) }
        },
    )
}
