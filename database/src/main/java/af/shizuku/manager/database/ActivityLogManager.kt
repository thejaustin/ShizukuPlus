package af.shizuku.manager.database

import timber.log.Timber

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class representing an activity log record.
 */
data class ActivityLogRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val action: String
)

/**
 * Interface for ActivityLogManager settings to decouple it from the main app module.
 */
interface ActivityLogSettings {
    fun isActivityLogEnabled(): Boolean
    fun getWatchdog(): Boolean
    fun getActivityLogRetention(): Int
    fun setActivityLogRetention(count: Int)
    fun showNotification(appName: String, action: String)
}

/**
 * Manager for activity logs with Room database persistence.
 */
object ActivityLogManager {
    private const val TAG = "ActivityLogManager"
    
    private val records = Collections.synchronizedList(LinkedList<ActivityLogRecord>())
    
    private var database: ActivityLogDatabase? = null
    private var dao: ActivityLogDao? = null
    
    private val isResettingDatabase = AtomicBoolean(false)
    
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, exception ->
        handleDatabaseError(exception)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    
    private val isInitialized = AtomicBoolean(false)
    private val isCleaningUp = AtomicBoolean(false)
    
    private val _logs = MutableStateFlow<List<ActivityLogRecord>>(emptyList())
    val logs: StateFlow<List<ActivityLogRecord>> = _logs.asStateFlow()
    
    private var retentionCount = 100
    private var appContext: Context? = null
    private var settings: ActivityLogSettings? = null
    
