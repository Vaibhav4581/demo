package mesh.android.testmode

import android.content.Context
import mesh.android.transport.TransportLogEvent
import java.io.File

/**
 * Exports collected on-device experiment metrics and link-layer event logs
 * to standard CSV files matching the simulator's format.
 *
 * Files are saved to app storage (e.g. `/sdcard/Android/data/<package>/files/experiments/`)
 * and can be pulled via:
 * ```bash
 * adb pull /sdcard/Android/data/org.mesh.emergency/files/experiments/ ./analysis/data/
 * ```
 */
object LogExporter {

    fun getDefaultOutputDir(context: Context): File {
        val baseDir = context.getExternalFilesDir("experiments") ?: File(context.filesDir, "experiments")
        val timestamp = System.currentTimeMillis()
        val runDir = File(baseDir, "run_$timestamp")
        runDir.mkdirs()
        return runDir
    }

    /**
     * Exports traffic records, link events, and summary metrics to [outputDir].
     */
    fun exportExperiment(
        outputDir: File,
        trafficRecords: List<TrafficRecord>,
        transportEvents: List<TransportLogEvent> = emptyList(),
        duplicatesDropped: Long = 0,
        retransmissions: Long = 0
    ): File {
        outputDir.mkdirs()

        // 1. Export messages.csv (matches simulator schema)
        val messagesFile = File(outputDir, "messages.csv")
        messagesFile.bufferedWriter().use { writer ->
            writer.write("msg_id,origin,dest,sent_time_ms,delivered_time_ms,ack_time_ms,latency_ms,hop_count,is_delivered,is_acked\n")
            for (r in trafficRecords) {
                writer.write("${r.msgIdHex},${r.origin},${r.dest ?: "broadcast"},${r.sentTimeMs},${r.deliveredTimeMs ?: ""},${r.ackTimeMs ?: ""},${r.latencyMs ?: ""},${r.hopCount ?: ""},${r.isDelivered},${r.isAcked}\n")
            }
        }

        // 2. Export events.csv (transport link layer logs)
        val eventsFile = File(outputDir, "events.csv")
        eventsFile.bufferedWriter().use { writer ->
            writer.write("timestamp_ms,event_type,peer_id,endpoint_id,packet_summary,details\n")
            for (e in transportEvents) {
                val cleanDetails = e.details?.replace("\"", "\"\"") ?: ""
                writer.write("${e.timestampMs},${e.eventType.name},${e.peerIdHex ?: ""},${e.endpointId ?: ""},\"${e.packetSummary ?: ""}\",\"$cleanDetails\"\n")
            }
        }

        // 3. Export summary.csv
        val totalSent = trafficRecords.size
        val totalDelivered = trafficRecords.count { it.isDelivered }
        val totalAcked = trafficRecords.count { it.isAcked }
        val deliveryRate = if (totalSent > 0) (totalDelivered.toDouble() / totalSent) * 100.0 else 0.0
        val ackRate = if (totalSent > 0) (totalAcked.toDouble() / totalSent) * 100.0 else 0.0

        val latencies = trafficRecords.mapNotNull { it.latencyMs }
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0

        val hops = trafficRecords.mapNotNull { it.hopCount }
        val avgHop = if (hops.isNotEmpty()) hops.average() else 0.0

        val summaryFile = File(outputDir, "summary.csv")
        summaryFile.bufferedWriter().use { writer ->
            writer.write("metric,value\n")
            writer.write("totalSent,$totalSent\n")
            writer.write("totalDelivered,$totalDelivered\n")
            writer.write("totalAcked,$totalAcked\n")
            writer.write("deliveryRatePercent,${"%.2f".format(deliveryRate)}\n")
            writer.write("ackRatePercent,${"%.2f".format(ackRate)}\n")
            writer.write("averageLatencyMs,${"%.2f".format(avgLatency)}\n")
            writer.write("averageHopCount,${"%.2f".format(avgHop)}\n")
            writer.write("duplicatesDropped,$duplicatesDropped\n")
            writer.write("retransmissions,$retransmissions\n")
        }

        return outputDir
    }
}
