package mesh.android.storage.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import mesh.delivery.DeliveryState
import mesh.protocol.NodeId
import mesh.storage.MessageRecord

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "msg_id", typeAffinity = ColumnInfo.BLOB)
    val msgId: ByteArray,

    @ColumnInfo(name = "origin", typeAffinity = ColumnInfo.BLOB)
    val origin: ByteArray,

    @ColumnInfo(name = "dest", typeAffinity = ColumnInfo.BLOB)
    val dest: ByteArray?,

    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    val payload: ByteArray,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    @ColumnInfo(name = "delivery_state")
    val deliveryState: String,

    @ColumnInfo(name = "is_incoming")
    val isIncoming: Boolean,

    @ColumnInfo(name = "hop_count", defaultValue = "0")
    val hopCount: Int = 0
) {
    fun toDomain(): MessageRecord {
        return MessageRecord(
            msgId = msgId,
            origin = NodeId(origin),
            dest = dest?.let { NodeId(it) },
            payload = payload,
            createdAtMs = createdAtMs,
            expiresAtMs = expiresAtMs,
            deliveryState = DeliveryState.valueOf(deliveryState),
            isIncoming = isIncoming,
            hopCount = hopCount
        )
    }

    companion object {
        fun fromDomain(record: MessageRecord): MessageEntity {
            return MessageEntity(
                msgId = record.msgId,
                origin = record.origin.bytes,
                dest = record.dest?.bytes,
                payload = record.payload,
                createdAtMs = record.createdAtMs,
                expiresAtMs = record.expiresAtMs,
                deliveryState = record.deliveryState.name,
                isIncoming = record.isIncoming,
                hopCount = record.hopCount
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return msgId.contentEquals(other.msgId)
    }

    override fun hashCode(): Int = msgId.contentHashCode()
}
