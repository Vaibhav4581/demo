package mesh.sim

import assertk.assertThat
import assertk.assertions.isGreaterThan
import mesh.sim.metrics.MetricsCollector
import mesh.sim.topology.GridTopology
import org.junit.jupiter.api.Test

class DedupComparisonScenarioTest {

    @Test
    fun `with dedup off transmissions per delivered message are measurably higher than with dedup on`() {
        // Run 1: Deduplication ON
        val netOn = SimNetwork()
        val metricsOn = MetricsCollector()
        val gridOn = GridTopology(netOn, width = 3, height = 3, linkLatencyMs = 10L)
        for (entry in gridOn.nodes) {
            entry.node.router.dedupManager.enabled = true
            metricsOn.attachToNode(entry.node)
        }
        val srcOn = gridOn.getNodeAt(0, 0)
        val pOn = srcOn.broadcast("Broadcast with dedup ON".toByteArray())
        metricsOn.recordSent(pOn, netOn.clock.nowMs())
        netOn.runFor(5000L)

        val txOn = netOn.totalTransmissionsAttempted

        // Run 2: Deduplication OFF
        val netOff = SimNetwork()
        val metricsOff = MetricsCollector()
        val gridOff = GridTopology(netOff, width = 3, height = 3, linkLatencyMs = 10L)
        for (entry in gridOff.nodes) {
            entry.node.router.dedupManager.enabled = false
            metricsOff.attachToNode(entry.node)
        }
        val srcOff = gridOff.getNodeAt(0, 0)
        val pOff = srcOff.broadcast("Broadcast with dedup OFF".toByteArray())
        metricsOff.recordSent(pOff, netOff.clock.nowMs())
        netOff.runFor(5000L)

        val txOff = netOff.totalTransmissionsAttempted

        // Acceptance criteria: with dedup off, transmissions are measurably higher
        assertThat(txOff).isGreaterThan(txOn)
        assertThat(metricsOff.getSummary().transmissionsPerDelivered)
            .isGreaterThan(metricsOn.getSummary().transmissionsPerDelivered)
    }
}
