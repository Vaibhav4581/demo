package mesh.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import mesh.delivery.DeliveryState
import mesh.sim.failure.FailureInjector
import mesh.sim.metrics.MetricsCollector
import mesh.sim.topology.GridTopology
import org.junit.jupiter.api.Test

class RelayFailureScenarioTest {

    @Test
    fun `in a grid with redundant paths kill a relay mid-run and verify convergence time is measured and non-zero`() {
        val network = SimNetwork()
        val metrics = MetricsCollector()
        val grid = GridTopology(network, width = 3, height = 3, linkLatencyMs = 10L)

        for (entry in grid.nodes) {
            metrics.attachToNode(entry.node)
        }
        val injector = FailureInjector(network)

        val src = grid.getNodeAt(0, 0)
        val dst = grid.getNodeAt(2, 2)
        val dstId = grid.getNodeIdAt(2, 2)
        val relayId = grid.getNodeIdAt(0, 1) // Critical path relay for top path

        // Warm up and verify baseline connectivity
        val p1 = src.send(dstId, "Pre-failure packet".toByteArray())
        metrics.recordSent(p1, network.clock.nowMs())
        network.runFor(500L)

        assertThat(src.getDeliveryState(p1.msgId.toByteArray())).isEqualTo(DeliveryState.DELIVERED)

        // Kill relay node mid-run at t = 1000ms
        network.runUntil(1000L)
        val failureTime = network.clock.nowMs()
        metrics.recordFailureEvent(failureTime, "Kill relay (0,1)")
        injector.killNode(relayId)

        // Advance a bit, then send new message at t = 1100ms
        network.runUntil(1100L)
        val p2 = src.send(dstId, "Post-failure packet through alternate path".toByteArray())
        metrics.recordSent(p2, network.clock.nowMs())

        // Run simulation until packet routes around the dead relay
        network.runFor(1000L)

        // Verify alternate path delivered packet 2 successfully
        assertThat(src.getDeliveryState(p2.msgId.toByteArray())).isEqualTo(DeliveryState.DELIVERED)
        val recordAtDst = dst.messageStore.getMessage(p2.msgId.toByteArray())
        assertThat(recordAtDst).isNotNull()

        // Verify convergence time was recorded and is strictly non-zero
        val convergenceEvents = metrics.getConvergenceEvents()
        assertThat(convergenceEvents.size).isGreaterThan(0)
        val event = convergenceEvents[0]
        assertThat(event.convergenceTimeMs).isNotNull()
        assertThat(event.convergenceTimeMs!!).isGreaterThan(0L)
    }
}
