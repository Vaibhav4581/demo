package mesh.storage

import mesh.delivery.DeliveryState
import mesh.protocol.Packet
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory thread-safe implementation of [MessageStore] for unit testing and JVM simulation.
 */
class InMemoryMessageStore : MessageStore {

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayKey) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val messages = ConcurrentHashMap<ByteArrayKey, MessageRecord>()
    private val outbox = ConcurrentHashMap<ByteArrayKey, Packet>()

    @Synchronized
    override fun saveMessage(record: MessageRecord) {
        messages[ByteArrayKey(record.msgId)] = record
    }

    @Synchronized
    override fun getMessage(msgId: ByteArray): MessageRecord? {
        return messages[ByteArrayKey(msgId)]
    }

    @Synchronized
    override fun getAllMessages(): List<MessageRecord> {
        return messages.values.toList()
    }

    @Synchronized
    override fun updateDeliveryState(msgId: ByteArray, state: DeliveryState) {
        val key = ByteArrayKey(msgId)
        val existing = messages[key]
        if (existing != null) {
            messages[key] = existing.copy(deliveryState = state)
        }
    }

    @Synchronized
    override fun getHeldMessageIds(): List<ByteArray> {
        return messages.keys.map { it.bytes.clone() }
    }

    @Synchronized
    override fun saveOutboxPacket(packet: Packet) {
        outbox[ByteArrayKey(packet.msgId.toByteArray())] = packet
    }

    @Synchronized
    override fun removeOutboxPacket(msgId: ByteArray) {
        outbox.remove(ByteArrayKey(msgId))
    }

    @Synchronized
    override fun getOutboxPackets(): List<Packet> {
        return outbox.values.toList()
    }

    @Synchronized
    override fun purgeExpired(nowMs: Long): Int {
        var count = 0
        val outboxIterator = outbox.entries.iterator()
        while (outboxIterator.hasNext()) {
            val entry = outboxIterator.next()
            if (nowMs >= entry.value.expiresAtMs) {
                outboxIterator.remove()
                updateDeliveryState(entry.key.bytes, DeliveryState.EXPIRED)
                count++
            }
        }
        return count
    }

    fun clear() {
        messages.clear()
        outbox.clear()
    }
}
