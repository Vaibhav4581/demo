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
}
