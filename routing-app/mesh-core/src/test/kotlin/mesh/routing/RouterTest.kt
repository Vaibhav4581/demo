package mesh.routing

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import mesh.fakes.FakeClock
import mesh.fakes.FakeTransport
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.protocol.PacketType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RouterTest {

    private val localNodeId = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val peerB = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
    private val peerC = NodeId(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0))
    private val peerD = NodeId(byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0))

    private lateinit var clock: FakeClock
    private lateinit var transport: FakeTransport
    private lateinit var router: Router

    private val deliveredPackets = mutableListOf<Packet>()
    private val relayedPackets = mutableListOf<Pair<Packet, NodeId>>()
    private val droppedPackets = mutableListOf<Pair<Packet, DropReason>>()

    @BeforeEach
    fun setup() {
        clock = FakeClock()
        transport = FakeTransport(localNodeId)
        router = Router(localNodeId, transport, clock)

        router.addListener(object : RouterListener {
            override fun onLocalDelivery(packet: Packet) {
                deliveredPackets.add(packet)
            }

            override fun onPacketRelayed(packet: Packet, nextHop: NodeId) {
                relayedPackets.add(Pair(packet, nextHop))
            }

            override fun onPacketDropped(packet: Packet, reason: DropReason) {
                droppedPackets.add(Pair(packet, reason))
            }
        })
    }

    @Test
    fun `duplicate packets are dropped`() {
        val packet = PacketFactory.createData(
            origin = peerB,
            dest = localNodeId,
            payload = "Hello".toByteArray()
        )

        // First presentation
        router.onPacketReceived(peerB, packet)
        assertThat(deliveredPackets.size).isEqualTo(1)
        assertThat(droppedPackets.size).isEqualTo(0)

        // Duplicate presentation
        router.onPacketReceived(peerB, packet)
        assertThat(deliveredPackets.size).isEqualTo(1)
        assertThat(droppedPackets.size).isEqualTo(1)
        assertThat(droppedPackets[0].second).isEqualTo(DropReason.DUPLICATE)
    }

    @Test
    fun `ttl reaching 0 stops forwarding`() {
        // Connected neighbour C to whom we could forward
        val transportC = FakeTransport(peerC)
        transport.link(transportC)

        // Packet for D with TTL = 1 (cannot be forwarded further)
        val packet = PacketFactory.createData(
            origin = peerB,
            dest = peerD,
            payload = "Test".toByteArray(),
            ttl = 1
        )

        router.onPacketReceived(peerB, packet)
        assertThat(transport.sentTransmissions.size).isEqualTo(0)
        assertThat(droppedPackets.size).isEqualTo(1)
        assertThat(droppedPackets[0].second).isEqualTo(DropReason.TTL_EXPIRED)
    }

    @Test
    fun `packet is never sent back to its sender (split horizon)`() {
        val transportB = FakeTransport(peerB)
        val transportC = FakeTransport(peerC)
        transport.link(transportB)
        transport.link(transportC)

        val broadcastPacket = PacketFactory.createData(
            origin = peerB,
            dest = null,
            payload = "Broadcast".toByteArray(),
            ttl = 5
        )

        // Arrives from peerB
        router.onPacketReceived(peerB, broadcastPacket)

        // Should be delivered locally because it's broadcast
        assertThat(deliveredPackets.size).isEqualTo(1)

        // Forwarded to peerC, but NEVER back to peerB
        val recipients = transport.sentTransmissions.map { it.first }
        assertThat(recipients).contains(peerC)
        assertThat(recipients).doesNotContain(peerB)
    }

    @Test
    fun `route learning after packet arrives from origin via intermediate neighbour`() {
        val transportC = FakeTransport(peerC)
        transport.link(transportC)

        // Packet originating at peerB, arriving at localNodeId via peerC with hopCount = 2
        val packet = PacketFactory.createData(
            origin = peerB,
            dest = localNodeId,
            payload = "Data".toByteArray(),
            hopCount = 2
        )

        router.onPacketReceived(peerC, packet)

        // Route toward peerB should be learned with nextHop = peerC and cost = 3 (2 hops + 1)
        val routeToB = router.routeTable.getRoute(peerB, clock.nowMs())
        assertThat(routeToB).isNotNull()
        assertThat(routeToB!!.nextHop).isEqualTo(peerC)
        assertThat(routeToB.cost).isEqualTo(3)
    }

    @Test
    fun `direct message to local node generates automatic ACK back to sender`() {
        val transportB = FakeTransport(peerB)
        transport.link(transportB)

        val packet = PacketFactory.createData(
            origin = peerB,
            dest = localNodeId,
            payload = "Important".toByteArray()
        )

        router.onPacketReceived(peerB, packet)

        // Local delivery occurred
        assertThat(deliveredPackets.size).isEqualTo(1)

        // ACK was sent back to peerB
        assertThat(transport.sentTransmissions.size).isEqualTo(1)
        val (destPeer, sentPacket) = transport.sentTransmissions[0]
        assertThat(destPeer).isEqualTo(peerB)
        assertThat(sentPacket.type).isEqualTo(PacketType.ACK)
        assertThat(sentPacket.payload.toByteArray()).isEqualTo(packet.msgId.toByteArray())
    }

    @Test
    fun `relayed packets are dropped when forward rate limit is exceeded`() {
        val limiter = mesh.delivery.RateLimiter(maxTokens = 1.0, refillRatePerSec = 0.5, clock = clock)
        val rateLimitedRouter = Router(
            localNodeId = localNodeId,
            transport = transport,
            clock = clock,
            forwardRateLimiter = limiter
        )

        val dropped = mutableListOf<Pair<Packet, DropReason>>()
        rateLimitedRouter.addListener(object : RouterListener {
            override fun onPacketDropped(packet: Packet, reason: DropReason) {
                dropped.add(Pair(packet, reason))
            }
        })

        val transportB = FakeTransport(peerB)
        val transportC = FakeTransport(peerC)
        transport.link(transportB)
        transport.link(transportC)

        val p1 = PacketFactory.createData(origin = peerB, dest = peerD, payload = "p1".toByteArray(), ttl = 4)
        val p2 = PacketFactory.createData(origin = peerB, dest = peerD, payload = "p2".toByteArray(), ttl = 4)

        // First packet relays successfully (burst=1.0 consumed)
        rateLimitedRouter.onPacketReceived(peerB, p1)
        assertThat(dropped.isEmpty()).isEqualTo(true)
        assertThat(rateLimitedRouter.rateLimitedDrops).isEqualTo(0L)

        // Second packet arrives immediately: bucket exhausted -> dropped due to rate limiting
        rateLimitedRouter.onPacketReceived(peerB, p2)
        assertThat(dropped.size).isEqualTo(1)
        assertThat(dropped[0].second).isEqualTo(DropReason.RATE_LIMITED)
        assertThat(rateLimitedRouter.rateLimitedDrops).isEqualTo(1L)
    }
}
