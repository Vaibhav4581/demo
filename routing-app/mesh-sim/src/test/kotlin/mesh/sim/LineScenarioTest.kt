package mesh.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import mesh.delivery.DeliveryState
import mesh.protocol.Packet
import mesh.protocol.originNodeId
import mesh.sim.metrics.MetricsCollector
import mesh.sim.topology.LineTopology
import org.junit.jupiter.api.Test

class LineScenarioTest {

    @Test
    fun `Line A-B-C-D-E direct message delivers in 4 hops with an ACK back`() {
        val network = SimNetwork()
        val metrics = MetricsCollector()

        // 5 nodes: A (0), B (1), C (2), D (3), E (4) -> 4 hops between A and E
        val linkLatencyMs = 10L
        val topology = LineTopology(network, nodeCount = 5, linkLatencyMs = linkLatencyMs)

        for (entry in topology.nodes) {
            metrics.attachToNode(entry.node)
        }

        val nodeA = topology.getNode(0)
        val nodeE = topology.getNode(4)
        val idA = topology.getNodeId(0)
        val idE = topology.getNodeId(4)

        var deliveredPayloadAtE: ByteArray? = null
        nodeE.onMessageReceived { from, payload, _ ->
            if (from == idA) {
                deliveredPayloadAtE = payload
            }
        }

        var ackReceivedAtA = false
        nodeA.onDelivered { _, _ ->
            ackDelivered()
            ackReceivedAtA = true
        }

        // 1. Send direct unicast message from A to E
        val msgText = "Disaster alert from A to E"
        val packet = nodeA.send(idE, msgText.toByteArray())
        metrics.recordSent(packet, network.clock.nowMs())

        // 2. Run simulation forward in virtual time
        // Expected propagation: A->B (10ms) -> C (20ms) -> D (30ms) -> E (40ms)
        // ACK return: E->D (50ms) -> C (60ms) -> B (70ms) -> A (80ms)
        network.runFor(500L)

        // 3. Verify delivery at E
        val messageRecordAtE = nodeE.messageStore.getMessage(packet.msgId.toByteArray())
        assertThat(messageRecordAtE).isNotNull()
        assertThat(String(messageRecordAtE!!.payload)).isEqualTo(msgText)

        // 4. Verify hop count at destination is exactly 4
        val metricAtE = metrics.getAllMessageMetrics().firstOrNull { it.msgIdHex == packet.msgId.toByteArray().joinToString("") { b -> "%02x".format(b) } }
        assertThat(metricAtE).isNotNull()
        assertThat(metricAtE!!.hopCount).isEqualTo(4)

        // 5. Verify ACK returned to A and A's delivery state is DELIVERED
        assertThat(ackReceivedAtA).isTrue()
        assertThat(nodeA.getDeliveryState(packet.msgId.toByteArray())).isEqualTo(DeliveryState.DELIVERED)

        // 6. Verify latency is measured and non-zero (approx 80ms)
        assertThat(metricAtE.latencyMs).isNotNull()
        assertThat(metricAtE.latencyMs!!).isEqualTo(80L)
    }

    private fun ackDelivered() {}
}
