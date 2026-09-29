package mesh.sim.metrics

import java.io.File

/**
 * Exports collected simulation metrics to structured CSV files for Python data analysis.
 */
object CsvExporter {

    fun exportAll(
        outputDir: File,
        summary: SimMetricsSummary,
        messages: List<MessageMetric>,
        convergenceEvents: List<ConvergenceEvent>
    ) {
        outputDir.mkdirs()

        // 1. Export summary
        val summaryFile = File(outputDir, "summary.csv")
        summaryFile.bufferedWriter().use { writer ->
            writer.write("metric,value\n")
            writer.write("totalSent,${summary.totalSent}\n")
            writer.write("totalDelivered,${summary.totalDelivered}\n")
            writer.write("totalAcked,${summary.totalAcked}\n")
            writer.write("deliveryRatePercent,${"%.2f".format(summary.deliveryRatePercent)}\n")
            writer.write("ackRatePercent,${"%.2f".format(summary.ackRatePercent)}\n")
            writer.write("averageLatencyMs,${"%.2f".format(summary.averageLatencyMs)}\n")
            writer.write("averageHopCount,${"%.2f".format(summary.averageHopCount)}\n")
            writer.write("totalTransmissions,${summary.totalTransmissions}\n")
            writer.write("transmissionsPerDelivered,${"%.2f".format(summary.transmissionsPerDelivered)}\n")
            writer.write("duplicatesDropped,${summary.duplicatesDropped}\n")
            writer.write("convergenceTimeMs,${summary.convergenceTimeMs ?: -1}\n")
        }

        // 2. Export detailed messages
        val messagesFile = File(outputDir, "messages.csv")
        messagesFile.bufferedWriter().use { writer ->
            writer.write("msg_id,origin,dest,sent_time_ms,delivered_time_ms,ack_time_ms,latency_ms,hop_count,is_delivered,is_acked\n")
            for (m in messages) {
                writer.write("${m.msgIdHex},${m.origin},${m.dest ?: "broadcast"},${m.sentTimeMs},${m.deliveredTimeMs ?: ""},${m.ackTimeMs ?: ""},${m.latencyMs ?: ""},${m.hopCount ?: ""},${m.isDelivered},${m.isAcked}\n")
            }
        }

        // 3. Export convergence events
        val convergenceFile = File(outputDir, "convergence.csv")
        convergenceFile.bufferedWriter().use { writer ->
            writer.write("failure_time_ms,description,recovered_time_ms,convergence_time_ms\n")
            for (c in convergenceEvents) {
                writer.write("${c.failureTimeMs},\"${c.description}\",${c.recoveredTimeMs ?: ""},${c.convergenceTimeMs ?: ""}\n")
            }
        }
    }
}
