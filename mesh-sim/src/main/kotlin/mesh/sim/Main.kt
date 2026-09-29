package mesh.sim

import mesh.protocol.destNodeId
import mesh.protocol.originNodeId
import mesh.sim.failure.FailureInjector
import mesh.sim.metrics.CsvExporter
import mesh.sim.metrics.MetricsCollector
import mesh.sim.topology.GridTopology
import mesh.sim.topology.LineTopology
import java.io.File

fun main(args: Array<String>) {
    val params = parseArgs(args)
    val scenario = params["scenario"] ?: "line"
    val hops = params["hops"]?.toIntOrNull() ?: 4
    val lossRate = params["lossRate"]?.toDoubleOrNull() ?: 0.0
    val outputDir = File(params["outputDir"] ?: "analysis/data")

    println("==========================================================")
    println(" Decentralized Emergency Mesh Network Simulator")
    println(" Scenario: $scenario | Hops/Size: $hops | LossRate: $lossRate")
    println(" Output Directory: ${outputDir.absolutePath}")
    println("==========================================================")

    val network = SimNetwork()
    val metrics = MetricsCollector()

    when (scenario.lowercase()) {
        "line" -> runLineScenario(network, metrics, hops, lossRate)
        "grid" -> runGridScenario(network, metrics, lossRate)
        "failure" -> runFailureScenario(network, metrics)
        "partition" -> runPartitionScenario(network, metrics)
        "dedup" -> runDedupScenario(network, metrics)
        else -> {
            println("Unknown scenario '$scenario'. Running line scenario by default.")
            runLineScenario(network, metrics, hops, lossRate)
        }
    }

    val summary = metrics.getSummary()
    println("\n----------------- Simulation Results -----------------")
    println("Total Messages Sent:        ${summary.totalSent}")
    println("Total Messages Delivered:   ${summary.totalDelivered}")
    println("Delivery Rate:              ${"%.1f".format(summary.deliveryRatePercent)}%")
    println("ACK Rate:                   ${"%.1f".format(summary.ackRatePercent)}%")
    println("Average Latency:            ${"%.1f".format(summary.averageLatencyMs)} ms (virtual time)")
    println("Average Hop Count:          ${"%.2f".format(summary.averageHopCount)} hops")
    println("Total Radio Transmissions:  ${summary.totalTransmissions}")
    println("Transmissions / Delivered:  ${"%.2f".format(summary.transmissionsPerDelivered)}")
    println("Duplicates Dropped:         ${summary.duplicatesDropped}")
    if (summary.convergenceTimeMs != null && summary.convergenceTimeMs > 0) {
        println("Routing Convergence Time:   ${summary.convergenceTimeMs} ms")
    }
    println("------------------------------------------------------")

    CsvExporter.exportAll(
        outputDir = outputDir,
        summary = summary,
        messages = metrics.getAllMessageMetrics(),
        convergenceEvents = metrics.getConvergenceEvents()
    )
    println("Wrote CSV metrics to ${outputDir.absolutePath}")
}

fun runLineScenario(network: SimNetwork, metrics: MetricsCollector, hops: Int, lossRate: Double) {
    val nodeCount = hops + 1
    val topo = LineTopology(network, nodeCount = nodeCount, linkLossRate = lossRate)
    for (entry in topo.nodes) {
        metrics.attachToNode(entry.node)
    }

    // Schedule periodic ticks for HELLO and outbox retries
    network.schedulePeriodicTicks(intervalMs = 1000L)

    val sender = topo.getNode(0)
    val receiverId = topo.getNodeId(hops)

    // Warm up routes with initial HELLOs
    network.runFor(2000L)

    // Send messages across the line
    for (i in 1..5) {
        network.schedule(delayMs = i * 2000L) {
            val packet = sender.send(receiverId, "Emergency payload #$i".toByteArray())
            metrics.recordSent(packet, network.clock.nowMs())
        }
    }

    network.runFor(15_000L)
}

fun runGridScenario(network: SimNetwork, metrics: MetricsCollector, lossRate: Double) {
    val grid = GridTopology(network, width = 3, height = 3, linkLossRate = lossRate)
    for (entry in grid.nodes) {
        metrics.attachToNode(entry.node)
    }

    network.schedulePeriodicTicks(intervalMs = 1000L)
    network.runFor(2000L)

    val src = grid.getNodeAt(0, 0)
    val dstId = grid.getNodeIdAt(2, 2)

    for (i in 1..5) {
        network.schedule(delayMs = i * 2000L) {
            val packet = src.send(dstId, "Grid data message #$i".toByteArray())
            metrics.recordSent(packet, network.clock.nowMs())
        }
    }

    network.runFor(20_000L)
}

fun runFailureScenario(network: SimNetwork, metrics: MetricsCollector) {
    val grid = GridTopology(network, width = 3, height = 3)
    for (entry in grid.nodes) {
        metrics.attachToNode(entry.node)
    }
    val injector = FailureInjector(network)

    network.schedulePeriodicTicks(intervalMs = 1000L)
    network.runFor(2000L)

    val src = grid.getNodeAt(0, 0)
    val dstId = grid.getNodeIdAt(2, 2)
    val centerNodeId = grid.getNodeIdAt(1, 1)

    // Message 1 traverses center
    val p1 = src.send(dstId, "Pre-failure packet".toByteArray())
    metrics.recordSent(p1, network.clock.nowMs())
    network.runFor(2000L)

    // Kill center relay at t = now
    val failureTime = network.clock.nowMs()
    metrics.recordFailureEvent(failureTime, "Kill center relay (1,1)")
    injector.killNode(centerNodeId)

    // Send messages during failure: network must route around center
    network.schedule(1000L) {
        val p2 = src.send(dstId, "Post-failure packet 1".toByteArray())
        metrics.recordSent(p2, network.clock.nowMs())
    }

    network.runFor(15_000L)
}

fun runPartitionScenario(network: SimNetwork, metrics: MetricsCollector) {
    val topo = LineTopology(network, nodeCount = 4)
    for (entry in topo.nodes) {
        metrics.attachToNode(entry.node)
    }
    val injector = FailureInjector(network)

    network.schedulePeriodicTicks(intervalMs = 1000L)
    network.runFor(2000L)

    val src = topo.getNode(0)
    val dstId = topo.getNodeId(3)

    // Sever link between 1 and 2
    val groupA = setOf(topo.getNodeId(0), topo.getNodeId(1))
    val groupB = setOf(topo.getNodeId(2), topo.getNodeId(3))

    injector.partition(groupA, groupB)

    // Enqueue message while partitioned (store-and-forward)
    val p = src.send(dstId, "Store-and-forward across partition".toByteArray())
    metrics.recordSent(p, network.clock.nowMs())

    network.runFor(5000L)

    // Heal partition
    injector.heal(groupA, groupB)

    network.runFor(15_000L)
}

fun runDedupScenario(network: SimNetwork, metrics: MetricsCollector) {
    val grid = GridTopology(network, width = 3, height = 3)
    for (entry in grid.nodes) {
        metrics.attachToNode(entry.node)
    }

    network.schedulePeriodicTicks(intervalMs = 1000L)
    network.runFor(2000L)

    val src = grid.getNodeAt(0, 0)
    val p = src.broadcast("Dedup test broadcast".toByteArray())
    metrics.recordSent(p, network.clock.nowMs())

    network.runFor(5000L)
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (arg in args) {
        val clean = arg.removePrefix("--")
        val split = clean.split("=", limit = 2)
        if (split.size == 2) {
            map[split[0].trim()] = split[1].trim()
        }
    }
    return map
}
