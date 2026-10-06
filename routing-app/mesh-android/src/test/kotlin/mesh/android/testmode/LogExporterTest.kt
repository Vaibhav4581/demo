package mesh.android.testmode

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import mesh.android.transport.TransportEventType
import mesh.android.transport.TransportLogEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LogExporterTest {

    @Test
    fun `LogExporter produces valid CSV files matching simulator schema`(@TempDir tempDir: File) {
        val records = listOf(
            TrafficRecord(
                msgIdHex = "0102030405060708090a0b0c0d0e0f10",
                origin = "1111111111111111",
                dest = "2222222222222222",
                sentTimeMs = 1000L,
                deliveredTimeMs = 1250L,
                ackTimeMs = 1250L,
                latencyMs = 250L,
                hopCount = 3,
                isDelivered = true,
                isAcked = true
            )
        )

        val logEvents = listOf(
            TransportLogEvent(
                timestampMs = 1000L,
                eventType = TransportEventType.PACKET_SENT,
                peerIdHex = "2222222222222222",
                details = "Test packet"
            )
        )

        val outputDir = File(tempDir, "test_run")
        LogExporter.exportExperiment(
            outputDir = outputDir,
            trafficRecords = records,
            transportEvents = logEvents,
            duplicatesDropped = 5,
            retransmissions = 1
        )

        val messagesFile = File(outputDir, "messages.csv")
        val eventsFile = File(outputDir, "events.csv")
        val summaryFile = File(outputDir, "summary.csv")

        assertThat(messagesFile.exists()).isTrue()
        assertThat(eventsFile.exists()).isTrue()
        assertThat(summaryFile.exists()).isTrue()

        val messagesContent = messagesFile.readText()
        assertThat(messagesContent).contains("msg_id,origin,dest,sent_time_ms")
        assertThat(messagesContent).contains("0102030405060708090a0b0c0d0e0f10,1111111111111111,2222222222222222,1000,1250,1250,250,3,true,true")

        val summaryContent = summaryFile.readText()
        assertThat(summaryContent).contains("totalSent,1")
        assertThat(summaryContent).contains("totalDelivered,1")
        assertThat(summaryContent).contains("deliveryRatePercent,100.00")
        assertThat(summaryContent).contains("averageLatencyMs,250.00")
        assertThat(summaryContent).contains("duplicatesDropped,5")
    }
}
