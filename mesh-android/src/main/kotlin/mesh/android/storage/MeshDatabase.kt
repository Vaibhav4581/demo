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
    version = 2,
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
            try {
                net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
            } catch (e: Throwable) {
                // Ignore if already initialized
            }
            val factory = SupportFactory(passphrase)
            val db = Room.databaseBuilder(
                context.applicationContext,
                MeshDatabase::class.java,
                dbName
            )
                .openHelperFactory(factory)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()

            // Verify the encrypted database can be opened with the current passphrase.
            // If the database file is corrupted or encrypted with an older/different key,
            // wipe it and cleanly recreate so the app does not crash on launch.
            try {
                db.openHelper.writableDatabase
            } catch (e: Throwable) {
                try {
                    context.deleteDatabase(dbName)
                    return Room.databaseBuilder(
                        context.applicationContext,
                        MeshDatabase::class.java,
                        dbName
                    )
                        .openHelperFactory(factory)
                        .allowMainThreadQueries()
                        .fallbackToDestructiveMigration()
                        .build()
                } catch (ignored: Throwable) {
                    // Fall back to original instance if deletion/recreation fails
                }
            }
            return db
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
