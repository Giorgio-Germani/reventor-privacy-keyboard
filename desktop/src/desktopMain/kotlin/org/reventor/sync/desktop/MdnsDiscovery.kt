package org.reventor.sync.desktop

import org.reventor.sync.protocol.SyncPeer
import org.reventor.sync.protocol.SyncProtocol
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Advertises this peer over mDNS and dials trusted peers as they appear on
 * the network (covers DHCP address changes without re-pairing).
 *
 * One JmDNS instance is created per site-local IPv4 interface — machines with
 * several NICs (VPNs, virtual adapters) otherwise advertise on the wrong one.
 */
class MdnsDiscovery(
    private val peer: () -> SyncPeer?,
) : ServiceListener {
    private val jmdnsInstances = mutableListOf<JmDNS>()

    fun start(port: Int, deviceId: String, deviceName: String) {
        stop()
        val interfaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
                .map { it as java.net.InetAddress }
                .toList()
        }.getOrDefault(emptyList())

        val targets = interfaces.ifEmpty { listOf(java.net.InetAddress.getLocalHost()) }
        for (address in targets) {
            try {
                val instance = JmDNS.create(address, deviceId)
                instance.registerService(
                    ServiceInfo.create(
                        SyncProtocol.SERVICE_TYPE, deviceId, port, 0, 0,
                        mapOf("name" to deviceName, "platform" to "desktop"),
                    )
                )
                instance.addServiceListener(SyncProtocol.SERVICE_TYPE, this)
                jmdnsInstances.add(instance)
            } catch (e: Exception) {
                println("mDNS: failed to start on $address: ${e.message}")
            }
        }
    }

    fun stop() {
        for (instance in jmdnsInstances) {
            runCatching {
                instance.unregisterAllServices()
                instance.close()
            }
        }
        jmdnsInstances.clear()
    }

    override fun serviceAdded(event: ServiceEvent) {}

    override fun serviceResolved(event: ServiceEvent) {
        val engine = peer() ?: return
        val info = event.info
        val deviceId = info.name ?: return
        val selfId = engine.identity?.deviceId ?: return
        if (deviceId == selfId) return
        // Only auto-dial devices we already trust; new devices pair through the UI.
        if (engine.trust.find(deviceId) == null) return
        val address = info.inet4Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull() ?: return
        engine.connectTo(address.hostAddress ?: return, info.port)
    }

    override fun serviceRemoved(event: ServiceEvent) {}
}
