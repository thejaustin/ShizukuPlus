package moe.shizuku.manager.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.database.ActivityLogDao
import moe.shizuku.manager.database.ActivityLogDatabase
import moe.shizuku.manager.database.ActivityLogRoom
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class representing an activity log record.
 * 
 * @property timestamp Unix timestamp in milliseconds when the action occurred.
 * @property appName Human-readable name of the application.
 * @property packageName Package name of the application.
 * @property action The action that was performed (e.g., "START", "STOP", "RESTART").
 */
data class ActivityLogRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val action: String
)

/**
 * Manager for activity logs with Room database persistence.
 * 
 * Features:
 * - Saves logs to Room database for persistence across app restarts
 * - Loads logs from database on initialization
 * - Configurable retention period (default 100 records)
 * - Export to file functionality (JSON and CSV formats)
 * - Thread-safe database operations
 * - Automatic cleanup of old records based on retention settings
 */
object ActivityLogManager {
    private const val TAG = "ActivityLogManager"
    
    // In-memory cache for quick access
    private val records = Collections.synchronizedList(LinkedList<ActivityLogRecord>())
    
    // Database components
    private var database: ActivityLogDatabase? = null
    private var dao: ActivityLogDao? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Thread safety
    private val dbLock = Semaphore(1)
    private val isInitialized = AtomicBoolean(false)
    private val isCleaningUp = AtomicBoolean(false)
    
    // State flow for observing log changes
    private val _logs = MutableStateFlow<List<ActivityLogRecord>>(emptyList())
    val logs: StateFlow<List<ActivityLogRecord>> = _logs.asStateFlow()
    
    // Default retention count
    private var retentionCount = 100
    
