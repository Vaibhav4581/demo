package mesh.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import mesh.android.storage.dao.MessageDao
import mesh.android.storage.dao.NodeDao
import mesh.android.storage.dao.OutboxDao
import mesh.android.storage.dao.RouteDao
import mesh.android.storage.entities.MessageEntity
import mesh.android.storage.entities.NodeEntity
import mesh.android.storage.entities.OutboxEntity
import mesh.android.storage.entities.RouteEntity
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        MessageEntity::class,
        OutboxEntity::class,
        RouteEntity::class,
        NodeEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun routeDao(): RouteDao
    abstract fun nodeDao(): NodeDao

    companion object {
        const val DEFAULT_DB_NAME = "mesh_encrypted.db"

        /**
         * Builds an encrypted [MeshDatabase] instance backed by SQLCipher using [passphrase].
         */
        fun buildEncryptedDatabase(
            context: Context,
            passphrase: ByteArray,
            dbName: String = DEFAULT_DB_NAME
        ): MeshDatabase {
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                MeshDatabase::class.java,
                dbName
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Builds an in-memory [MeshDatabase] for testing.
         * If [passphrase] is non-null, SQLCipher encryption is enabled in memory.
         */
        fun buildInMemory(
            context: Context,
            passphrase: ByteArray? = null
        ): MeshDatabase {
            val builder = Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MeshDatabase::class.java
            ).allowMainThreadQueries()

            if (passphrase != null) {
                builder.openHelperFactory(SupportFactory(passphrase))
            }
            return builder.build()
        }
    }
}
