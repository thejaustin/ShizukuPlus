package af.shizuku.manager.database

import timber.log.Timber

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Room database for activity logs.
 * 
 * This database stores activity log entries for tracking application actions.
 */
@Database(
    entities = [ActivityLogRoom::class],
    version = 1,
    exportSchema = false
)
abstract class ActivityLogDatabase : RoomDatabase() {

    /**
     * Get the DAO for activity logs.
     */
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        private const val DATABASE_NAME = "shizuku_activity_logs.db"

        @Volatile
        private var instance: ActivityLogDatabase? = null

        private val lock = ReentrantLock()

        /**
         * Get the singleton instance of the database.
         * 
         * @param context Application context.
         * @return The database instance.
         */
        fun getInstance(context: Context): ActivityLogDatabase {
            return instance ?: lock.withLock {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): ActivityLogDatabase {
            // Try device-protected storage first so logs survive direct boot.
            // Fall back to regular app storage if the directory can't be created
            // (happens on some devices where createDeviceProtectedStorageContext()
            // maps back to credential-encrypted storage that isn't yet accessible).
            val candidates = buildList {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    add(context.createDeviceProtectedStorageContext())
                }
                add(context.applicationContext)
            }

            for (ctx in candidates) {
                val dbFile = ctx.getDatabasePath(DATABASE_NAME)
                val parent = dbFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                if (parent == null || parent.exists()) {
                    return Room.databaseBuilder(ctx, ActivityLogDatabase::class.java, DATABASE_NAME)
                        .fallbackToDestructiveMigration()
                        .build()
                }
                Timber.tag("ActivityLogDatabase").w("mkdirs failed for ${parent?.absolutePath}, trying next context")
            }

            // Last resort: in-memory database so the app never crashes due to storage issues
            Timber.tag("ActivityLogDatabase").e("All storage contexts failed; falling back to in-memory database")
            return Room.inMemoryDatabaseBuilder(context.applicationContext, ActivityLogDatabase::class.java)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Reset the singleton instance (useful for testing).
         */
        fun resetInstance() {
            lock.withLock {
                instance?.close()
                instance = null
            }
        }
    }
}
