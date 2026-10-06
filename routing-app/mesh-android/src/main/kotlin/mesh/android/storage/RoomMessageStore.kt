package mesh.android.storage

import mesh.android.storage.dao.MessageDao
import mesh.android.storage.dao.OutboxDao
import mesh.android.storage.entities.MessageEntity
import mesh.android.storage.entities.OutboxEntity
import mesh.delivery.DeliveryState
import mesh.protocol.Packet
import mesh.storage.MessageRecord
import mesh.storage.MessageStore

/**
 * Android implementation of [MessageStore] backed by encrypted Room persistence.
 */
class RoomMessageStore(
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao
) : MessageStore {

    constructor(database: MeshDatabase) : this(
        messageDao = database.messageDao(),
        outboxDao = database.outboxDao()
    )

    override fun saveMessage(record: MessageRecord) {
        messageDao.saveMessage(MessageEntity.fromDomain(record))
    }

    override fun getMessage(msgId: ByteArray): MessageRecord? {
        return messageDao.getMessage(msgId)?.toDomain()
    }

    override fun getAllMessages(): List<MessageRecord> {
        return messageDao.getAllMessages().map { it.toDomain() }
    }

    override fun updateDeliveryState(msgId: ByteArray, state: DeliveryState, hopCount: Int?) {
        messageDao.updateDeliveryState(msgId, state.name, hopCount)
    }

    override fun getHeldMessageIds(): List<ByteArray> {
        return messageDao.getHeldMessageIds()
    }

    override fun saveOutboxPacket(packet: Packet) {
        outboxDao.saveOutbox(OutboxEntity.fromPacket(packet))
    }

    override fun removeOutboxPacket(msgId: ByteArray) {
        outboxDao.removeOutbox(msgId)
    }

    override fun getOutboxPackets(): List<Packet> {
        return outboxDao.getAllOutbox().map { it.toPacket() }
    }

    override fun purgeExpired(nowMs: Long): Int {
        val purgedMessages = messageDao.purgeExpired(nowMs)
        val purgedOutbox = outboxDao.purgeExpired(nowMs)
        return purgedMessages + purgedOutbox
    }
}
