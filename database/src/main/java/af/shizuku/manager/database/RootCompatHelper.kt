package af.shizuku.manager.database

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

object RootCompatHelper {

    private fun escapeSed(s: String) = s
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("&", "\\&")

    private fun escapeShellSingleQuote(s: String) = s.replace("'", "'\\''")

    // Apps that store their SU path in Android global settings (accessible without root)
    private val GLOBAL_SETTINGS_APPS = mapOf(
        "org.adaway"           to "adaway_su_path",
        "dev.ukanth.ufirewall" to "afwall_su_path",
        "com.ramdaas.ramexe"   to "ramexe_su_path",
        "me.piebridge.prevent"  to "prevent_su_path"
    )

    // Apps that store their SU path in shared_prefs; only reachable with UID 0 (root Shizuku).
    // Format: package → Pair(prefs file basename, XML key name)
    private val ROOT_PREFS_APPS = mapOf(
        "com.keramidas.TitaniumBackup" to Pair("TitaniumBackup-preferences", "suCommand"),
        "com.speedsoftware.rootexplorer" to Pair("RootExplorer", "SuCommandLine"),
        "pl.solidexplorer2"              to Pair("SolidExplorer2", "su_binary_path"),
        "com.ghisler.android.TotalCommander" to Pair("tcandroid3", "supath"),
        "com.jrummy.root.browserfree"    to Pair("es_preferences", "su_path"),
        "com.estrongs.android.pop"       to Pair("es_preferences", "su_path")
    )

    /**
     * Automatically configures a root app to use the Shizuku+ SU Bridge.
     * Uses global settings for apps that support it; falls back to direct shared_prefs
     * editing when Shizuku is running as root (UID 0).
     */
    suspend fun autoSetup(context: Context, packageName: String, suPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) return@withContext false

        var success = false
        try {
            val globalKey = GLOBAL_SETTINGS_APPS[packageName]
            val prefsEntry = ROOT_PREFS_APPS[packageName]

            when {
                globalKey != null -> {
                    success = executePrivileged(arrayOf("settings", "put", "global", globalKey, suPath))
                }
                prefsEntry != null && isShizukuRoot() -> {
                    // Root Shizuku (UID 0) can directly edit another app's shared_prefs.
                    val (prefsFile, prefsKey) = prefsEntry
                    val escapedPath = escapeSed(escapeShellSingleQuote(suPath))
                    val escapedKey  = escapeSed(prefsKey)
                    val target = "/data/data/$packageName/shared_prefs/$prefsFile.xml"
                    // Replace existing value or append if key is absent
                    val cmd = """
                        if [ -f '$target' ]; then
                            if grep -q 'name="$escapedKey"' '$target'; then
                                sed -i 's|<string name="$escapedKey">.*</string>|<string name="$escapedKey">$escapedPath</string>|' '$target'
                            else
                                sed -i 's|</map>|    <string name="$escapedKey">$escapedPath</string>\n</map>|' '$target'
                            fi
                        fi
                    """.trimIndent()
                    success = executePrivileged(arrayOf("sh", "-c", cmd))
                }
                else -> {
                    // No automatic path; UI will guide the user through manual setup.
                    success = true
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "autoSetup failed for package $packageName")
            false
        }
        success
    }

    private fun isShizukuRoot(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.getUid() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun autoSetupAll(context: Context, suPath: String): Int = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) return@withContext 0

        // Prefer an exec-permitted deployment: /storage is usually noexec and app_process rejects a
        // writable dex on A14+, so a config pointing at the storage export often won't actually run.
        // Deploy to /data/local/tmp and point apps there when we can.
        val effectiveSuPath = deployBridgeToTmp(context) ?: suPath

        val pm = context.packageManager
        val installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        var processedCount = 0

        val automatable = GLOBAL_SETTINGS_APPS.keys + if (isShizukuRoot()) ROOT_PREFS_APPS.keys else emptySet()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName
            if (pkg == context.packageName) continue

