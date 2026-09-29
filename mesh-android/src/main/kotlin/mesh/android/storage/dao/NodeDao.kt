package mesh.android.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mesh.android.storage.entities.NodeEntity

@Dao
interface NodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertNode(node: NodeEntity)

    @Query("SELECT * FROM nodes WHERE node_id = :nodeId LIMIT 1")
    fun getNode(nodeId: ByteArray): NodeEntity?

    @Query("SELECT * FROM nodes ORDER BY last_seen_ms DESC")
    fun getAllNodes(): List<NodeEntity>

    @Query("DELETE FROM nodes WHERE node_id = :nodeId")
    fun deleteNode(nodeId: ByteArray): Int
}
