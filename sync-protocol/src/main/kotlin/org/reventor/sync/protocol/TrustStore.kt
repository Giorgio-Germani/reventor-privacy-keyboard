package org.reventor.sync.protocol

import kotlinx.serialization.Serializable

/** A device this peer has completed SAS-verified pairing with. */
@Serializable
data class TrustedDevice(
    val deviceId: String,
    var deviceName: String,
    val platform: String,
    /** Hex-encoded X.509 identity public key; the root of trust for this device. */
    val identityPublicKey: String,
    val pairedAt: Long,
    var lastAddress: String? = null,
    var lastPort: Int? = null,
    var lastSeen: Long = 0,
)

/** Persistence of paired devices. Android backs this with DataStore, desktop with a JSON file. */
interface TrustStore {
    fun all(): List<TrustedDevice>
    fun find(deviceId: String): TrustedDevice?
    fun upsert(device: TrustedDevice)
    fun remove(deviceId: String)
}

class InMemoryTrustStore(initial: List<TrustedDevice> = emptyList()) : TrustStore {
    private val devices = LinkedHashMap<String, TrustedDevice>(initial.associateBy { it.deviceId })

    @Synchronized
    override fun all(): List<TrustedDevice> = devices.values.map { it.copy() }

    @Synchronized
    override fun find(deviceId: String): TrustedDevice? = devices[deviceId]?.copy()

    @Synchronized
    override fun upsert(device: TrustedDevice) {
        devices[device.deviceId] = device
    }

    @Synchronized
    override fun remove(deviceId: String) {
        devices.remove(deviceId)
    }
}
