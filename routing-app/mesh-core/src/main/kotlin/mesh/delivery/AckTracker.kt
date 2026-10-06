package mesh.delivery

import mesh.protocol.Packet
import java.util.concurrent.ConcurrentHashMap

data class PendingAck(
    val packet: Packet,
    val onDelivered: ((ackPacket: Packet) -> Unit)?,
    val onExpired: (() -> Unit)?
)

/**
 * Correlates outgoing unicast messages with arriving ACK packets.
 */
class AckTracker {

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayKey) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val pendingAcks = ConcurrentHashMap<ByteArrayKey, PendingAck>()

    /**
     * Registers a message to await ACK confirmation.
     */
    fun track(
        packet: Packet,
        onDelivered: ((ackPacket: Packet) -> Unit)? = null,
        onExpired: (() -> Unit)? = null
    ) {
        val key = ByteArrayKey(packet.msgId.toByteArray())
        pendingAcks[key] = PendingAck(packet, onDelivered, onExpired)
    }

    /**
     * Evaluates whether an incoming packet is an ACK for a tracked message.
     *
     * @param ackPacket the received ACK packet whose payload contains the 16-byte acknowledged msg_id.
     * @return true if an active tracked message was matched and cleared
     */
    fun handleAck(ackPacket: Packet): Boolean {
        val ackedMsgId = ackPacket.payload.toByteArray()
        val key = ByteArrayKey(ackedMsgId)
        val pending = pendingAcks.remove(key) ?: return false
        pending.onDelivered?.invoke(ackPacket)
        return true
    }

    /**
     * Checks whether a message is currently pending ACK confirmation.
     */
    fun isAwaitingAck(msgId: ByteArray): Boolean {
        return pendingAcks.containsKey(ByteArrayKey(msgId))
    }

    /**
     * Cancels tracking for a message without invoking delivery callbacks.
     */
    fun cancel(msgId: ByteArray): Boolean {
        return pendingAcks.remove(ByteArrayKey(msgId)) != null
    }

    /**
     * Checks for messages that have expired and triggers expiration callbacks.
     */
    fun purgeExpired(nowMs: Long): Int {
        var expiredCount = 0
        val iterator = pendingAcks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs >= entry.value.packet.expiresAtMs) {
                iterator.remove()
                entry.value.onExpired?.invoke()
                expiredCount++
            }
        }
        return expiredCount
    }

    fun clear() {
        pendingAcks.clear()
    }
}
