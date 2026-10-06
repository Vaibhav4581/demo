package mesh.sim.metrics

import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.destNodeId
import mesh.protocol.originNodeId
import mesh.routing.DropReason
import mesh.routing.RouterListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class MessageMetric(
    val msgIdHex: String,
    val origin: NodeId,
    val dest: NodeId?,
    val sentTimeMs: Long,
    var deliveredTimeMs: Long? = null,
    var ackTimeMs: Long? = null,
    var hopCount: Int? = null,
    var isDelivered: Boolean = false,
    var isAcked: Boolean = false
) {
    val latencyMs: Long?
        get() = ackTimeMs?.let { it - sentTimeMs } ?: deliveredTimeMs?.let { it - sentTimeMs }
}

data class ConvergenceEvent(
    val failureTimeMs: Long,
    val description: String,
    var recoveredTimeMs: Long? = null
) {
    val convergenceTimeMs: Long?
        get() = recoveredTimeMs?.let { it - failureTimeMs }
}

data class SimMetricsSummary(
    val totalSent: Int,
    val totalDelivered: Int,
    val totalAcked: Int,
    val deliveryRatePercent: Double,
    val ackRatePercent: Double,
    val averageLatencyMs: Double,
    val averageHopCount: Double,
    val totalTransmissions: Long,
    val transmissionsPerDelivered: Double,
    val duplicatesDropped: Long,
    val convergenceTimeMs: Long?
)

/**
 * Collects message latencies, hop counts, transmission overhead, and convergence timing.
 */
class MetricsCollector {

    private val messages = ConcurrentHashMap<String, MessageMetric>()
    private val convergenceEvents = mutableListOf<ConvergenceEvent>()

    private val _totalTransmissions = AtomicLong(0)
    private val _duplicatesDropped = AtomicLong(0)

    val totalTransmissions: Long
        get() = _totalTransmissions.get()

    val duplicatesDropped: Long
        get() = _duplicatesDropped.get()

    fun recordSent(packet: Packet, sentTimeMs: Long) {
        val hex = packet.msgId.toByteArray().joinToString("") { "%02x".format(it) }
        messages[hex] = MessageMetric(
            msgIdHex = hex,
            origin = packet.originNodeId,
            dest = packet.destNodeId,
            sentTimeMs = sentTimeMs
        )
    }

    fun recordDelivered(packet: Packet, deliveredTimeMs: Long) {
        val hex = packet.msgId.toByteArray().joinToString("") { "%02x".format(it) }
        val metric = messages[hex]
        if (metric != null) {
            metric.isDelivered = true
            metric.deliveredTimeMs = deliveredTimeMs
            metric.hopCount = packet.hopCount + 1
        }

        // Check active convergence tracking
        checkConvergenceRecovery(deliveredTimeMs)
    }

    fun recordAck(ackedMsgIdHex: String, ackTimeMs: Long) {
        val metric = messages[ackedMsgIdHex]
        if (metric != null) {
            metric.isAcked = true
            metric.ackTimeMs = ackTimeMs
        }
        checkConvergenceRecovery(ackTimeMs)
    }

    fun recordTransmission() {
        _totalTransmissions.incrementAndGet()
    }

    fun recordDuplicateDropped() {
        _duplicatesDropped.incrementAndGet()
    }

    fun recordFailureEvent(timeMs: Long, description: String) {
        convergenceEvents.add(ConvergenceEvent(timeMs, description))
    }

    private fun checkConvergenceRecovery(nowMs: Long) {
        for (event in convergenceEvents) {
            if (event.recoveredTimeMs == null && nowMs > event.failureTimeMs) {
                event.recoveredTimeMs = nowMs
            }
        }
    }

    fun attachToNode(node: MeshNode) {
        node.router.addListener(object : RouterListener {
            override fun onLocalDelivery(packet: Packet) {
                recordDelivered(packet, node.clock.nowMs())
            }

            override fun onAckReceived(packet: Packet) {
                val ackedHex = packet.payload.toByteArray().joinToString("") { "%02x".format(it) }
                recordAck(ackedHex, node.clock.nowMs())
            }

            override fun onPacketRelayed(packet: Packet, nextHop: NodeId) {
                recordTransmission()
            }

            override fun onPacketDropped(packet: Packet, reason: DropReason) {
                if (reason == DropReason.DUPLICATE) {
                    recordDuplicateDropped()
                }
            }
        })
    }

    fun getSummary(): SimMetricsSummary {
        val allMetrics = messages.values.toList()
        val totalSent = allMetrics.size
        val delivered = allMetrics.filter { it.isDelivered }
        val acked = allMetrics.filter { it.isAcked }

        val deliveryRate = if (totalSent > 0) (delivered.size.toDouble() / totalSent) * 100.0 else 0.0
        val ackRate = if (totalSent > 0) (acked.size.toDouble() / totalSent) * 100.0 else 0.0

        val latencies = allMetrics.mapNotNull { it.latencyMs }
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0

        val hops = delivered.mapNotNull { it.hopCount }
        val avgHops = if (hops.isNotEmpty()) hops.average() else 0.0

        val txPerDelivered = if (delivered.isNotEmpty()) {
            _totalTransmissions.get().toDouble() / delivered.size
        } else {
            0.0
        }

        val firstConvergence = convergenceEvents.firstOrNull { it.convergenceTimeMs != null }?.convergenceTimeMs

        return SimMetricsSummary(
            totalSent = totalSent,
            totalDelivered = delivered.size,
            totalAcked = acked.size,
            deliveryRatePercent = deliveryRate,
            ackRatePercent = ackRate,
            averageLatencyMs = avgLatency,
            averageHopCount = avgHops,
            totalTransmissions = _totalTransmissions.get(),
            transmissionsPerDelivered = txPerDelivered,
            duplicatesDropped = _duplicatesDropped.get(),
            convergenceTimeMs = firstConvergence
        )
    }

    fun getAllMessageMetrics(): List<MessageMetric> = messages.values.toList()
    fun getConvergenceEvents(): List<ConvergenceEvent> = convergenceEvents.toList()

    fun clear() {
        messages.clear()
        convergenceEvents.clear()
        _totalTransmissions.set(0)
        _duplicatesDropped.set(0)
    }
}
