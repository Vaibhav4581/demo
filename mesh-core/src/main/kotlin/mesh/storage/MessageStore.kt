package mesh.storage

import mesh.delivery.DeliveryState
import mesh.protocol.NodeId
import mesh.protocol.Packet

/**
 * Domain entity representing a stored message (incoming or outgoing).
 */
data class MessageRecord(
    val msgId: ByteArray,
    val origin: NodeId,
    val dest: NodeId?,
    val payload: ByteArray,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val deliveryState: DeliveryState,
    val isIncoming: Boolean,
    val hopCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageRecord) return false
        return msgId.contentEquals(other.msgId)
    }

    override fun hashCode(): Int = msgId.contentHashCode()
}

/**
 * Storage interface for persisting messages, outbox packets, and delivery states.
 */
interface MessageStore {
    /**
     * Persists or updates a message record.
     */
    fun saveMessage(record: MessageRecord)

    /**
     * Retrieves a stored message by its 16-byte [msgId].
     */
    fun getMessage(msgId: ByteArray): MessageRecord?

    /**
     * Retrieves all stored messages.
     */
    fun getAllMessages(): List<MessageRecord>

    /**
     * Updates the delivery state of a stored message, optionally updating hop count.
     */
    fun updateDeliveryState(msgId: ByteArray, state: DeliveryState, hopCount: Int? = null)

    /**
     * Returns a list of 16-byte message IDs currently held in storage (for anti-entropy reconciliation).
     */
    fun getHeldMessageIds(): List<ByteArray>

    /**
     * Saves a packet to the store-and-forward outbox.
     */
    fun saveOutboxPacket(packet: Packet)

    /**
     * Removes a packet from the store-and-forward outbox once delivered or expired.
     */
    fun removeOutboxPacket(msgId: ByteArray)

    /**
     * Retrieves all pending outbox packets.
     */
    fun getOutboxPackets(): List<Packet>

    /**
     * Deletes expired messages and outbox packets relative to [nowMs].
     */
    fun purgeExpired(nowMs: Long): Int
}
