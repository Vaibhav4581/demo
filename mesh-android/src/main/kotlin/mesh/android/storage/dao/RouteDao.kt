package mesh.android.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mesh.android.storage.entities.RouteEntity

@Dao
interface RouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRoute(route: RouteEntity)

    @Query("SELECT * FROM routes WHERE dest = :dest LIMIT 1")
    fun getRoute(dest: ByteArray): RouteEntity?

    @Query("SELECT * FROM routes")
    fun getAllRoutes(): List<RouteEntity>

    @Query("DELETE FROM routes WHERE dest = :dest")
    fun deleteRoute(dest: ByteArray): Int

    @Query("DELETE FROM routes WHERE next_hop = :nextHop")
    fun deleteRoutesThrough(nextHop: ByteArray): Int

    @Query("DELETE FROM routes WHERE last_seen_ms < :staleThresholdMs")
    fun purgeStale(staleThresholdMs: Long): Int
}
