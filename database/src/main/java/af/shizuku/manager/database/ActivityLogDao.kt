package af.shizuku.manager.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for activity logs.
 *
 * Provides methods for inserting, querying, and deleting activity log entries.
 * This DAO uses suspend functions and Flow for non-blocking database access.
 */
@Dao
interface ActivityLogDao {

    /**
     * Insert a single activity log entry.
     *
     * @param log The activity log to insert.
     * @return The row ID of the inserted log entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActivityLogRoom): Long

    /**
     * Insert multiple activity log entries.
     *
     * @param logs List of activity logs to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ActivityLogRoom>)

    /**
     * Get all activity log entries as a Flow, ordered by timestamp (newest first).
     * The Flow will automatically emit a new list when the data changes.
     *
     * @return Flow emitting a list of all activity logs sorted by timestamp descending.
     */
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ActivityLogRoom>>

    /**
     * Get a limited number of activity log entries as a Flow, ordered by timestamp (newest first).
     * The Flow will automatically emit a new list when the data changes.
     *
     * @param limit Maximum number of records to return.
     * @return Flow emitting a list of activity logs sorted by timestamp descending, limited to [limit] entries.
     */
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getLimited(limit: Int): Flow<List<ActivityLogRoom>>

    /**
     * Delete all activity log entries.
     */
    @Query("DELETE FROM activity_logs")
    suspend fun clear()

    /**
     * Delete activity logs except for the most recent ones.
     *
     * @param limit The number of most recent records to keep.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM (SELECT id FROM activity_logs ORDER BY timestamp DESC, id DESC LIMIT :limit))")
    suspend fun deleteExcess(limit: Int): Int

    /**
     * Delete activity logs older than the specified timestamp.
     *
     * @param timestamp Unix timestamp in milliseconds. Logs with timestamp less than this value will be deleted.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM activity_logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    /**
     * Get the count of activity log entries.
     *
     * @return Total number of activity logs in the database.
     */
    @Query("SELECT COUNT(*) FROM activity_logs")
    suspend fun getCount(): Int

    /**
     * Delete a specific activity log entry.
     *
     * @param log The activity log to delete.
     */
    @Delete
    suspend fun delete(log: ActivityLogRoom)

    /**
     * Get the oldest log entry.
     *
     * @return The oldest activity log or null if no logs exist.
     */
    @Query("SELECT * FROM activity_logs ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldest(): ActivityLogRoom?
}
