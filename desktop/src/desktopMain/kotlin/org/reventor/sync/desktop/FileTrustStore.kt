package org.reventor.sync.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.reventor.sync.protocol.TrustedDevice
import org.reventor.sync.protocol.TrustStore
import java.io.File

/** TrustStore backed by trusted.json in the app config dir. */
class FileTrustStore : TrustStore {
    @Serializable
    private data class Wrapped(val devices: List<TrustedDevice>)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file get() = File(appConfigDir(), "trusted.json")
    private val lock = Any()

    init {
        if (!file.isFile) persist()
    }

    private fun load(): Wrapped =
        runCatching { json.decodeFromString<Wrapped>(file.readText()) }.getOrDefault(Wrapped(emptyList()))

    private fun persist() {
        file.writeText(json.encodeToString(load()))
    }

    override fun all(): List<TrustedDevice> = synchronized(lock) { load().devices }

    override fun find(deviceId: String): TrustedDevice? =
        synchronized(lock) { load().devices.firstOrNull { it.deviceId == deviceId } }

    override fun upsert(device: TrustedDevice) = synchronized(lock) {
        val current = load().devices.filterNot { it.deviceId == device.deviceId } + device
        file.writeText(json.encodeToString(Wrapped(current)))
    }

    override fun remove(deviceId: String) = synchronized(lock) {
        val current = load().devices.filterNot { it.deviceId == deviceId }
        file.writeText(json.encodeToString(Wrapped(current)))
    }
}
