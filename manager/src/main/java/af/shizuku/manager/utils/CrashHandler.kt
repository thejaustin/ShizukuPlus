package af.shizuku.manager.utils

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler(private val context: Context, private val defaultHandler: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val CRASH_FILE_NAME = "last_crash.txt"

        fun getCrashFile(context: Context): File? {
            val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val dir = storageContext.cacheDir ?: storageContext.filesDir
            if (dir == null) return null
            return File(dir, CRASH_FILE_NAME)
        }

        fun getLastCrashReport(context: Context): String? {
            val file = getCrashFile(context) ?: return null
            if (!file.exists()) return null
            return try {
                file.readText()
            } catch (e: Exception) {
                null
            }
        }

        fun clearLastCrash(context: Context) {
            val file = getCrashFile(context) ?: return
            if (file.exists()) file.delete()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
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

    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
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
    }
}
