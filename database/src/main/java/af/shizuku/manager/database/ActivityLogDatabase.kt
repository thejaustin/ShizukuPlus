package af.shizuku.manager.database

import android.content.Context
import androidx.room.Database
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

        private fun buildDatabase(context: Context): ActivityLogDatabase =
            buildRoomDatabaseWithStorageFallback(
                context, DATABASE_NAME, ActivityLogDatabase::class.java, "ActivityLogDatabase"
            )

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
