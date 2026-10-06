package mesh.android.transport

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Type of transport event recorded by structured logging.
 */
enum class TransportEventType {
    PEER_CONNECTED,
    PEER_DISCONNECTED,
    PACKET_SENT,
    PACKET_RECEIVED,
    PACKET_BROADCAST,
    PACKET_REJECTED_OVERSIZE,
    CONNECTION_INITIATED,
    CONNECTION_FAILED,
    ADVERTISING_STARTED,
    ADVERTISING_FAILED,
    DISCOVERY_STARTED,
    DISCOVERY_FAILED
}

/**
 * Immutable structured log entry for transport events.
 */
data class TransportLogEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: TransportEventType,
    val peerIdHex: String? = null,
    val endpointId: String? = null,
    val packetSummary: String? = null,
    val details: String? = null
) {
    fun toFormattedString(): String {
        return buildString {
            append("[$timestampMs] ")
            append(eventType.name)
            if (peerIdHex != null) append(" peer=").append(peerIdHex)
            if (endpointId != null) append(" endpoint=").append(endpointId)
            if (packetSummary != null) append(" packet=[").append(packetSummary).append("]")
            if (details != null) append(" details=").append(details)
        }
    }
}

/**
 * Structured logger capturing all link-layer events with timestamps.
 * Maintains an in-memory buffer of recent events for diagnostics and evaluation export.
 */
class TransportLogger(private val maxBufferSize: Int = 1000) {

    private val eventHistory = ConcurrentLinkedDeque<TransportLogEvent>()

    fun log(event: TransportLogEvent) {
        eventHistory.addLast(event)
        while (eventHistory.size > maxBufferSize) {
            eventHistory.pollFirst()
        }

        val message = event.toFormattedString()
        when (event.eventType) {
            TransportEventType.PACKET_REJECTED_OVERSIZE,
            TransportEventType.CONNECTION_FAILED,
            TransportEventType.ADVERTISING_FAILED,
            TransportEventType.DISCOVERY_FAILED -> Log.w(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    fun getRecentEvents(): List<TransportLogEvent> {
        return eventHistory.toList()
    }

    fun clear() {
        eventHistory.clear()
    }

    companion object {
        private const val TAG = "MeshTransport"
    }
}
