package mesh.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import mesh.delivery.DeliveryState
import mesh.sim.failure.FailureInjector
import mesh.sim.metrics.MetricsCollector
import mesh.sim.topology.LineTopology
import org.junit.jupiter.api.Test

class PartitionAndHealScenarioTest {

    @Test
    fun `partition network queue messages and reconnect verifying store-and-forward delivery`() {
        val network = SimNetwork()
        val metrics = MetricsCollector()
        val topology = LineTopology(network, nodeCount = 4, linkLatencyMs = 10L)

        for (entry in topology.nodes) {
            metrics.attachToNode(entry.node)
        }
        val injector = FailureInjector(network)

        val node0 = topology.getNode(0)
        val node3 = topology.getNode(3)
        val id3 = topology.getNodeId(3)

        // 1. Partition the network: groupA = {0, 1}, groupB = {2, 3}
        val groupA = setOf(topology.getNodeId(0), topology.getNodeId(1))
        val groupB = setOf(topology.getNodeId(2), topology.getNodeId(3))

        injector.partition(groupA, groupB)

        // 2. Queue message from Node 0 to Node 3 during partition
        val messageText = "Message sent during partition"
        val packet = node0.send(id3, messageText.toByteArray())
        metrics.recordSent(packet, network.clock.nowMs())

        // Run simulation while partitioned
        network.runFor(2000L)

        // Verify message has NOT reached Node 3 yet
        val recordBeforeHeal = node3.messageStore.getMessage(packet.msgId.toByteArray())
        assertThat(recordBeforeHeal).isEqualTo(null)

        // 3. Heal the partition: restore link between node 1 and node 2
        injector.heal(groupA, groupB, latencyMs = 10L)

        // Run simulation to let store-and-forward outbox flush upon peer connection
        network.runFor(5000L)

        // 4. Verify message is delivered to Node 3
        val recordAfterHeal = node3.messageStore.getMessage(packet.msgId.toByteArray())
        assertThat(recordAfterHeal).isNotNull()
        assertThat(String(recordAfterHeal!!.payload)).isEqualTo(messageText)

        // 5. Verify ACK returned to Node 0 and delivery state is DELIVERED
        assertThat(node0.getDeliveryState(packet.msgId.toByteArray())).isEqualTo(DeliveryState.DELIVERED)
    }
}
