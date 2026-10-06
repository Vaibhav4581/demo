package mesh.android.testmode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.transport.Clock
import mesh.transport.SystemClock
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Record of a message sent during an evaluation traffic run.
 */
data class TrafficRecord(
    val msgIdHex: String,
    val origin: String,
    val dest: String?,
    val sentTimeMs: Long,
    var deliveredTimeMs: Long? = null,
    var ackTimeMs: Long? = null,
    var latencyMs: Long? = null,
    var hopCount: Int? = null,
    var isDelivered: Boolean = false,
    var isAcked: Boolean = false
)

/**
 * Traffic generator that transmits N messages at a configured rate (interval in ms)
 * to a target node (or broadcast) to measure delivery rate, latency, and hop count.
 *
 * Latency is measured at the **sender** as `ackTimeMs - sentTimeMs` to avoid clock drift
 * between unsynchronised mobile devices.
 */
class TrafficGenerator(
    private val meshNode: MeshNode,
    private val clock: Clock = SystemClock,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val isRunning = AtomicBoolean(false)
    private var job: Job? = null

    private val records = ConcurrentHashMap<String, TrafficRecord>()
    private val orderedRecords = CopyOnWriteArrayList<TrafficRecord>()
    private val sentCounter = AtomicInteger(0)
    private val deliveredCounter = AtomicInteger(0)

    private val random = SecureRandom()

    init {
        // Register ACK delivery callback on the MeshNode
        meshNode.onDelivered { msgIdBytes: ByteArray, ackPacket: Packet ->
            val msgIdHex = msgIdBytes.joinToString("") { b -> "%02x".format(b) }
            val nowMs = clock.nowMs()
            records[msgIdHex]?.let { record ->
                record.isDelivered = true
                record.isAcked = true
                record.ackTimeMs = nowMs
                record.deliveredTimeMs = nowMs
                record.latencyMs = nowMs - record.sentTimeMs
                record.hopCount = ackPacket.hopCount
                deliveredCounter.incrementAndGet()
            }
        }
    }

    val running: Boolean get() = isRunning.get()
    val totalSent: Int get() = sentCounter.get()
    val totalDelivered: Int get() = deliveredCounter.get()

    /**
     * Starts generating traffic.
     *
     * @param dest recipient node ID (null for broadcast)
     * @param count number of messages to send
     * @param intervalMs delay between messages in milliseconds
     * @param payloadSizeBytes size of each generated random payload
     * @param onProgress callback invoked after each transmission with (sent, total)
     * @param onComplete callback invoked when all messages have been sent
     */
    fun startTraffic(
        dest: NodeId?,
        count: Int,
        intervalMs: Long,
        payloadSizeBytes: Int = 64,
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (!isRunning.compareAndSet(false, true)) return

        job = scope.launch {
            try {
                for (i in 1..count) {
                    if (!isActive || !isRunning.get()) break

                    val payload = ByteArray(payloadSizeBytes).also { random.nextBytes(it) }
                    val nowMs = clock.nowMs()

                    val packet: Packet = if (dest != null) {
                        meshNode.send(dest, payload)
                    } else {
                        meshNode.broadcast(payload)
                    }

                    val msgIdHex = packet.msgId.toByteArray().joinToString("") { "%02x".format(it) }
                    val record = TrafficRecord(
                        msgIdHex = msgIdHex,
                        origin = meshNode.nodeId.toHex(),
                        dest = dest?.toHex(),
                        sentTimeMs = nowMs
                    )

                    records[msgIdHex] = record
                    orderedRecords.add(record)
                    val sent = sentCounter.incrementAndGet()

                    onProgress?.invoke(sent, count)

                    if (i < count && intervalMs > 0) {
                        delay(intervalMs)
                    }
                }
            } finally {
                isRunning.set(false)
                onComplete?.invoke()
            }
        }
    }

    /**
     * Halts ongoing traffic generation.
     */
    fun stop() {
        isRunning.set(false)
        job?.cancel()
    }

    /**
     * Resets internal records and counters.
     */
    fun reset() {
        stop()
        records.clear()
        orderedRecords.clear()
        sentCounter.set(0)
        deliveredCounter.set(0)
    }

    /**
     * Returns a snapshot of all generated traffic records.
     */
    fun getRecords(): List<TrafficRecord> = orderedRecords.toList()
}
