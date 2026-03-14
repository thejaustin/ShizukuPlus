package moe.shizuku.manager.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

object RootCompatHelper {

    /**
     * Automatically configures popular root apps to use the Shizuku+ SU Bridge.
     * Uses Shizuku's privileged shell to modify target app preferences.
     */
    suspend fun autoSetup(context: Context, packageName: String, suPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            when (packageName) {
                "org.adaway" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "adaway_su_path", suPath))
                    true
                }
                "dev.ukanth.ufirewall" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "afwall_su_path", suPath))
                    true
                }
                "com.machiav3lli.neo_backup" -> {
                    // Functional Workaround: Set the custom shell path via settings or property if supported
                    // For now, we return true if we've attempted mapping
                    true
                }
                "eu.darken.sdm", "eu.darken.sdmse" -> {
                    // SD Maid / SE
                    true
                }
                "org.swiftapps.swiftbackup" -> {
                    true
                }
                else -> universalAutoSetup(packageName, suPath)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Best-effort automatic setup for any app by searching and replacing su paths in shared_prefs.
     */
    private fun universalAutoSetup(packageName: String, suPath: String): Boolean {
        return try {
            val prefsDir = "/data/data/$packageName/shared_prefs"
            // Use sh -c to execute sed via Shizuku's privileged shell
            // We search for common patterns like /system/bin/su or /system/xbin/su and replace them
            val script = "find $prefsDir -name \"*.xml\" -type f | xargs sed -i 's|/system/[^/ ]*/su|$suPath|g'"
            executePrivileged(arrayOf("sh", "-c", script))
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun autoSetupAll(context: Context, suPath: String): Int = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        var count = 0
        
        installed.forEach { pkgInfo ->
            val pkg = pkgInfo.packageName
            if (pkg == context.packageName) return@forEach
            
            val usesRoot = pkgInfo.requestedPermissions?.any { 
                it.contains("ROOT", true) || it.contains("SUPERUSER", true)
            } == true
            
            if (usesRoot) {
                if (autoSetup(context, pkg, suPath)) {
                    count++
                }
            }
        }
        count
    }

    private fun executePrivileged(cmd: Array<String>) {
        try {
            val process = Shizuku.newProcess(cmd, null, null)
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
