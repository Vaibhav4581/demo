package mesh.android.storage.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import mesh.protocol.Packet

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "msg_id", typeAffinity = ColumnInfo.BLOB)
    val msgId: ByteArray,

    @ColumnInfo(name = "packet_bytes", typeAffinity = ColumnInfo.BLOB)
    val packetBytes: ByteArray,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_attempt_ms")
    val lastAttemptMs: Long = 0L
) {
    fun toPacket(): Packet = Packet.parseFrom(packetBytes)

    companion object {
        fun fromPacket(packet: Packet, createdAtMs: Long = packet.createdAtMs): OutboxEntity {
            return OutboxEntity(
                msgId = packet.msgId.toByteArray(),
                packetBytes = packet.toByteArray(),
                createdAtMs = createdAtMs,
                expiresAtMs = packet.expiresAtMs
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntity) return false
        return msgId.contentEquals(other.msgId)
    }

    override fun hashCode(): Int = msgId.contentHashCode()
}
