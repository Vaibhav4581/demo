package mesh.android.storage.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id", typeAffinity = ColumnInfo.BLOB)
    val nodeId: ByteArray,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "public_key", typeAffinity = ColumnInfo.BLOB)
    val publicKey: ByteArray,

    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeEntity) return false
        return nodeId.contentEquals(other.nodeId)
    }

    override fun hashCode(): Int = nodeId.contentHashCode()
}
