package af.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File

object DeviceOptimizer {

    private fun runCommand(cmd: String): Boolean {
        if (Shizuku.pingBinder()) {
            try {
                val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                if (p != null) {
                    val code = p.waitFor()
                    p.destroy()
                    return code == 0
                }
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Shizuku: $cmd")
            }
        }
        if (EnvironmentUtils.isRooted()) {
            try {
                return com.topjohnwu.superuser.Shell.cmd(cmd).exec().isSuccess
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Root: $cmd")
            }
        }
        return false
    }

    suspend fun applyFixes(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder() && !EnvironmentUtils.isRooted()) {
            return@withContext false
        }

        val pkgName = context.packageName

        // 1. Whitelist Shizuku from Doze / Battery Optimization
        runCommand("dumpsys deviceidle whitelist +$pkgName")

        // 2. Whitelist known terminal and companion packages if installed
        val clientPackages = listOf(
            "com.termux",
            "com.termux.api",
            "in.hridayan.ashell",
            "com.rubex.nfile"
        )
        val pm = context.packageManager
        for (client in clientPackages) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(client, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(client, 0)
                }
                runCommand("dumpsys deviceidle whitelist +$client")
            } catch (_: PackageManager.NameNotFoundException) {
                // Ignore uninstalled packages
            }
        }

        // 3. Grant WRITE_SECURE_SETTINGS to self if not granted
        if (!SettingsHelper.hasWriteSecureSettings(context)) {
            runCommand("pm grant $pkgName android.permission.WRITE_SECURE_SETTINGS")
        }

        // 4. Set AppOps for background resilience
        runCommand("appops set $pkgName SYSTEM_ALERT_WINDOW allow")
        runCommand("appops set $pkgName GET_USAGE_STATS allow")
        runCommand("appops set $pkgName SCHEDULE_EXACT_ALARM allow")

        // 5. Sync native starter binary to /data/local/tmp/shizuku
        try {
            val starterFile = File(context.applicationInfo.nativeLibraryDir, "libshizuku.so")
            if (starterFile.exists()) {
                val tmpPath = "/data/local/tmp/shizuku"
                runCommand("cp ${starterFile.absolutePath} $tmpPath")
                runCommand("chmod 755 $tmpPath")
            }
        } catch (e: Exception) {
            Timber.tag("DeviceOptimizer").w(e, "Failed to sync starter binary")
        }

        true
    }
}
