package mesh.android.storage

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mesh.android.storage.dao.MessageDao
import mesh.android.storage.dao.OutboxDao
import mesh.android.storage.entities.MessageEntity
import mesh.android.storage.entities.OutboxEntity
import mesh.delivery.DeliveryState
import mesh.protocol.NodeId
import mesh.protocol.PacketFactory
import mesh.storage.MessageRecord
import org.junit.jupiter.api.Test

class RoomMessageStoreUnitTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val store = RoomMessageStore(messageDao, outboxDao)

    private val origin = NodeId(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
    private val dest = NodeId(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2))
    private val msgId = ByteArray(16) { it.toByte() }

    @Test
    fun `saveMessage converts domain record to entity and persists via DAO`() {
        val record = MessageRecord(
            msgId = msgId,
            origin = origin,
            dest = dest,
            payload = "Hello Mesh".toByteArray(),
            createdAtMs = 1000L,
            expiresAtMs = 5000L,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = false
        )

        store.saveMessage(record)

        verify {
            messageDao.saveMessage(match { entity ->
                entity.msgId.contentEquals(msgId) &&
                        entity.origin.contentEquals(origin.bytes) &&
                        entity.dest?.contentEquals(dest.bytes) == true &&
                        entity.deliveryState == DeliveryState.QUEUED.name &&
                        !entity.isIncoming
            })
        }
    }

    @Test
    fun `getMessage retrieves and converts entity to domain record`() {
        val entity = MessageEntity(
            msgId = msgId,
            origin = origin.bytes,
            dest = dest.bytes,
            payload = "Hello Mesh".toByteArray(),
            createdAtMs = 1000L,
            expiresAtMs = 5000L,
            deliveryState = DeliveryState.DELIVERED.name,
            isIncoming = true
        )
        every { messageDao.getMessage(msgId) } returns entity

        val result = store.getMessage(msgId)

        assertThat(result).isNotNull()
        assertThat(result!!.msgId).isEqualTo(msgId)
        assertThat(result.origin).isEqualTo(origin)
        assertThat(result.dest).isEqualTo(dest)
        assertThat(result.deliveryState).isEqualTo(DeliveryState.DELIVERED)
        assertThat(result.isIncoming).isEqualTo(true)
    }

    @Test
    fun `updateDeliveryState updates state by name in DAO`() {
        store.updateDeliveryState(msgId, DeliveryState.DELIVERED)
        verify { messageDao.updateDeliveryState(msgId, DeliveryState.DELIVERED.name) }
    }

    @Test
    fun `getHeldMessageIds returns held IDs from DAO`() {
        val heldList = listOf(msgId)
        every { messageDao.getHeldMessageIds() } returns heldList

        val result = store.getHeldMessageIds()
        assertThat(result).containsExactly(msgId)
    }

    @Test
    fun `saveOutboxPacket encodes Packet and persists to OutboxDao`() {
        val packet = PacketFactory.createData(
            origin = origin,
            dest = dest,
            payload = "Outbox test".toByteArray(),
            createdAtMs = 2000L
        )

        store.saveOutboxPacket(packet)

        verify {
            outboxDao.saveOutbox(match { entity ->
                entity.msgId.contentEquals(packet.msgId.toByteArray()) &&
                        entity.packetBytes.isNotEmpty() &&
                        entity.expiresAtMs == packet.expiresAtMs
            })
        }
    }

    @Test
    fun `getOutboxPackets decodes entities back to Packet instances`() {
        val packet = PacketFactory.createData(
            origin = origin,
            dest = dest,
            payload = "Outbox test".toByteArray()
        )
        val entity = OutboxEntity.fromPacket(packet)
        every { outboxDao.getAllOutbox() } returns listOf(entity)

        val packets = store.getOutboxPackets()
        assertThat(packets.size).isEqualTo(1)
        assertThat(packets[0].msgId.toByteArray()).isEqualTo(packet.msgId.toByteArray())
    }

    @Test
    fun `purgeExpired removes expired messages and outbox entries`() {
        every { messageDao.purgeExpired(10000L) } returns 3
        every { outboxDao.purgeExpired(10000L) } returns 2

        val purged = store.purgeExpired(10000L)
        assertThat(purged).isEqualTo(5)
    }
}
