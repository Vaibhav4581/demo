package mesh.android.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mesh.android.storage.entities.OutboxEntity

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveOutbox(outbox: OutboxEntity)

    @Query("DELETE FROM outbox WHERE msg_id = :msgId")
    fun removeOutbox(msgId: ByteArray): Int

    @Query("SELECT * FROM outbox ORDER BY created_at_ms ASC")
    fun getAllOutbox(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE expires_at_ms <= :nowMs")
    fun purgeExpired(nowMs: Long): Int

    @Query("UPDATE outbox SET retry_count = :retryCount, last_attempt_ms = :lastAttemptMs WHERE msg_id = :msgId")
    fun updateAttempt(msgId: ByteArray, retryCount: Int, lastAttemptMs: Long)
}
