package mesh.delivery

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import mesh.fakes.FakeClock
import mesh.fakes.FakeTransport
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.routing.Router
import mesh.storage.InMemoryMessageStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OutboxTest {

    private val localNodeId = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val peerB = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
    private val peerC = NodeId(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0))

    private lateinit var clock: FakeClock
    private lateinit var transport: FakeTransport
    private lateinit var router: Router
    private lateinit var store: InMemoryMessageStore
    private lateinit var outbox: Outbox

    @BeforeEach
    fun setup() {
        clock = FakeClock(1_000L)
        transport = FakeTransport(localNodeId)
        router = Router(localNodeId, transport, clock)
        store = InMemoryMessageStore()
        outbox = Outbox(router, store, clock)
    }

    @Test
    fun `message queued with no neighbours is sent after a peer-up event`() {
        val packet = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "Offline message".toByteArray()
        )

        // 1. Enqueue with no peers connected
        val immediateSent = outbox.enqueue(packet)
        assertThat(immediateSent).isEqualTo(false)
        assertThat(outbox.getPendingCount()).isEqualTo(1)
        assertThat(store.getMessage(packet.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.QUEUED)

        // 2. Peer B connects
        val transportB = FakeTransport(peerB)
        transport.link(transportB)
        outbox.onPeerConnected(peerB)

        // 3. Message should now have been forwarded over peerB!
        assertThat(transport.sentTransmissions.size).isEqualTo(1)
        assertThat(transport.sentTransmissions[0].first).isEqualTo(peerB)
        assertThat(store.getMessage(packet.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.SENT)
    }

    @Test
    fun `retransmission follows backoff schedule and ACK cancels retries`() {
        val transportB = FakeTransport(peerB)
        transport.link(transportB)

        val packet = PacketFactory.createData(
            origin = localNodeId,
            dest = peerB,
            payload = "Needs ACK".toByteArray(),
            createdAtMs = clock.nowMs()
        )

        var deliveredConfirmed = false
        outbox.enqueue(packet, onDelivered = {
            deliveredConfirmed = true
        })

        // Initial transmission completed
        assertThat(transport.sentTransmissions.size).isEqualTo(1)

        // Initial backoff delay is 5,000ms
        clock.advance(4_999L)
        val retriedEarly = outbox.processRetries()
        assertThat(retriedEarly).isEqualTo(0)
        assertThat(transport.sentTransmissions.size).isEqualTo(1)

        // At 5,000ms, first retry triggers
        clock.advance(1L)
        val retriedAt5s = outbox.processRetries()
        assertThat(retriedAt5s).isEqualTo(1)
        assertThat(transport.sentTransmissions.size).isEqualTo(2)

        // Next backoff is 15,000ms (at t = 5000 + 15000 = 20000ms)
        clock.advance(14_999L)
        assertThat(outbox.processRetries()).isEqualTo(0)

        // ACK arrives at t = 19,999ms
        val ackPacket = PacketFactory.createAck(
            origin = peerB,
            dest = localNodeId,
            ackedMsgId = packet.msgId.toByteArray()
        )
        outbox.onAckReceived(ackPacket)

        assertThat(deliveredConfirmed).isTrue()
        assertThat(outbox.getPendingCount()).isEqualTo(0)
        assertThat(store.getMessage(packet.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.DELIVERED)

        // Advance to 20,000ms: retries were cancelled by ACK, so no further transmission happens
        clock.advance(1L)
        assertThat(outbox.processRetries()).isEqualTo(0)
        assertThat(transport.sentTransmissions.size).isEqualTo(2)
    }

    @Test
    fun `outbox bounds capacity and evicts oldest pending message when limit reached`() {
        val boundedOutbox = Outbox(router, store, clock, maxCapacity = 2)

        var expiredP1 = false
        val p1 = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "Packet 1".toByteArray(),
            createdAtMs = 1_000L
        )
        val p2 = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "Packet 2".toByteArray(),
            createdAtMs = 2_000L
        )
        val p3 = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "Packet 3".toByteArray(),
            createdAtMs = 3_000L
        )

        boundedOutbox.enqueue(p1, onExpired = { expiredP1 = true })
        boundedOutbox.enqueue(p2)
        assertThat(boundedOutbox.getPendingCount()).isEqualTo(2)
        assertThat(boundedOutbox.totalEvictions).isEqualTo(0)

        // Enqueuing p3 exceeds maxCapacity=2 -> p1 (createdAt=1000) should be evicted
        boundedOutbox.enqueue(p3)

        assertThat(boundedOutbox.getPendingCount()).isEqualTo(2)
        assertThat(boundedOutbox.totalEvictions).isEqualTo(1)
        assertThat(expiredP1).isTrue()
        assertThat(store.getMessage(p1.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.EXPIRED)
        assertThat(store.getMessage(p2.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.QUEUED)
        assertThat(store.getMessage(p3.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.QUEUED)
    }

    @Test
    fun `outbox purges expired messages before evicting unexpired pending ones`() {
        val boundedOutbox = Outbox(router, store, clock, maxCapacity = 2)

        val p1Expired = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "P1 Expired".toByteArray(),
            createdAtMs = 1_000L,
            expiresAtMs = 1_500L
        )
        val p2Active = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "P2 Active".toByteArray(),
            createdAtMs = 2_000L,
            expiresAtMs = 10_000L
        )
        val p3Active = PacketFactory.createData(
            origin = localNodeId,
            dest = peerC,
            payload = "P3 Active".toByteArray(),
            createdAtMs = 3_000L,
            expiresAtMs = 10_000L
        )

        boundedOutbox.enqueue(p1Expired)
        boundedOutbox.enqueue(p2Active)

        // Advance clock past p1 expiration time
        clock.advance(1_000L) // now = 2_000L, p1 is expired

        // Enqueue p3: p1 should be purged because it's expired, so p2 is NOT evicted
        boundedOutbox.enqueue(p3Active)

        assertThat(boundedOutbox.getPendingCount()).isEqualTo(2)
        assertThat(store.getMessage(p1Expired.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.EXPIRED)
        assertThat(store.getMessage(p2Active.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.QUEUED)
        assertThat(store.getMessage(p3Active.msgId.toByteArray())?.deliveryState).isEqualTo(DeliveryState.QUEUED)
        // Eviction count should be 0 because p1 was purged as expired, not evicted as capacity overflow
        assertThat(boundedOutbox.totalEvictions).isEqualTo(0)
    }
}

