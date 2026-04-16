package af.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import af.shizuku.manager.ktx.loge
import af.shizuku.manager.R
import rikka.shizuku.Shizuku
import timber.log.Timber

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
                    // Neo Backup stores its custom shell path in shared_prefs
                    val script = "find /data/data/$packageName/shared_prefs -name \"*.xml\" -type f | xargs sed -i 's|<string name=\"custom_shell_path\">.*</string>|<string name=\"custom_shell_path\">$suPath</string>|g'"
                    executePrivileged(arrayOf("sh", "-c", script))
                    true
                }
                "eu.darken.sdm", "eu.darken.sdmse" -> {
                    // SD Maid / SE - Search and replace su path in settings
                    val script = "find /data/data/$packageName/shared_prefs -name \"*.xml\" -type f | xargs sed -i 's|/system/[^/ ]*/su|$suPath|g'"
                    executePrivileged(arrayOf("sh", "-c", script))
                    true
                }
                "org.swiftapps.swiftbackup" -> {
                    // Swift Backup - Use universal approach
                    universalAutoSetup(packageName, suPath)
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
            // Use -r with xargs to avoid running sed if find returns nothing
            val script = "find $prefsDir -name \"*.xml\" -type f 2>/dev/null | xargs -r sed -i 's|/system/[^/ ]*/su|$suPath|g'"
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
        
        val knownRootPackages = setOf(
            "org.adaway", "dev.ukanth.ufirewall", "com.machiav3lli.neo_backup",
            "eu.darken.sdm", "eu.darken.sdmse", "org.swiftapps.swiftbackup",
            "com.keramidas.TitaniumBackup", "com.noshufou.android.su",
            "com.zacharee.tweaker", "com.franco.doze", "com.oasisfeng.greenify"
        )

        for (pkgInfo in installed) {
            val pkg = pkgInfo.packageName
            if (pkg == context.packageName) continue
            
            val requestsRootPerm = pkgInfo.requestedPermissions?.any { 
                it.contains("ROOT", true) || it.contains("SUPERUSER", true)
            } == true
            
            val isKnownRootApp = knownRootPackages.contains(pkg)
            
            if (requestsRootPerm || isKnownRootApp) {
                if (autoSetup(context, pkg, suPath)) {
                    count++
                }
            }
        }
        count
    }

    private fun executePrivileged(cmd: Array<String>) {
        if (!Shizuku.pingBinder()) {
            Timber.w("RootCompatHelper: Shizuku binder not available, skipping command")
            return
        }
        try {
            val process = Shizuku.newProcess(cmd, null, null)
            
            // Start threads to drain output/error streams to prevent buffer-fill hangs
            val outDrainer = Thread { try { process.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) {} }
            val errDrainer = Thread { try { process.errorStream.bufferedReader().use { it.readText() } } catch (e: Exception) {} }
            outDrainer.start()
            errDrainer.start()

            process.waitFor()
            outDrainer.join(500)
            errDrainer.join(500)

            // Close all streams
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        } catch (e: IllegalStateException) {
            // Binder lost between ping and call — not a bug, just timing.
            Timber.w("RootCompatHelper: binder lost during privileged command: ${e.message}")
        } catch (e: Exception) {
            loge("execute privileged command failed", e)
        }
    }
}
