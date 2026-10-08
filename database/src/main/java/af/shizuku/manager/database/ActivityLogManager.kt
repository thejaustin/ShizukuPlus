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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ActivityEventType {
    SERVICE_START,
    SERVICE_STOP,
    PERMISSION,
    APP_MANAGEMENT,
    WATCHDOG,
    SYSTEM,
    OTHER;

    companion object {
        fun fromAction(action: String): ActivityEventType = when {
            action.contains("Service started", ignoreCase = true) -> SERVICE_START
            action.contains("started via", ignoreCase = true) -> SERVICE_START
            action.contains("Service stopped", ignoreCase = true) ||
                action.contains("stopped", ignoreCase = true) -> SERVICE_STOP
            action.contains("grant_permission", ignoreCase = true) ||
                action.contains("revoke_permission", ignoreCase = true) ||
                action.contains("Permission", ignoreCase = true) -> PERMISSION
            action.contains("Long-press:", ignoreCase = true) ||
                action.contains("freeze", ignoreCase = true) ||
                action.contains("unfreeze", ignoreCase = true) ||
                action.contains("Enhancement", ignoreCase = true) -> APP_MANAGEMENT
            action.contains("Watchdog", ignoreCase = true) ||
                action.contains("watchdog", ignoreCase = true) -> WATCHDOG
            action.contains("Database", ignoreCase = true) ||
                action.contains("System", ignoreCase = true) ||
                action.contains("autofixed", ignoreCase = true) -> SYSTEM
            else -> OTHER
        }
    }
}

data class ActivityLogRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val action: String,
    val eventType: ActivityEventType = ActivityEventType.fromAction(action),
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
        val dao = dao ?: return

        scope.launch {
            var retryCount = 0
            while (retryCount < 3) {
                try {
                    dao.getAll().collect { dbLogs ->
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
    
    private fun getTimestampFilename(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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

                settings?.showNotification("System Warning", "Activity log database corrupted. A backup was saved and a new DB created. You can attempt manual recovery in Developer Settings.")

                val recoveryRecord = ActivityLogRecord(
                    appName = "System",
                    packageName = context.packageName,
                    action = "Database autofixed after corruption. Backup saved to ${corruptedBackup.name}"
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

}