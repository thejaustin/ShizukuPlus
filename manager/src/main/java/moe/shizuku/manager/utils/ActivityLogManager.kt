package moe.shizuku.manager.utils

import moe.shizuku.manager.ShizukuSettings
import java.util.Collections
import java.util.LinkedList

data class ActivityLogRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val action: String
)

object ActivityLogManager {
    private val records = Collections.synchronizedList(LinkedList<ActivityLogRecord>())
    private const val MAX_RECORDS = 100

    fun log(appName: String, packageName: String, action: String) {
        if (!ShizukuSettings.isActivityLogEnabled()) return
        
        synchronized(records) {
            if (records.size >= MAX_RECORDS) {
                records.removeLast()
            }
            records.addFirst(ActivityLogRecord(appName = appName, packageName = packageName, action = action))
        }
    }

    fun getRecords(): List<ActivityLogRecord> = synchronized(records) {
        records.toList()
    }

    fun clear() {
        records.clear()
    }
}