    /**
     * Initialize the ActivityLogManager with the application context.
     * This must be called before using any other methods.
     *
     * @param context Application context
     */
    fun initialize(context: Context) {
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "ActivityLogManager already initialized")
            return
        }

        try {
            database = ActivityLogDatabase.getInstance(context)
            dao = database?.activityLogDao()

            // Load retention setting
            retentionCount = ShizukuSettings.getActivityLogRetention()

            // Load existing logs from database
            loadFromDatabase()

            // Perform cleanup of old records
            cleanupOldRecords()

            Log.d(TAG, "ActivityLogManager initialized with retention count: $retentionCount")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ActivityLogManager", e)
            // Continue without database - logs will still work in memory
            // This prevents app crash if database is corrupted
        }
    }
    
    /**
     * Load all activity logs from the database into memory.
     */
    private fun loadFromDatabase() {
        if (dao == null) {
            Log.w(TAG, "DAO not available, cannot load from database")
            return
        }
        
        scope.launch {
            try {
                dbLock.acquire()
                try {
                    val dbLogs = dao!!.getAll()
                    synchronized(records) {
                        records.clear()
                        dbLogs.reversed().forEach { log ->
                            records.add(
                                ActivityLogRecord(
                                    timestamp = log.timestamp,
                                    appName = log.appName,
                                    packageName = log.packageName,
                                    action = log.action
                                )
                            )
                        }
                        _logs.value = records.toList()
                    }
                    Log.d(TAG, "Loaded ${records.size} logs from database")
                } finally {
                    dbLock.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading logs from database", e)
            }
        }
    }
    
    /**
     * Log an activity.
     * 
     * @param appName Human-readable name of the application
     * @param packageName Package name of the application
     * @param action The action that was performed
     */
    fun log(appName: String, packageName: String, action: String) {
        if (!ShizukuSettings.isActivityLogEnabled()) return
        if (!isInitialized.get()) {
            Log.w(TAG, "ActivityLogManager not initialized, ignoring log")
            return
        }
        
        val record = ActivityLogRecord(
            timestamp = System.currentTimeMillis(),
            appName = appName,
            packageName = packageName,
            action = action
        )
        
        // Add to in-memory cache
        synchronized(records) {
            // Remove oldest if at capacity
            if (records.size >= retentionCount) {
                records.removeLast()
            }
            records.add(0, record)
            _logs.value = records.toList()
        }
        
        // Save to database asynchronously
        saveToDatabase(record)
        
        // Trigger cleanup if needed
        if (!isCleaningUp.get()) {
            cleanupOldRecords()
        }
    }
    
    /**
     * Save a log record to the database.
     */
    private fun saveToDatabase(record: ActivityLogRecord) {
        if (dao == null) {
            Log.w(TAG, "DAO not available, cannot save to database")
            return
        }
        
        scope.launch {
            try {
                dbLock.acquire()
                try {
                    val roomLog = ActivityLogRoom(
                        timestamp = record.timestamp,
                        appName = record.appName,
                        packageName = record.packageName,
                        action = record.action
                    )
                    dao!!.insert(roomLog)
                    
                    // Enforce retention limit in database
                    enforceRetentionInDatabase()
                } finally {
                    dbLock.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving log to database", e)
            }
        }
    }
    
    /**
     * Enforce the retention limit in the database.
     */
    private suspend fun enforceRetentionInDatabase() {
        if (isCleaningUp.getAndSet(true)) return
        
        try {
            val count = dao!!.getCount()
            if (count > retentionCount) {
                val excessCount = count - retentionCount
                // Get the timestamp of the record to keep
                val logs = dao!!.getAll()
                if (logs.size > retentionCount) {
                    val cutoffTimestamp = logs[retentionCount - 1].timestamp
                    val deleted = dao!!.deleteOlderThan(cutoffTimestamp)
                    Log.d(TAG, "Cleaned up $deleted old records from database")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing retention", e)
        } finally {
            isCleaningUp.set(false)
        }
    }
    
    /**
     * Clean up old records based on retention settings.
     */
    private fun cleanupOldRecords() {
        if (isCleaningUp.getAndSet(true)) return
        
        scope.launch {
            try {
                dbLock.acquire()
                try {
                    val count = dao?.getCount() ?: 0
                    if (count > retentionCount) {
                        val logs = dao!!.getAll()
                        if (logs.size > retentionCount) {
                            val cutoffTimestamp = logs[retentionCount - 1].timestamp
                            val deleted = dao!!.deleteOlderThan(cutoffTimestamp)
                            Log.d(TAG, "Cleaned up $deleted old records")
                        }
                    }
                } finally {
                    dbLock.release()
                    isCleaningUp.set(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up old records", e)
                isCleaningUp.set(false)
            }
        }
    }
    
    /**
     * Get all activity log records.
     * 
     * @return List of activity log records, newest first
     */
    fun getRecords(): List<ActivityLogRecord> = synchronized(records) {
        records.toList()
    }
    
    /**
     * Get a limited number of activity log records.
     * 
     * @param limit Maximum number of records to return
     * @return List of activity log records, newest first, limited to [limit] entries
     */
    fun getRecords(limit: Int): List<ActivityLogRecord> = synchronized(records) {
        records.take(limit).toList()
    }
    
    /**
     * Clear all activity logs from both memory and database.
     */
    fun clear() {
        synchronized(records) {
            records.clear()
            _logs.value = emptyList()
        }
        
        scope.launch {
            try {
                dbLock.acquire()
                try {
                    dao?.clear()
                    Log.d(TAG, "Cleared all logs from database")
                } finally {
                    dbLock.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing logs from database", e)
            }
        }
    }
    
    /**
     * Update the retention count setting.
     * 
     * @param count New retention count (minimum 10, maximum 1000)
     */
    fun updateRetentionCount(count: Int) {
        val newRetention = count.coerceIn(10, 1000)
        retentionCount = newRetention
        ShizukuSettings.setActivityLogRetention(newRetention)
        
        // Trigger cleanup with new retention
        cleanupOldRecords()
        
        Log.d(TAG, "Updated retention count to: $newRetention")
    }
    
    /**
     * Get the current retention count.
     * 
     * @return Current retention count
     */
    fun getRetentionCount(): Int = retentionCount
    
    /**
     * Export activity logs to a JSON file.
     * 
     * @param directory Directory to save the export file
     * @param filename Optional filename (defaults to auto-generated name with timestamp)
     * @return File object representing the exported file, or null if export failed
     */
    suspend fun exportToJson(directory: File, filename: String? = null): File? = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            Log.e(TAG, "Cannot export: ActivityLogManager not initialized")
            return@withContext null
        }
        
        try {
            dbLock.acquire()
            try {
                val logs = dao?.getAll() ?: emptyList()
                
                if (logs.isEmpty()) {
                    Log.w(TAG, "No logs to export")
                    return@withContext null
                }
                
                val exportFile = File(
                    directory,
                    filename ?: "activity_logs_${getTimestampFilename()}.json"
                )
                
                FileWriter(exportFile).use { writer ->
                    writer.appendLine("[")
                    logs.forEachIndexed { index, log ->
                        writer.appendLine("  {")
                        writer.appendLine("    \"id\": ${log.id},")
                        writer.appendLine("    \"timestamp\": ${log.timestamp},")
                        writer.appendLine("    \"timestampFormatted\": \"${formatTimestamp(log.timestamp)}\",")
                        writer.appendLine("    \"appName\": \"${escapeJson(log.appName)}\",")
                        writer.appendLine("    \"packageName\": \"${escapeJson(log.packageName)}\",")
                        writer.appendLine("    \"action\": \"${escapeJson(log.action)}\"")
                        writer.appendLine("  }${if (index < logs.size - 1) "," else ""}")
                    }
                    writer.appendLine("]")
                }
                
                Log.d(TAG, "Exported ${logs.size} logs to JSON: ${exportFile.absolutePath}")
                exportFile
            } finally {
                dbLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting logs to JSON", e)
            null
        }
    }
    
    /**
     * Export activity logs to a CSV file.
     * 
     * @param directory Directory to save the export file
     * @param filename Optional filename (defaults to auto-generated name with timestamp)
     * @return File object representing the exported file, or null if export failed
     */
    suspend fun exportToCsv(directory: File, filename: String? = null): File? = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            Log.e(TAG, "Cannot export: ActivityLogManager not initialized")
            return@withContext null
        }
        
        try {
            dbLock.acquire()
            try {
                val logs = dao?.getAll() ?: emptyList()
                
                if (logs.isEmpty()) {
                    Log.w(TAG, "No logs to export")
                    return@withContext null
                }
                
                val exportFile = File(
                    directory,
                    filename ?: "activity_logs_${getTimestampFilename()}.csv"
                )
                
                FileWriter(exportFile).use { writer ->
                    // Write header
                    writer.appendLine("ID,Timestamp,TimestampFormatted,App Name,Package Name,Action")
                    
                    // Write data rows
                    logs.forEach { log ->
                        writer.appendLine(
                            "${log.id}," +
                            "${log.timestamp}," +
                            "\"${formatTimestamp(log.timestamp)}\"," +
                            "\"${escapeCsv(log.appName)}\"," +
                            "\"${escapeCsv(log.packageName)}\"," +
                            "\"${escapeCsv(log.action)}\""
                        )
                    }
                }
                
                Log.d(TAG, "Exported ${logs.size} logs to CSV: ${exportFile.absolutePath}")
                exportFile
            } finally {
                dbLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting logs to CSV", e)
            null
        }
    }
    
    /**
     * Export activity logs to a human-readable text file.
     * 
     * @param directory Directory to save the export file
     * @param filename Optional filename (defaults to auto-generated name with timestamp)
     * @return File object representing the exported file, or null if export failed
     */
    suspend fun exportToText(directory: File, filename: String? = null): File? = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            Log.e(TAG, "Cannot export: ActivityLogManager not initialized")
            return@withContext null
        }
        
        try {
            dbLock.acquire()
            try {
                val logs = dao?.getAll() ?: emptyList()
                
                if (logs.isEmpty()) {
                    Log.w(TAG, "No logs to export")
                    return@withContext null
                }
                
                val exportFile = File(
                    directory,
                    filename ?: "activity_logs_${getTimestampFilename()}.txt"
                )
                
                FileWriter(exportFile).use { writer ->
                    writer.appendLine("=".repeat(60))
                    writer.appendLine("Shizuku+ Activity Log Export")
                    writer.appendLine("Generated: ${formatTimestamp(System.currentTimeMillis())}")
                    writer.appendLine("Total Records: ${logs.size}")
                    writer.appendLine("=".repeat(60))
                    writer.appendLine()
                    
                    logs.forEach { log ->
                        writer.appendLine("[${formatTimestamp(log.timestamp)}]")
                        writer.appendLine("  App: ${log.appName}")
                        writer.appendLine("  Package: ${log.packageName}")
                        writer.appendLine("  Action: ${log.action}")
                        writer.appendLine("-".repeat(60))
                    }
                    
                    writer.appendLine()
                    writer.appendLine("=".repeat(60))
                    writer.appendLine("End of Activity Log")
                    writer.appendLine("=".repeat(60))
                }
                
                Log.d(TAG, "Exported ${logs.size} logs to text: ${exportFile.absolutePath}")
                exportFile
            } finally {
                dbLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting logs to text", e)
            null
        }
    }
    
    /**
     * Get the total count of logs in the database.
     * 
     * @return Total number of logs
     */
    suspend fun getDatabaseCount(): Int = withContext(Dispatchers.IO) {
        try {
            dbLock.acquire()
            try {
                dao?.getCount() ?: 0
            } finally {
                dbLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting database count", e)
            0
        }
    }
    
    /**
     * Shutdown the manager and close the database connection.
     */
    fun shutdown() {
        scope.launch {
            try {
                dbLock.acquire()
                try {
                    database?.close()
                    database = null
                    dao = null
                    isInitialized.set(false)
                    Log.d(TAG, "ActivityLogManager shutdown complete")
                } finally {
                    dbLock.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown", e)
            }
        }
    }
    
    // Helper functions
    
    private fun getTimestampFilename(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
    }
    
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    private fun escapeCsv(str: String): String {
        return str
            .replace("\"", "\"\"")
    }
}
