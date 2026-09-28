package org.reventor.sync.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full-engine tests over real localhost sockets: SAS pairing, bidirectional
 * clip flow, echo suppression, pairing rejection, and reconnect reconciliation.
 */
class SyncPeerIntegrationTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class TestPeer(
        val name: String,
        scope: CoroutineScope,
        listenEnabled: Boolean,
        listenPort: Int = 0,
    ) {
        val trust = InMemoryTrustStore()
        val identity = SyncIdentity.generate(UUID.randomUUID().toString(), name)
        val applied = CopyOnWriteArrayList<Pair<String, String>>()
        val pairingRequests = CopyOnWriteArrayList<PeerConnection>()
        val sases = CopyOnWriteArrayList<String>()
        @Volatile var autoAccept = true

        @Volatile var currentText: String = ""
        @Volatile var currentTimestamp: Long = 0

        val peer = SyncPeer(
            scope = scope,
            identityProvider = { identity },
            trust = trust,
            platformName = "test-$name",
            callbacks = object : SyncPeer.Callbacks {
                override fun currentClipSnapshot(): SyncPeer.ClipSnapshot? =
                    if (currentText.isEmpty()) null else SyncPeer.ClipSnapshot(currentText, currentTimestamp)

                override fun applyRemoteClip(text: String, timestamp: Long, fromDeviceId: String) {
                    applied.add(text to fromDeviceId)
                    copyLocally(text, timestamp)
                }

                override fun onPairingRequested(connection: PeerConnection) {
                    pairingRequests.add(connection)
                    sases.add(connection.sas)
                    if (autoAccept) connection.acceptPairing() else connection.rejectPairing()
                }

                override fun onPairingCompleted(device: TrustedDevice) {}
            },
            listenEnabled = listenEnabled,
            listenPort = listenPort,
        )

        /** Simulates the local clipboard being set (local copy or applied remote clip). */
        fun copyLocally(text: String, timestamp: Long = System.currentTimeMillis()) {
            currentText = text
            currentTimestamp = timestamp
            peer.onLocalClipChanged(text, timestamp)
        }
    }

    private suspend fun awaitUntil(
        label: String = "<unnamed>",
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition '$label' not met within ${timeoutMs}ms")
            }
            delay(50)
        }
    }

    /** Starts [server] listening, pairs it with [client], returns the server port. */
    private suspend fun pair(client: TestPeer, server: TestPeer): Int {
        server.peer.start()
        awaitUntil("server listening") { server.peer.actualListenPort.value != 0 }
        val port = server.peer.actualListenPort.value
        val clientRequestsBefore = client.pairingRequests.size
        client.peer.start()
        client.peer.connectTo("127.0.0.1", port)

        awaitUntil("pairing requests on both sides") {
            client.pairingRequests.size == clientRequestsBefore + 1 && server.pairingRequests.size == 1
        }
        assertEquals(client.sases[clientRequestsBefore], server.sases[0])
        assertEquals(6, client.sases[clientRequestsBefore].length)
        assertNotEquals("000000", client.sases[clientRequestsBefore])

        awaitUntil("trust stored on both sides") {
            client.trust.find(server.identity.deviceId) != null &&
                server.trust.find(client.identity.deviceId) != null
        }
        return port
    }

    @Test
    fun pairing_exchangesMatchingSasAndStoresTrust() = runBlocking<Unit> {
        val client = TestPeer("client", scope, listenEnabled = false)
        val server = TestPeer("server", scope, listenEnabled = true)
        pair(client, server)
    }

    @Test
    fun clips_flowBothDirections_withoutEcho() = runBlocking {
        val client = TestPeer("client", scope, listenEnabled = false)
        val server = TestPeer("server", scope, listenEnabled = true)
        pair(client, server)

        client.copyLocally("hello from client", 1000)
        awaitUntil { server.applied.any { it.first == "hello from client" } }

        server.copyLocally("hello from server", 2000)
        awaitUntil { client.applied.any { it.first == "hello from server" } }

        // Give any (buggy) echo a chance to arrive, then assert none did.
        delay(500)
        assertEquals(listOf("hello from server"), client.applied.map { it.first })
        assertEquals(listOf("hello from client"), server.applied.map { it.first })
    }

    @Test
    fun pairing_rejectionStoresNoTrust() = runBlocking {
        val client = TestPeer("client", scope, listenEnabled = false)
        val server = TestPeer("server", scope, listenEnabled = true)
        client.autoAccept = false

        server.peer.start()
        awaitUntil { server.peer.actualListenPort.value != 0 }
        client.peer.start()
        client.peer.connectTo("127.0.0.1", server.peer.actualListenPort.value)

        awaitUntil { client.pairingRequests.size == 1 }
        val rejectedConn = client.pairingRequests[0]
        awaitUntil { rejectedConn.state.value == PeerConnection.State.CLOSED }

        delay(500)
        assertTrue(client.trust.all().isEmpty())
        assertTrue(server.trust.all().isEmpty())
    }

    @Test
    fun reconcile_deliversClipCapturedWhilePeerWasAway() = runBlocking {
        val client = TestPeer("client", scope, listenEnabled = false)
        val server = TestPeer("server", scope, listenEnabled = true)
        pair(client, server)

        // Sever the link, then capture a clip on the server side.
        client.peer.stop()
        awaitUntil("server sees connection closed") { server.peer.statuses.value.isEmpty() }

        server.copyLocally("copied while you were away", 3000)
        awaitUntil("still offline") { server.peer.statuses.value.isEmpty() } // still nothing live to send to

        // Client comes back: reconnect via the stored last-seen address.
        client.peer.start()
        awaitUntil { client.applied.any { it.first == "copied while you were away" } }
    }

    @Test
    fun fanout_deliversClipToMultiplePeers() = runBlocking {
        val phone = TestPeer("phone", scope, listenEnabled = false)
        val desktopA = TestPeer("desktopA", scope, listenEnabled = true)
        val desktopB = TestPeer("desktopB", scope, listenEnabled = true)
        pair(phone, desktopA)
        pair(phone, desktopB)

        phone.copyLocally("broadcast me", 4000)
        awaitUntil { desktopA.applied.any { it.first == "broadcast me" } }
        awaitUntil { desktopB.applied.any { it.first == "broadcast me" } }
    }
}
