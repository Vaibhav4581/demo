package mesh.delivery

import assertk.assertThat
import assertk.assertions.isEqualTo
import mesh.dedup.BloomFilter
import mesh.fakes.FakeClock
import mesh.fakes.FakeTransport
import mesh.protocol.NodeId
import mesh.protocol.PacketFactory
import mesh.storage.InMemoryMessageStore
import mesh.storage.MessageRecord
import org.junit.jupiter.api.Test

class AntiEntropyTest {

    private val nodeA = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val nodeB = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))

    @Test
    fun `identifies missing stored messages and transmits them to peer`() {
        val clock = FakeClock(1000L)
        val transportA = FakeTransport(nodeA)
        val transportB = FakeTransport(nodeB)
        transportA.link(transportB)

        val storeA = InMemoryMessageStore()
        val aeA = AntiEntropyManager(nodeA, transportA, storeA, clock)

        // Node A holds msg1 and msg2
        val msg1 = PacketFactory.createData(origin = nodeA, dest = null, payload = "Msg1".toByteArray())
        val msg2 = PacketFactory.createData(origin = nodeA, dest = null, payload = "Msg2".toByteArray())

        storeA.saveMessage(
            MessageRecord(
                msgId = msg1.msgId.toByteArray(),
                origin = nodeA,
                dest = null,
                payload = "Msg1".toByteArray(),
                createdAtMs = 1000L,
                expiresAtMs = 10_000L,
                deliveryState = DeliveryState.DELIVERED,
                isIncoming = false
            )
        )
        storeA.saveMessage(
            MessageRecord(
                msgId = msg2.msgId.toByteArray(),
                origin = nodeA,
                dest = null,
                payload = "Msg2".toByteArray(),
                createdAtMs = 1000L,
                expiresAtMs = 10_000L,
                deliveryState = DeliveryState.DELIVERED,
                isIncoming = false
            )
        )

        // Peer B sends SYNC_SUMMARY holding ONLY msg1
        val bloomB = BloomFilter(expectedInsertions = 100, fpp = 0.01)
        bloomB.insert(msg1.msgId.toByteArray())

        val syncPacketFromB = PacketFactory.createSyncSummary(
            origin = nodeB,
            dest = nodeA,
            bloomFilterBytes = bloomB.toByteArray()
        )

        val syncCount = aeA.onSyncSummaryReceived(nodeB, syncPacketFromB)

        // Exactly 1 message (msg2) was missing in B and synchronized by A
        assertThat(syncCount).isEqualTo(1)
        val transmissions = transportA.sentTransmissions
        assertThat(transmissions.size).isEqualTo(1)
        assertThat(transmissions[0].second.msgId.toByteArray()).isEqualTo(msg2.msgId.toByteArray())
    }
}
