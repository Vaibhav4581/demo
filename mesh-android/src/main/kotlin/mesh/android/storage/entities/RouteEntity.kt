package mesh.android.storage.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey
    @ColumnInfo(name = "dest", typeAffinity = ColumnInfo.BLOB)
    val dest: ByteArray,

    @ColumnInfo(name = "next_hop", typeAffinity = ColumnInfo.BLOB)
    val nextHop: ByteArray,

    @ColumnInfo(name = "cost")
    val cost: Int,

    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteEntity) return false
        return dest.contentEquals(other.dest)
    }

    override fun hashCode(): Int = dest.contentHashCode()
}
