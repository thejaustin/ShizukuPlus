package af.shizuku.manager.utils

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/** A crash kept in the history, for picking which ones go into a manual report. */
data class LoggedCrash(
    val file: File,
    val timestamp: Long,
    val exception: String,
)

class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    companion object {
        private const val CRASH_FILE_NAME = "last_crash.txt"
        private const val HISTORY_DIR = "crash_history"
        private const val HISTORY_MAX = 10

        private fun historyDir(context: Context): File {
            val base =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context.applicationContext
                }
            return File(base.filesDir, HISTORY_DIR)
        }

        /** The last [HISTORY_MAX] crashes, newest first. last_crash.txt alone only held the latest. */
        fun getCrashHistory(context: Context): List<LoggedCrash> {
            val files = historyDir(context).listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") } ?: return emptyList()
            return files
                .mapNotNull { file ->
                    val time =
                        file.name
                            .removePrefix("crash_")
                            .removeSuffix(".txt")
                            .toLongOrNull() ?: return@mapNotNull null
                    val exception =
                        try {
                            file.useLines { lines -> lines.firstOrNull { it.startsWith("Exception: ") }?.removePrefix("Exception: ") }
                        } catch (_: Exception) {
                            null
                        }
                    LoggedCrash(file, time, exception?.substringAfterLast('.') ?: "?")
                }.sortedByDescending { it.timestamp }
        }

        fun getCrashFile(
            context: Context,
            checkExistingOnly: Boolean = false,
        ): File? {
            val candidates =
                buildList {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        add(context.createDeviceProtectedStorageContext())
                    }
                    add(context.applicationContext)
                }
            for (ctx in candidates) {
                val dir = ctx.cacheDir ?: ctx.filesDir ?: continue
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (dir.exists()) {
                    val file = File(dir, CRASH_FILE_NAME)
                    if (!checkExistingOnly || file.exists()) {
                        return file
                    }
                }
            }
            return null
        }

        /**
         * Adds the Sentry event id to the crash just saved (last_crash.txt and the newest history
         * entry, if written in the last minute). The sync script maps it to the Sentry issue.
         */
        fun noteSentryEvent(
            context: Context,
            eventId: String,
        ) {
            val line = "Sentry event: $eventId\n"
            val recent = System.currentTimeMillis() - 60_000
            try {
                getCrashFile(context, checkExistingOnly = true)
                    ?.takeIf { it.lastModified() >= recent }
                    ?.appendText(line)
                getCrashHistory(context)
                    .firstOrNull()
                    ?.takeIf { it.timestamp >= recent }
                    ?.file
                    ?.appendText(line)
            } catch (e: Exception) {
                android.util.Log.w("CrashHandler", "Couldn't note Sentry event: ${e.message}")
            }
        }

        fun getLastCrashReport(context: Context): String? {
            val file = getCrashFile(context, checkExistingOnly = true) ?: return null
            if (!file.exists()) return null
            return try {
                file.readText()
            } catch (_: Exception) {
                null
            }
        }

        fun clearLastCrash(context: Context) {
            val candidates =
                buildList {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        add(context.createDeviceProtectedStorageContext())
                    }
                    add(context.applicationContext)
                }
            for (ctx in candidates) {
                val dir = ctx.cacheDir ?: ctx.filesDir ?: continue
                val file = File(dir, CRASH_FILE_NAME)
                if (file.exists()) file.delete()
            }
        }
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            saveCrashReport(thread, throwable)
        } catch (e: Exception) {
            // Use android.util.Log, NOT Timber — Timber routes ERROR-level logs to Sentry,
            // which would create a spurious FileNotFoundException event instead of the real crash.
            android.util.Log.w("CrashHandler", "Failed to save crash report: ${e.message}")
        }

        // Call default handler (usually Sentry or Android system)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashReport(
        thread: Thread,
        throwable: Throwable,
    ) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val report = StringBuilder()
        report.append("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        report.append("Thread: ${thread.name} (id: ${thread.id})\n")
        report.append("Exception: ${throwable.javaClass.name}\n")
        report.append("Message: ${throwable.message}\n\n")
        report.append("Stacktrace:\n")
        report.append(stackTrace)

        try {
            val file = getCrashFile(context) ?: return
            // cacheDir may not exist on fresh installs or after a storage mount issue
            val parent = file.parentFile
            if (parent != null) {
                if (parent.exists() && !parent.isDirectory) {
                    parent.delete()
                }
                if (!parent.exists()) {
                    if (!parent.mkdirs()) {
                        return
                    }
                }
            }
            if (parent == null) return

            file.writeText(report.toString())
        } catch (e: Exception) {
            android.util.Log.w("CrashHandler", "Error writing crash file: ${e.message}")
        }

        try {
            val dir = historyDir(context)
            if (dir.isDirectory || dir.mkdirs()) {
                File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(report.toString())
                getCrashHistory(context).drop(HISTORY_MAX).forEach { it.file.delete() }
            }
        } catch (e: Exception) {
            android.util.Log.w("CrashHandler", "Error writing crash history: ${e.message}")
        }
    }
}
