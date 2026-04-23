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

    private fun escapeSed(s: String) = s
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("&", "\\&")

    private fun escapeShellSingleQuote(s: String) = s.replace("'", "'\\''")

    /**
     * Automatically configures popular root apps to use the Shizuku+ SU Bridge.
     * Uses Shizuku's privileged shell to modify target app preferences.
     */
    suspend fun autoSetup(context: Context, packageName: String, suPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeReplace = escapeSed(suPath)
            when (packageName) {
                "org.adaway" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "adaway_su_path", suPath))
                    true
                }
                "dev.ukanth.ufirewall" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "afwall_su_path", suPath))
                    true
                }
                "com.machiav3lli.neo_backup", "org.swiftapps.swiftbackup" -> {
                    val script = "find '/data/data/$packageName/shared_prefs' -name '*.xml' -type f | xargs sed -i 's|<string name=\"custom_shell_path\">.*</string>|<string name=\"custom_shell_path\">$safeReplace</string>|g'"
                    executePrivileged(arrayOf("sh", "-c", script))
                    true
                }
                "com.keramidas.TitaniumBackup" -> {
                    val script = "find '/data/data/$packageName/shared_prefs' -name '*.xml' -type f | xargs sed -i 's|<string name=\"ex_su_path\">.*</string>|<string name=\"ex_su_path\">$safeReplace</string>|g'"
                    executePrivileged(arrayOf("sh", "-c", script))
                    true
                }
                "com.speedsoftware.explorer" -> {
                    val script = "find '/data/data/$packageName/shared_prefs' -name '*.xml' -type f | xargs sed -i 's|<string name=\"su_path\">.*</string>|<string name=\"su_path\">$safeReplace</string>|g'"
                    executePrivileged(arrayOf("sh", "-c", script))
                    true
                }
                "eu.darken.sdm", "eu.darken.sdmse" -> {
                    val script = "find '/data/data/$packageName/shared_prefs' -name '*.xml' -type f | xargs sed -i 's|/system/[^/ ]*/su|$safeReplace|g'"
                    executePrivileged(arrayOf("sh", "-c", script))
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
            val safeReplace = escapeSed(suPath)
            val script = "find '/data/data/$packageName/shared_prefs' -name '*.xml' -type f 2>/dev/null | xargs -r sed -i 's|/system/[^/ ]*/su|$safeReplace|g'"
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
            "com.keramidas.TitaniumBackup", "com.speedsoftware.explorer",
            "com.jrummy.root.browserfree", "projekt.substratum.lite",
            "com.oasisfeng.greenify", "com.franco.doze",
            "com.uzumapps.wakelockdetector", "com.asksven.betterbatterystats",
            "com.jrummy.apps.build.prop.editor", "com.paget96.chargemonitor",
            "com.zacharee.tweaker", "com.samsung.android.themepark",
            "com.samsung.android.hexinstall", "bin.mt.plus",
            "net.dinglisch.android.taskerm", "pl.solidexplorer2",
            "com.mixplorer", "com.mixplorer.silver", "nextapp.fx"
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