    fun initialize(context: Context, settings: ActivityLogSettings) {
        if (isInitialized.getAndSet(true)) {
            return
        }
        appContext = context.applicationContext
        this.settings = settings

        scope.launch {
            try {
                val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                val dbFile = storageContext.getDatabasePath("shizuku_activity_logs.db")
                try {
                    dbFile.parentFile?.let { parent ->
                        if (!parent.exists()) {
                            parent.mkdirs()
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to create database directory")
                }

                try {
                    database = ActivityLogDatabase.getInstance(storageContext)
                    dao = database?.activityLogDao()
                    retentionCount = settings.getActivityLogRetention()
                    loadFromDatabase()
                    cleanupOldRecords()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to initialize ActivityLog database")
                    database = null
                    dao = null
                }

                Timber.tag(TAG).d("ActivityLogManager initialized")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to initialize ActivityLogManager")
            }
        }
    }
    
    private fun loadFromDatabase() {
        if (dao == null) return

        scope.launch {
            var retryCount = 0
            while (retryCount < 3) {
                try {
                    dao!!.getAll().collect { dbLogs ->
                        synchronized(records) {
                            records.clear()
                            dbLogs.forEach { log ->
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
                    }
                    return@launch
                } catch (e: Exception) {
                    retryCount++
                    delay(500)
                    if (retryCount >= 3) {
                        handleDatabaseError(e)
                    }
                }
            }
        }
    }
    
    fun log(appName: String, packageName: String, action: String) {
        val s = settings ?: return
        if (!s.isActivityLogEnabled()) return
        if (!isInitialized.get()) return
        
        if (s.getWatchdog()) {
            s.showNotification(appName, action)
        }
        
        val record = ActivityLogRecord(
            timestamp = System.currentTimeMillis(),
            appName = appName,
            packageName = packageName,
            action = action
        )
        
        synchronized(records) {
            if (records.size >= retentionCount) {
                records.removeAt(records.size - 1)
            }
            records.add(0, record)
            _logs.value = records.toList()
        }
        
        saveToDatabase(record)
        
        if (!isCleaningUp.get()) {
            cleanupOldRecords()
        }
    }
    
    private fun saveToDatabase(record: ActivityLogRecord) {
        val d = dao ?: return
        scope.launch {
            try {
                val roomLog = ActivityLogRoom(
                    timestamp = record.timestamp,
                    appName = record.appName,
                    packageName = record.packageName,
                    action = record.action
                )
                d.insert(roomLog)
            } catch (e: android.database.sqlite.SQLiteCantOpenDatabaseException) {
                Timber.tag(TAG).w("Error saving log: SQLiteCantOpenDatabaseException")
                handleDatabaseError(e)
            } catch (e: android.database.sqlite.SQLiteDatabaseCorruptException) {
                Timber.tag(TAG).w("Error saving log: SQLiteDatabaseCorruptException")
                handleDatabaseError(e)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error saving log")
            }
        }
    }
    
    private fun cleanupOldRecords() {
        if (isCleaningUp.getAndSet(true)) return
        
        scope.launch {
            try {
                dao?.deleteExcess(retentionCount)
            } catch (e: Exception) {
                handleDatabaseError(e)
            } finally {
                isCleaningUp.set(false)
            }
        }
    }
    
    fun getRecords(): List<ActivityLogRecord> = synchronized(records) {
        records.toList()
    }
    
    fun clear() {
        synchronized(records) {
            records.clear()
            _logs.value = emptyList()
        }
        
        scope.launch {
            try {
                dao?.clear()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error clearing logs")
            }
        }
    }
    
    fun updateRetentionCount(count: Int) {
        val newRetention = count.coerceIn(10, 1000)
        retentionCount = newRetention
        settings?.setActivityLogRetention(newRetention)
        cleanupOldRecords()
    }
    
    fun getRetentionCount(): Int = retentionCount
    
    suspend fun exportToJson(directory: File, filename: String? = null): File? = withContext(Dispatchers.IO) {
        try {
            val logs = dao?.getAll()?.first() ?: emptyList()
            if (logs.isEmpty()) return@withContext null
            
            val exportFile = File(directory, filename ?: "activity_logs_${getTimestampFilename()}.json")
            FileWriter(exportFile).use { writer ->
                writer.appendLine("[")
                logs.forEachIndexed { index, log ->
                    writer.appendLine("  {")
                    writer.appendLine("    \"timestamp\": ${log.timestamp},")
                    writer.appendLine("    \"appName\": \"${escapeJson(log.appName)}\",")
                    writer.appendLine("    \"packageName\": \"${escapeJson(log.packageName)}\",")
                    writer.appendLine("    \"action\": \"${escapeJson(log.action)}\"")
                    writer.appendLine("  }${if (index < logs.size - 1) "," else ""}")
                }
                writer.appendLine("]")
            }
            exportFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getTimestampFilename(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
    
    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun handleDatabaseError(e: Throwable) {
        val context = appContext ?: return
        
        val isDbError = e is android.database.sqlite.SQLiteCantOpenDatabaseException ||
                e is android.database.sqlite.SQLiteDatabaseCorruptException ||
                e.cause is android.database.sqlite.SQLiteCantOpenDatabaseException ||
                e.cause is android.database.sqlite.SQLiteDatabaseCorruptException
                
        if (!isDbError || isResettingDatabase.getAndSet(true)) return
        
        scope.launch {
            try {
                Timber.tag(TAG).w("Autofixing corrupted database: ${e.message}")
                
                ActivityLogDatabase.resetInstance()
                database = null
                dao = null
                
                val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                var recoverySuccessful = false
                val dbFile = storageContext.getDatabasePath("shizuku_activity_logs.db")
                val timestamp = getTimestampFilename()
                val corruptedBackup = File(dbFile.path + "_corrupt_backup_" + timestamp)

                listOf(
                    dbFile,
                    File(dbFile.path + "-shm"),
                    File(dbFile.path + "-wal")
                ).forEach { file ->
                    if (file.exists() && file.name.endsWith(".db")) {
                        file.renameTo(corruptedBackup)
                    } else if (file.exists()) {
                        file.delete()
                    }
                }
                
                try {
                    dbFile.parentFile?.let { parent ->
                        if (!parent.exists()) parent.mkdirs()
                    }
                } catch (ex: Exception) {
                    Timber.tag(TAG).e(ex, "Failed to create database directory during recovery")
                }

                database = ActivityLogDatabase.getInstance(storageContext)
                dao = database?.activityLogDao()
                
                if (recoverySuccessful) {
                    settings?.showNotification("System Recovered", "Activity log database was corrupted but automatically salvaged using SQLite recovery!")
                } else {
                    settings?.showNotification("System Warning", "Activity log database corrupted. A backup was saved and a new DB created. You can attempt manual recovery in Developer Settings.")
                }
                
                val recoveryRecord = ActivityLogRecord(
                    appName = "System",
                    packageName = context.packageName,
                    action = if (recoverySuccessful) {
                        "Database automatically recovered and salvaged from corruption!"
                    } else {
                        "Database autofixed after corruption. Backup saved to ${corruptedBackup.name}"
                    }
                )
                
                synchronized(records) {
                    records.add(0, recoveryRecord)
                    _logs.value = records.toList()
                }
                
                dao?.insert(ActivityLogRoom(
                    timestamp = recoveryRecord.timestamp,
                    appName = recoveryRecord.appName,
                    packageName = recoveryRecord.packageName,
                    action = recoveryRecord.action
                ))
            } catch (resetError: Exception) {
                Timber.tag(TAG).e(resetError, "CRITICAL: Failed to autofix database!")
            } finally {
                isResettingDatabase.set(false)
            }
        }
    }

    suspend fun manualRecoverDatabase(context: android.content.Context, backupFile: File, method: String): String {
        if (!backupFile.exists()) return "Backup file not found."
        
        val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val newDbFile = storageContext.getDatabasePath("shizuku_activity_logs.db")
        
        return withContext(Dispatchers.IO) {
            try {
                when (method) {
                    "recover" -> {
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "sqlite3 ${backupFile.absolutePath} '.recover' | sqlite3 ${newDbFile.absolutePath}"))
                        if (process.waitFor() == 0) "Recovery successful via SQLite .recover" else "SQLite .recover failed."
                    }
                    "dump" -> {
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "sqlite3 ${backupFile.absolutePath} '.dump' | sqlite3 ${newDbFile.absolutePath}"))
                        if (process.waitFor() == 0) "Recovery successful via SQLite .dump" else "SQLite .dump failed."
                    }
                    "raw_text_extraction" -> {
                        // Raw binary scraping for partial recovery of readable text logs
                        val content = backupFile.readBytes()
                        val text = String(content, Charsets.US_ASCII)
                        val regex = Regex("[A-Za-z0-9_{}\\\":., -]{15,}")
                        val matches = regex.findAll(text).map { it.value }.toList()
                        
                        val exportFile = File(context.filesDir, "partial_text_recovery_${getTimestampFilename()}.txt")
                        exportFile.writeText(matches.joinToString("\n"))
                        "Partial text extracted to ${exportFile.name}"
                    }
                    else -> "Unknown recovery method."
                }
            } catch (e: Exception) {
                "Recovery failed: ${e.message}"
            }
        }
    }
}