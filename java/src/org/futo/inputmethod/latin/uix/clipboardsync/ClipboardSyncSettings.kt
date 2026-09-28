package org.futo.inputmethod.latin.uix.clipboardsync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.reventor.sync.protocol.SyncJson
import org.reventor.sync.protocol.TrustedDevice
import org.reventor.sync.protocol.TrustStore

/** Master switch; the engine only runs while this is on. */
val CLIPBOARD_SYNC_ENABLED = SettingsKey(
    key = booleanPreferencesKey("clipboard_sync_enabled"),
    default = false,
)

/** User-chosen name shown to peers; blank = derived from the device. */
val CLIPBOARD_SYNC_DEVICE_NAME = SettingsKey(
    key = stringPreferencesKey("clipboard_sync_device_name"),
    default = "",
)

/** JSON-encoded list of [TrustedDevice]. */
val CLIPBOARD_SYNC_DEVICES = SettingsKey(
    key = stringPreferencesKey("clipboard_sync_paired_devices"),
    default = "[]",
)

/** Identity blob: deviceId:x509-pub-hex:keystore-wrapped-pkcs8-hex */
val CLIPBOARD_SYNC_IDENTITY = SettingsKey(
    key = stringPreferencesKey("clipboard_sync_identity_blob"),
    default = "",
)

/** [TrustStore] backed by the app's DataStore. */
class DataStoreTrustStore(private val context: Context) : TrustStore {
    private val lock = Any()

    override fun all(): List<TrustedDevice> = synchronized(lock) {
        decode(context.getSettingBlocking(CLIPBOARD_SYNC_DEVICES))
    }

    override fun find(deviceId: String): TrustedDevice? =
        all().firstOrNull { it.deviceId == deviceId }

    override fun upsert(device: TrustedDevice) = synchronized(lock) {
        val current = decode(context.getSettingBlocking(CLIPBOARD_SYNC_DEVICES))
            .filterNot { it.deviceId == device.deviceId }
        context.setSettingBlocking(
            CLIPBOARD_SYNC_DEVICES.key,
            SyncJson.encodeToString(ListSerializer(TrustedDevice.serializer()), current + device),
        )
    }

    override fun remove(deviceId: String) = synchronized(lock) {
        val current = decode(context.getSettingBlocking(CLIPBOARD_SYNC_DEVICES))
            .filterNot { it.deviceId == deviceId }
        context.setSettingBlocking(
            CLIPBOARD_SYNC_DEVICES.key,
            SyncJson.encodeToString(ListSerializer(TrustedDevice.serializer()), current),
        )
    }

    private fun decode(blob: String): List<TrustedDevice> = try {
        if (blob.isEmpty()) emptyList()
        else SyncJson.decodeFromString(ListSerializer(TrustedDevice.serializer()), blob)
    } catch (e: Exception) {
        emptyList()
    }
}