            if (pkg in automatable) {
                if (autoSetup(context, pkg, effectiveSuPath)) processedCount++
            } else {
                // Falls through to manual-guidance UI path.
                processedCount++
            }
        }
        processedCount
    }

    /**
     * Deploys the SU Bridge (su/rish/plus + rish_shizuku.dex) to /data/local/tmp via Shizuku.
     *
     * This is strictly better than the user-picked storage export for making the bridge actually
     * work with third-party apps:
     *  - /data/local/tmp is exec-permitted, whereas shared storage (/sdcard) is usually mounted
     *    noexec, so apps that exec the su path directly fail from storage.
     *  - The dex is written 0444 (read-only), which app_process requires on Android 14+ (it refuses
     *    a writable dex); FAT/exFAT SD cards can't hold unix perms at all.
     *
     * Each asset is streamed over the privileged process's stdin (`cat > file`) so it works in both
     * root and ADB mode without the shell needing to read the app's private files. Returns the
     * /data/local/tmp/su path on success, or null on failure.
     */
    suspend fun deployBridgeToTmp(context: Context): String? = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) return@withContext null
        val dir = "/data/local/tmp"
        // asset name -> octal mode (scripts executable; dex read-only for app_process on A14+)
        val files = listOf(
            "su" to "755",
            "rish" to "755",
            "plus" to "755",
            "rish_shizuku.dex" to "444"
        )
        try {
            for ((name, mode) in files) {
                val bytes = context.assets.open(name).use { it.readBytes() }
                if (!streamToPrivilegedFile(bytes, "$dir/$name", mode)) {
                    Timber.e("deployBridgeToTmp: failed to write $dir/$name")
                    return@withContext null
                }
            }
            "$dir/su"
        } catch (e: Exception) {
            Timber.e(e, "deployBridgeToTmp failed")
            null
        }
    }

    /** Writes [bytes] to [path] via a privileged `cat`, then chmods it. Streams over stdin so no
     *  cross-UID file read is needed (works in ADB mode, not just root). */
    private fun streamToPrivilegedFile(bytes: ByteArray, path: String, mode: String): Boolean {
        if (!Shizuku.pingBinder()) return false
        return try {
            val escaped = escapeShellSingleQuote(path)
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "cat > '$escaped' && chmod $mode '$escaped'"), null, null
            )
            // Drain stdout/stderr concurrently so the child never blocks on a full pipe.
            val outDrainer = Thread { try { process.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) {} }
            val errDrainer = Thread { try { process.errorStream.bufferedReader().use { it.readText() } } catch (_: Exception) {} }
            outDrainer.start()
            errDrainer.start()
            // outputStream is the child's stdin; writing then closing sends EOF so `cat` completes.
            process.outputStream.use { it.write(bytes) }
            val exitCode = process.waitFor()
            outDrainer.join(500)
            errDrainer.join(500)
            try { process.inputStream.close() } catch (_: Exception) {}
            try { process.errorStream.close() } catch (_: Exception) {}
            exitCode == 0
        } catch (e: Exception) {
            Timber.e(e, "streamToPrivilegedFile failed for $path")
            false
        }
    }

    private fun executePrivileged(cmd: Array<String>): Boolean {
        if (!Shizuku.pingBinder()) {
            Timber.w("RootCompatHelper: Shizuku binder not available, skipping command")
            return false
        }
        return try {
            val process = Shizuku.newProcess(cmd, null, null)
            
            // Start threads to drain output/error streams to prevent buffer-fill hangs
            val outDrainer = Thread { try { process.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { Timber.w(e, "RootCompatHelper: Error draining output stream") } }
            val errDrainer = Thread { try { process.errorStream.bufferedReader().use { it.readText() } } catch (e: Exception) { Timber.w(e, "RootCompatHelper: Error draining error stream") } }
            outDrainer.start()
            errDrainer.start()

            val exitCode = process.waitFor()
            outDrainer.join(500)
            errDrainer.join(500)

            // Close all streams
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
            
            exitCode == 0
        } catch (e: IllegalStateException) {
            // Binder lost between ping and call — not a bug, just timing.
            Timber.w("RootCompatHelper: binder lost during privileged command: ${e.message}")
            false
        } catch (e: Exception) {
            Timber.e(e, "execute privileged command failed")
            false
        }
    }
}
