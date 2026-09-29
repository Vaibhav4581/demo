package org.mesh.emergency.ui

import mesh.delivery.DeliveryState
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.storage.InMemoryMessageStore
import mesh.transport.Clock
import mesh.transport.NoOpCrypto
import mesh.transport.Transport
import mesh.transport.TransportListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class FakeTestClock(var currentMs: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = currentMs
    fun advance(ms: Long) { currentMs += ms }
}

class FakeTestTransport(override val localNodeId: NodeId) : Transport {
    val listeners = CopyOnWriteArrayList<TransportListener>()
    val connected = mutableSetOf<NodeId>()

    override fun send(peer: NodeId, packet: Packet): Boolean = connected.contains(peer)
    override fun broadcast(packet: Packet): Int = connected.size
    override fun registerListener(listener: TransportListener) { listeners.add(listener) }
    override fun unregisterListener(listener: TransportListener) { listeners.remove(listener) }

    fun connectPeer(peer: NodeId) {
        connected.add(peer)
        listeners.forEach { it.onPeerConnected(peer) }
    }
}

class UiStateUnitTest {

    private lateinit var clock: FakeTestClock
    private lateinit var transport: FakeTestTransport
    private lateinit var messageStore: InMemoryMessageStore
    private lateinit var meshNode: MeshNode

    private val localNodeId = NodeId("1111111111111111".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    private val peerA = NodeId("2222222222222222".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    private val peerB = NodeId("3333333333333333".chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    @BeforeEach
    fun setup() {
        clock = FakeTestClock(1_000_000L)
        transport = FakeTestTransport(localNodeId)
        messageStore = InMemoryMessageStore()
        meshNode = MeshNode(
            nodeId = localNodeId,
            transport = transport,
            clock = clock,
            messageStore = messageStore,
            crypto = NoOpCrypto()
        )
    }

    @Test
    @DisplayName("Conversation separation correctly isolates broadcast from 1-on-1 direct messages")
    fun testConversationSeparation() {
        meshNode.broadcast("Hello all".toByteArray())
        meshNode.send(peerA, "Hello A".toByteArray())
        meshNode.send(peerB, "Hello B".toByteArray())

        val allMessages = messageStore.getAllMessages()
        assertEquals(3, allMessages.size)

        val broadcastMessages = allMessages.filter { it.dest == null }
        assertEquals(1, broadcastMessages.size)
        assertEquals("Hello all", String(broadcastMessages[0].payload))

        val peerAMessages = allMessages.filter {
            (it.dest == peerA && !it.isIncoming) || (it.origin == peerA && it.isIncoming)
        }
        assertEquals(1, peerAMessages.size)
        assertEquals("Hello A", String(peerAMessages[0].payload))
    }

    @Test
    @DisplayName("Per-message status changes from QUEUED to SENT and DELIVERED with hop count")
    fun testDeliveryStatusAndHopCount() {
        // Initially enqueued without neighbours -> QUEUED
        val packet = meshNode.send(peerA, "Test status".toByteArray())
        val msgId = packet.msgId.toByteArray()

        assertEquals(DeliveryState.QUEUED, meshNode.getDeliveryState(msgId))

        // Connect peer -> Outbox attempts forward -> SENT
        transport.connectPeer(peerA)
        meshNode.onPeerConnected(peerA)
        assertEquals(DeliveryState.SENT, meshNode.getDeliveryState(msgId))

        // Receive ACK arriving via 2 hops -> DELIVERED with hopCount = 2
        val ackPacket = PacketFactory.createAck(
            origin = peerA,
            dest = localNodeId,
            ackedMsgId = msgId,
            hopCount = 2,
            createdAtMs = clock.nowMs()
        )
        meshNode.onAckReceived(ackPacket)

        val updatedRecord = messageStore.getMessage(msgId)
        assertNotNull(updatedRecord)
        assertEquals(DeliveryState.DELIVERED, updatedRecord!!.deliveryState)
        assertEquals(2, updatedRecord.hopCount)
    }

    @Test
    @DisplayName("Mesh screen routing table correctly tracks distance and next hop")
    fun testMeshScreenRoutingTable() {
        // Learn route to peerB via peerA with cost 2
        meshNode.router.routeTable.learnRoute(
            dest = peerB,
            via = peerA,
            cost = 2,
            nowMs = clock.nowMs()
        )

        val knownRoutes = meshNode.getKnownRoutes()
        assertEquals(1, knownRoutes.size)
        val route = knownRoutes[0]
        assertEquals(peerB, route.dest)
        assertEquals(peerA, route.nextHop)
        assertEquals(2, route.cost)
    }

    @Test
    @DisplayName("Incoming DATA packet stores received hop count on delivery")
    fun testIncomingMessageHopCount() {
        val incomingData = PacketFactory.createData(
            origin = peerB,
            dest = localNodeId,
            payload = "Incoming multi-hop alert".toByteArray(),
            hopCount = 3,
            ttl = 5,
            createdAtMs = clock.nowMs()
        )

        meshNode.onLocalDelivery(incomingData)

        val delivered = messageStore.getMessage(incomingData.msgId.toByteArray())
        assertNotNull(delivered)
        assertTrue(delivered!!.isIncoming)
        assertEquals(3, delivered.hopCount)
        assertEquals(DeliveryState.DELIVERED, delivered.deliveryState)
        assertEquals("Incoming multi-hop alert", String(delivered.payload))
    }

    @Test
    @DisplayName("Debug metrics correctly track anti-storm duplicates dropped and outbox queue")
    fun testDebugMetrics() {
        val dataPacket = PacketFactory.createData(
            origin = peerA,
            dest = localNodeId,
            payload = "Storm packet".toByteArray(),
            createdAtMs = clock.nowMs()
        )

        // First arrival is fresh
        meshNode.router.onPacketReceived(peerA, dataPacket)
        assertEquals(0L, meshNode.router.dedupManager.duplicatesDropped)

        // Duplicate arrival is dropped by anti-storm filter
        meshNode.router.onPacketReceived(peerA, dataPacket)
        assertEquals(1L, meshNode.router.dedupManager.duplicatesDropped)
    }
}
