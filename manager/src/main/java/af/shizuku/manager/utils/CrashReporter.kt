package af.shizuku.manager.utils

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CrashReporter {
    /**
     * [crashes]: logged crashes to include (from [CrashHandler.getCrashHistory]); null keeps the
     * old behaviour of attaching only the last crash, empty attaches none.
     */
    fun generateReport(
        context: Context,
        crashes: List<LoggedCrash>? = null,
    ): String {
        val sb = StringBuilder()

        // 1. Header
        sb.append("## Shizuku+ Manual Crash Report\n\n")
        sb.append("Please describe what you were doing when the crash occurred.\n\n")

        // 2. Crashes: the ones picked from the history, or the last persistent one
        if (crashes != null) {
            if (crashes.isNotEmpty()) sb.append("### Logged Crashes (${crashes.size})\n")
            crashes.forEach { crash ->
                val text =
                    try {
                        crash.file.readText()
                    } catch (_: Exception) {
                        return@forEach
                    }
                sb.append("#### ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(crash.timestamp)))
                sb.append(" - ").append(crash.exception).append("\n")
                sb.append("```text\n").append(text).append("```\n\n")
            }
        }
        val lastCrash = if (crashes == null) CrashHandler.getLastCrashReport(context) else null
        if (lastCrash != null) {
            sb.append("### Last Persistent Crash Detected\n")
            sb.append("```text\n")
            sb.append(lastCrash)
            sb.append("```\n\n")
        }

        // 3. Device Information
        sb.append("### Device Info\n")
        sb.append("- **Device:** ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")
        sb.append("- **Android Version:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        if (EnvironmentUtils.isSamsung()) {
            sb.append("- **One UI Version:** ${EnvironmentUtils.getOneUiVersion()}\n")
        }
        if (EnvironmentUtils.isOppo() || EnvironmentUtils.isOnePlus()) {
            sb.append("- **ColorOS/OxygenOS Version:** ${EnvironmentUtils.getColorOsVersion()}\n")
        }
        if (EnvironmentUtils.isXiaomi()) {
            sb.append("- **HyperOS/MIUI Version:** ${EnvironmentUtils.getHyperOsVersion()}\n")
        }
        if (EnvironmentUtils.isTCL()) {
            sb.append("- **TCL Device Detected**\n")
        }
        sb.append("- **Shizuku+ Version:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("- **Rooted:** ${Shell.isAppGrantedRoot()}\n")

        // Detailed Diagnostics (from Service Doctor logic)
        sb.append("- **Battery Optimization:** ${SettingsHelper.isIgnoringBatteryOptimizations(context)}\n")
        sb.append("- **Wireless ADB Port:** ${EnvironmentUtils.getAdbTcpPort()}\n")
        sb.append("- **Write Secure Settings:** ${SettingsHelper.hasWriteSecureSettings(context)}\n")

        sb.append("- **Timestamp:** ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")

        // 4. Activity Logs (Breadcrumbs)
        sb.append("### Recent Activity Logs\n")
        val recentLogs = ActivityLogManager.logs.value.takeLast(15)
        if (recentLogs.isNotEmpty()) {
            sb.append("```text\n")
            recentLogs.forEach { log ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))
                sb
                    .append("[")
                    .append(time)
                    .append("] ")
                    .append(log.packageName)
                    .append(": ")
                    .append(log.action)
                    .append("\n")
            }
            sb.append("```\n\n")
        } else {
            sb.append("_No activity logs recorded._\n\n")
        }

        // 5. Shizuku State
        sb.append("### Shizuku State\n")
        sb.append("- **State:** ${ShizukuStateMachine.get()}\n")
        sb.append("- **Watchdog Enabled:** ${ShizukuSettings.getWatchdog()}\n\n")

        // 6. Logs (Logcat tail)
        sb.append("### Logs (Last 300 lines)\n")
        sb.append("```text\n")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "300"))
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        sb.append(line).append("\n")
                    }
                }
                process.waitFor()
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            sb.append("Failed to capture logcat: ").append(e.message).append("\n")
        }
        sb.append("```\n")

        // Reports end up in public GitHub issues: don't reveal which other apps this person uses,
        // or their addresses and network names.
        val otherApps =
            ActivityLogManager.logs.value
                .map { it.packageName }
                .filter { it.isNotBlank() && it != context.packageName && it != "android" }
                .distinct()
        return TelemetryScrubber.scrub(anonymizePackages(sb.toString(), otherApps)).orEmpty()
    }

    /**
     * Generate a pre-filled GitHub issue URL
     */
    fun getGitHubReportUrl(
        context: Context,
        crashes: List<LoggedCrash>? = null,
    ): String {
        val report = generateReport(context, crashes)
        val title = "Manual Crash Report: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        return "https://github.com/thejaustin/ShizukuPlus/issues/new" +
            "?title=" + Uri.encode(title) +
            "&body=" + Uri.encode(report)
    }

    /**
     * Share the report as a .txt file
     */
    fun shareAsFile(
        context: Context,
        crashes: List<LoggedCrash>? = null,
    ) {
        val report = generateReport(context, crashes)
        val dir = context.cacheDir ?: context.filesDir ?: return
        val file = File(dir, "shizuku_plus_crash_report.txt")
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (parent == null || parent.exists()) {
            try {
                file.writeText(report)
            } catch (_: Exception) {
                // cacheDir may be unwritable (full disk, storage mount race); don't crash
                // the app while it's trying to report a crash.
                return
            }
        } else {
            return
        }

        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(android.content.Intent.createChooser(intent, "Share Crash Report"))
    }
}

/**
 * Replaces each of [packages] in [text] with "app N" (numbered in order), matching whole names
 * only, so com.example doesn't eat com.example.plugin and vice versa.
 */
internal fun anonymizePackages(
    text: String,
    packages: List<String>,
): String {
    var out = text
    // Longest first, so a longer name is replaced before a shorter prefix of it.
    val numbered = packages.distinct().withIndex().associate { (i, pkg) -> pkg to "app ${i + 1}" }
    for (pkg in numbered.keys.sortedByDescending { it.length }) {
        out = out.replace(Regex("(?<![\\w.])" + Regex.escape(pkg) + "(?![\\w.]*\\w)"), numbered.getValue(pkg))
    }
    return out
}
