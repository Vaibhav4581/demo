package mesh.android.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mesh.android.storage.entities.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE msg_id = :msgId LIMIT 1")
    fun getMessage(msgId: ByteArray): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY created_at_ms ASC")
    fun getAllMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE dest IS NULL ORDER BY created_at_ms ASC")
    fun getBroadcastMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE (dest = :peerId AND is_incoming = 0) OR (origin = :peerId AND is_incoming = 1) ORDER BY created_at_ms ASC")
    fun getConversation(peerId: ByteArray): List<MessageEntity>

    @Query("UPDATE messages SET delivery_state = :state, hop_count = COALESCE(:hopCount, hop_count) WHERE msg_id = :msgId")
    fun updateDeliveryState(msgId: ByteArray, state: String, hopCount: Int? = null)

    @Query("SELECT msg_id FROM messages")
    fun getHeldMessageIds(): List<ByteArray>

    @Query("DELETE FROM messages WHERE expires_at_ms <= :nowMs")
    fun purgeExpired(nowMs: Long): Int
}
