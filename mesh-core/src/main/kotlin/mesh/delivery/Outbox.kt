package mesh.delivery

import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.destNodeId
import mesh.protocol.isBroadcast
import mesh.protocol.isExpired
import mesh.protocol.originNodeId
import mesh.routing.Router
import mesh.storage.MessageRecord
import mesh.storage.MessageStore
import mesh.transport.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class OutboxEntry(
    val packet: Packet,
    var attempt: Int = 0,
    var nextRetryTimeMs: Long = 0L,
    val onDelivered: ((ackPacket: Packet) -> Unit)? = null,
    val onExpired: (() -> Unit)? = null
)

/**
 * Store-and-forward outbox implementing retransmission, peer-connection flushing,
 * and bounded queue capacity with oldest-message eviction to prevent memory exhaustion.
 */
class Outbox(
    val router: Router,
    val messageStore: MessageStore,
    val clock: Clock,
    val ackTracker: AckTracker = AckTracker(),
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val maxCapacity: Int = DEFAULT_MAX_CAPACITY
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
    }

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayKey) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val entries = ConcurrentHashMap<ByteArrayKey, OutboxEntry>()
    private val _totalRetransmissions = AtomicLong(0)
    private val _totalEvictions = AtomicLong(0)

    val totalRetransmissions: Long
        get() = _totalRetransmissions.get()

    val totalEvictions: Long
        get() = _totalEvictions.get()

    /**
     * Enqueues a packet for transmission.
     *
     * If the outbox queue has reached [maxCapacity], expired packets are purged first.
     * If still at capacity, the oldest pending packet is evicted.
     * Persists the packet in [MessageStore] and attempts an initial forward.
     * If no physical neighbours are available, the message remains stored in [DeliveryState.QUEUED].
     */
    @Synchronized
    fun enqueue(
        packet: Packet,
        onDelivered: ((ackPacket: Packet) -> Unit)? = null,
        onExpired: (() -> Unit)? = null
    ): Boolean {
        val nowMs = clock.nowMs()
        val msgIdBytes = packet.msgId.toByteArray()
        val key = ByteArrayKey(msgIdBytes)

        // Bounded capacity enforcement
        if (!entries.containsKey(key) && entries.size >= maxCapacity) {
            purgeExpired(nowMs)
            if (entries.size >= maxCapacity) {
                evictOldest()
            }
        }

        // 1. Persist to storage (create message record if not already created)
        if (messageStore.getMessage(msgIdBytes) == null) {
            val record = MessageRecord(
                msgId = msgIdBytes,
                origin = packet.originNodeId,
                dest = packet.destNodeId,
                payload = packet.payload.toByteArray(),
                createdAtMs = packet.createdAtMs,
                expiresAtMs = packet.expiresAtMs,
                deliveryState = DeliveryState.QUEUED,
                isIncoming = false
            )
            messageStore.saveMessage(record)
        }
        messageStore.saveOutboxPacket(packet)

        val entry = OutboxEntry(
            packet = packet,
            attempt = 0,
            nextRetryTimeMs = nowMs,
            onDelivered = onDelivered,
            onExpired = onExpired
        )
        entries[key] = entry

        // 2. Direct messages register with ACK tracker
        if (!packet.isBroadcast) {
            ackTracker.track(packet, onDelivered = { ack ->
                handleAckConfirmed(msgIdBytes, ack)
            }, onExpired = {
                handleExpired(msgIdBytes)
            })
        }

        // 3. Attempt immediate forward
        val sent = router.forwardPacket(packet, incomingPeer = null)
        if (sent) {
            if (packet.isBroadcast) {
                // Broadcasts do not expect ACKs; complete immediately
                entries.remove(key)
                messageStore.removeOutboxPacket(msgIdBytes)
                messageStore.updateDeliveryState(msgIdBytes, DeliveryState.DELIVERED)
            } else {
                // Guard against synchronous transport where ACK already arrived during forwardPacket
                if (messageStore.getMessage(msgIdBytes)?.deliveryState != DeliveryState.DELIVERED) {
                    messageStore.updateDeliveryState(msgIdBytes, DeliveryState.SENT)
                    entry.attempt = 1
                    entry.nextRetryTimeMs = nowMs + retryPolicy.nextRetryDelayMs(0)
                }
            }
            return true
        } else {
            // No route or no neighbours: remains stored for store-and-forward
            messageStore.updateDeliveryState(msgIdBytes, DeliveryState.QUEUED)
            return false
        }
    }

    /**
     * Retransmits pending outbox packets when a new peer connects.
     */
    @Synchronized
    fun onPeerConnected(peer: NodeId) {
        val nowMs = clock.nowMs()
        for ((key, entry) in entries) {
            if (entry.packet.isExpired(nowMs)) {
                handleExpired(key.bytes)
                continue
            }
            val sent = router.forwardPacket(entry.packet, incomingPeer = null)
            if (sent) {
                if (entry.packet.isBroadcast) {
                    entries.remove(key)
                    messageStore.removeOutboxPacket(key.bytes)
                    messageStore.updateDeliveryState(key.bytes, DeliveryState.DELIVERED)
                } else {
                    if (messageStore.getMessage(key.bytes)?.deliveryState != DeliveryState.DELIVERED) {
                        messageStore.updateDeliveryState(key.bytes, DeliveryState.SENT)
                        entry.attempt = 1
                        entry.nextRetryTimeMs = nowMs + retryPolicy.nextRetryDelayMs(0)
                    }
                }
            }
        }
    }

    /**
     * Executes scheduled retransmissions according to exponential backoff.
     */
    @Synchronized
    fun processRetries(): Int {
        val nowMs = clock.nowMs()
        var retriedCount = 0

        for ((key, entry) in entries) {
            if (entry.packet.isExpired(nowMs)) {
                handleExpired(key.bytes)
                continue
            }

            if (nowMs >= entry.nextRetryTimeMs) {
                if (retryPolicy.canRetry(entry.attempt, entry.packet.expiresAtMs, nowMs)) {
                    val sent = router.forwardPacket(entry.packet, incomingPeer = null)
                    entry.attempt++
                    entry.nextRetryTimeMs = nowMs + retryPolicy.nextRetryDelayMs(entry.attempt)
                    if (sent) {
                        messageStore.updateDeliveryState(key.bytes, DeliveryState.SENT)
                        _totalRetransmissions.incrementAndGet()
                        retriedCount++
                    }
                } else {
                    // Retry budget exhausted or packet expired
                    handleExpired(key.bytes)
                }
            }
        }
        return retriedCount
    }

    /**
     * Called when an ACK arrives for a message ID.
     */
    @Synchronized
    fun onAckReceived(ackPacket: Packet) {
        ackTracker.handleAck(ackPacket)
    }

    /**
     * Purges all expired packets currently in the outbox queue.
     *
     * @return count of packets purged.
     */
    @Synchronized
    fun purgeExpired(nowMs: Long = clock.nowMs()): Int {
        val expiredKeys = entries.filter { it.value.packet.isExpired(nowMs) }.map { it.key.bytes }
        expiredKeys.forEach { handleExpired(it) }
        return expiredKeys.size
    }

    private fun evictOldest() {
        val oldest = entries.entries.minByOrNull { it.value.packet.createdAtMs } ?: return
        val oldestKeyBytes = oldest.key.bytes
        entries.remove(oldest.key)
        ackTracker.cancel(oldestKeyBytes)
        messageStore.removeOutboxPacket(oldestKeyBytes)
        messageStore.updateDeliveryState(oldestKeyBytes, DeliveryState.EXPIRED)
        oldest.value.onExpired?.invoke()
        _totalEvictions.incrementAndGet()
    }

    private fun handleAckConfirmed(msgIdBytes: ByteArray, ackPacket: Packet) {
        val key = ByteArrayKey(msgIdBytes)
        val entry = entries.remove(key)
        messageStore.removeOutboxPacket(msgIdBytes)
        messageStore.updateDeliveryState(msgIdBytes, DeliveryState.DELIVERED, ackPacket.hopCount)
        entry?.onDelivered?.invoke(ackPacket)
    }

    private fun handleExpired(msgIdBytes: ByteArray) {
        val key = ByteArrayKey(msgIdBytes)
        val entry = entries.remove(key)
        ackTracker.cancel(msgIdBytes)
        messageStore.removeOutboxPacket(msgIdBytes)
        messageStore.updateDeliveryState(msgIdBytes, DeliveryState.EXPIRED)
        entry?.onExpired?.invoke()
    }

    fun getPendingCount(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        ackTracker.clear()
        _totalRetransmissions.set(0)
        _totalEvictions.set(0)
    }
}
