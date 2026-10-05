package mesh.android.testmode

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mesh.transport.Clock
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.protocol.PacketType
import mesh.storage.InMemoryMessageStore
import mesh.transport.Transport
import mesh.transport.TransportListener
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficGeneratorTest {

    private class FakeClock(var timeMs: Long = 1000L) : Clock {
        override fun nowMs(): Long = timeMs
    }

    private class FakeTransport(override val localNodeId: NodeId) : Transport {
        val listeners = CopyOnWriteArrayList<TransportListener>()
        val sentPackets = CopyOnWriteArrayList<Packet>()

        override fun registerListener(listener: TransportListener) {
            listeners.add(listener)
        }

        override fun unregisterListener(listener: TransportListener) {
            listeners.remove(listener)
        }

        override fun send(dest: NodeId, packet: Packet): Boolean {
            sentPackets.add(packet)
            return true
        }

        override fun broadcast(packet: Packet): Int {
            sentPackets.add(packet)
            return 1
        }
    }

    private val localNodeId = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val peerNodeId = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))

    @Test
    fun `TrafficGenerator sends specified message count and measures latency on ACK`() = runTest {
        val testScope = TestScope(testScheduler)
        val clock = FakeClock(10_000L)
        val transport = FakeTransport(localNodeId)
        val meshNode = MeshNode(
            nodeId = localNodeId,
            transport = transport,
            clock = clock,
            messageStore = InMemoryMessageStore()
        )

        val generator = TrafficGenerator(
            meshNode = meshNode,
            clock = clock,
            scope = testScope
        )

        var completed = false
        generator.startTraffic(
            dest = peerNodeId,
            count = 3,
            intervalMs = 100L,
            payloadSizeBytes = 16,
            onComplete = { completed = true }
        )

        testScope.advanceUntilIdle()

        assertThat(completed).isTrue()
        assertThat(generator.totalSent).isEqualTo(3)
        val records = generator.getRecords()
        assertThat(records.size).isEqualTo(3)

        // Simulate ACK return for message 0 after 250ms
        clock.timeMs = 10_250L
        val msg0 = records[0]
        val ackPacket = PacketFactory.createAck(
            origin = peerNodeId,
            dest = localNodeId,
            ackedMsgId = msg0.msgIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            hopCount = 2,
            createdAtMs = clock.timeMs
        )

        // Trigger ACK delivery via transport listener
        transport.listeners.forEach { it.onPacketReceived(peerNodeId, ackPacket) }

        assertThat(msg0.isAcked).isTrue()
        assertThat(msg0.latencyMs).isNotNull()
        assertThat(msg0.latencyMs!!).isEqualTo(250L)
        assertThat(msg0.hopCount).isEqualTo(2)
        assertThat(generator.totalDelivered).isEqualTo(1)
    }
}
