package af.shizuku.manager.database

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

object RootCompatHelper {

    /** Returns true if [packageName] can be auto-configured via global settings (no root needed).
     *  These apps read their SU path from a global settings key that the ADB shell can write. */
    fun canAutoSetupInAdbMode(packageName: String): Boolean = packageName in GLOBAL_SETTINGS_APPS

    /** Returns true if [packageName] supports Magic Setup in the current privilege mode.
     *  Pass [rootMode] = true when Shizuku is running as UID 0.
     *  This is the single source of truth for whether the Magic Setup button should be enabled. */
    fun canAutoSetup(packageName: String, rootMode: Boolean): Boolean =
        packageName in GLOBAL_SETTINGS_APPS || (rootMode && packageName in ROOT_PREFS_APPS)

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
        "com.keramidas.TitaniumBackup"    to Pair("TitaniumBackup-preferences", "suCommand"),
        "com.speedsoftware.rootexplorer" to Pair("RootExplorer", "SuCommandLine"),
        "pl.solidexplorer2"              to Pair("SolidExplorer2", "su_binary_path"),
        "com.ghisler.android.TotalCommander" to Pair("tcandroid3", "supath"),
        "com.jrummy.root.browserfree"    to Pair("es_preferences", "su_path"),
        "com.estrongs.android.pop"       to Pair("es_preferences", "su_path"),
        "com.github.machiav3lli.backup"  to Pair("com.github.machiav3lli.backup_preferences", "custom_su_path")
        // Swift Backup (org.swiftapps.swiftbackup) intentionally has no entry here: reverse-
        // engineering its 5.1.0 APK found it never reads a custom su-binary path from its own
        // SharedPreferences - root access goes through libsu's Shell.Builder, which just invokes
        // plain PATH-resolved "su". There is no su_path/custom_su/suCommand-style key anywhere in
        // its bytecode for this (or any prior) entry to have matched, so autoSetup() correctly
        // falls through to the "no automatic path" branch for it. It IS a genuine Shizuku client
        // (own rikka.shizuku.ShizukuProvider at authority org.swiftapps.swiftbackup.shizuku,
        // package-agnostic binder handshake) - point users at its own Settings > grant-permissions
        // flow ("Grant with Root or Shizuku") instead of SU Bridge auto-setup for this app.
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
                    // Force-stop first: a running app periodically flushes its in-memory
                    // SharedPreferences to disk, which would overwrite the edit we are about to
                    // make. Stopping it ensures the on-disk file is stable before we touch it.
                    executePrivileged(arrayOf("am", "force-stop", packageName))
                    val escapedPath = escapeShellSingleQuote(escapeSed(suPath))
                    val escapedKey  = escapeSed(prefsKey)
                    val target = "/data/data/$packageName/shared_prefs/$prefsFile.xml"
                    // Replace existing value or append before </map> if key is absent.
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
                    // Not in either map — canAutoSetup() should be checked before calling this.
                    success = false
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

            // Only count apps we actually auto-configured. Non-automatable apps (the vast majority of
            // what's installed) can't be set up from here — we don't know their SU-path storage format
            // — so they must NOT inflate the count, or the "configured N apps" toast claims to have
            // set up every app on the device.
            if (pkg in automatable && autoSetup(context, pkg, effectiveSuPath)) {
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
    /** Result of [deployBridgeToTmp]: [suPath] is the deployed `su` path on success; [failureDetail]
     *  carries the exit code/stderr of whichever asset write failed, for callers (like [selfTest])
     *  that need to show *why* the deploy failed instead of a generic message. */
    data class DeployResult(val suPath: String?, val failureDetail: String? = null)

    suspend fun deployBridgeToTmp(context: Context): String? =
        deployBridgeToTmpDetailed(context).suPath

    suspend fun deployBridgeToTmpDetailed(context: Context): DeployResult = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) return@withContext DeployResult(null, "Shizuku binder not available")

        // Android 16+ (API 36) tightened the SELinux policy for the ADB/shell process (uid 2000),
        // denying writes to /data/local/tmp. Skip all 4 write attempts immediately to avoid a
        // multi-second stall — each cat > file times out waiting for the shell to report EACCES.
        // selfTest() already explains this to the user and directs them to the exported path.
        // (SHIZUKUPLUS-8A/8G/8D — all Android 16 non-rooted devices hitting this.)
        val serverUid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
        if (serverUid == 2000 && android.os.Build.VERSION.SDK_INT >= 36) {
            return@withContext DeployResult(
                null,
                "Android 16+ ADB/shell mode: /data/local/tmp is not writable from the shell " +
                "process (SELinux policy). Use the exported path instead."
            )
        }

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
                val result = streamToPrivilegedFile(bytes, "$dir/$name", mode)
                if (result.exitCode != 0) {
                    // WARN not ERROR: failure here is expected on devices where the ADB shell (uid 2000)
                    // lacks write access to /data/local/tmp (SELinux, read-only remount, etc.) — the
                    // reason is shown to the user via selfTest's failureDetail; no Sentry event needed.
                    // SHIZUKUPLUS-8A/8G/8D were all non-rooted devices hitting this on Android 16.
                    val detail = "failed to write $dir/$name (exit=${result.exitCode}, stderr=${result.stderr.take(500)})"
                    Timber.w("deployBridgeToTmp: $detail")
                    return@withContext DeployResult(null, detail)
                }
            }
            DeployResult("$dir/su")
        } catch (e: Exception) {
            Timber.e(e, "deployBridgeToTmp failed")
            DeployResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Writes [bytes] to [path] via a privileged `cat`, then chmods it. Streams over stdin so no
     *  cross-UID file read is needed (works in ADB mode, not just root). */
    private fun streamToPrivilegedFile(bytes: ByteArray, path: String, mode: String): ShizukuCaptureResult {
        if (!Shizuku.pingBinder()) return ShizukuCaptureResult(-1, "", "Shizuku binder not available")
        val escaped = escapeShellSingleQuote(path)
        // rm -f first: a prior deploy may have left the file as read-only (444 for the dex),
        // and `cat >` would fail with EACCES even for the file's own owner. outputStream is
        // the child's stdin; writing then closing sends EOF so `cat` completes.
        return ShizukuProcessUtils.runPrivilegedCapture(
            arrayOf("sh", "-c", "rm -f '$escaped' && cat > '$escaped' && chmod $mode '$escaped'"),
            joinTimeoutMs = 500,
            writeStdin = { it.use { stream -> stream.write(bytes) } }
        )
    }

    /** Result of [selfTest]: [ok] is a coarse pass/fail; [report] is a human-readable multi-line
     *  summary meant to be shown verbatim in a dialog. */
    data class BridgeSelfTest(val ok: Boolean, val report: String)

    /**
     * Diagnoses the SU Bridge on THIS device without needing a third-party app. Runs two probes:
     *
     *  A. **Deploy + privilege (via Shizuku — reliable).** Deploys to /data/local/tmp, lists the
     *     files, and reads the *real* uid from `/proc/self/status`. We can't use `id`/`whoami` — the
     *     server intercepts those and spoofs `uid=0(root)` for root-detection, so they'd lie about
     *     the true privilege ceiling (shell/ADB = uid 2000 vs. real root = uid 0).
     *
     *  B. **App-side exec (best-effort).** Has *this app's own process* exec the deployed `su`, which
     *     is the exact mechanism a third-party app uses: app_process then runs at the app's uid, so
     *     the server's package↔uid check in `attachApplication` passes for our own package. This is
     *     the only way to exercise the real attach from inside the app. Some ROMs' SELinux blocks an
     *     untrusted app from exec'ing app_process or reading `shell_data_file` in /data/local/tmp; if
     *     so we report that honestly instead of as a bridge bug — it tells the user the deploy
     *     location won't work for exec-style callers on their device.
     */
    suspend fun selfTest(context: Context): BridgeSelfTest = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) {
            return@withContext BridgeSelfTest(false,
                "Shizuku isn't connected. Start the Shizuku service from the home screen, then try again.")
        }
        val deployResult = deployBridgeToTmpDetailed(context)
        val tmpSu = deployResult.suPath
            ?: run {
                val serverUid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
                val detail = deployResult.failureDetail?.let { "Reason: $it\n\n" } ?: ""
                val action = if (serverUid == 2000) {
                    // On Android 16+ the SELinux policy for the shell (ADB) process was tightened
                    // to deny writes to /data/local/tmp (SHIZUKUPLUS-8A/8G/8D). This is expected
                    // and the bridge still functions via the user-exported path.
                    "ADB/shell mode detected. Android 16+ restricts writes to /data/local/tmp from " +
                        "the shell process — this is expected. The SU Bridge still works via your " +
                        "exported path. Tap \"Export\" in the compatibility hub and direct root apps " +
                        "to that path, or switch to root mode for full /data/local/tmp access."
                } else {
                    "Make sure the Shizuku service is running, then retry."
                }
                return@withContext BridgeSelfTest(false,
                    "❌ Could not deploy the bridge to /data/local/tmp.\n\n$detail$action")
            }
        val tmpDir = tmpSu.substringBeforeLast('/')

        // Probe A — deploy check + true privilege, via Shizuku.
        val (_, lsOut, _) = runPrivilegedCapture(arrayOf("sh", "-c",
            "ls -l '$tmpDir'/su '$tmpDir'/rish '$tmpDir'/plus '$tmpDir'/rish_shizuku.dex 2>&1; " +
                "echo '---'; grep -m1 '^Uid:' /proc/self/status"))
        val deployed = lsOut.isNotBlank() && !lsOut.contains("No such file")
        val uid = Regex("Uid:\\s+(\\d+)").find(lsOut)?.groupValues?.get(1)?.toIntOrNull()
        val privLabel = when (uid) {
            0 -> "root (uid 0) — full privileges"
            2000 -> "shell / ADB (uid 2000)"
            null -> "unknown"
            else -> "uid $uid"
        }

        val sb = StringBuilder()
        sb.append(if (deployed) "✅ Bridge deployed to $tmpDir\n" else "❌ Bridge files missing under $tmpDir\n")
        sb.append("• Privilege level: $privLabel\n")

        // Probe B — real app-exec flow (best-effort).
        val appExec = try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "$tmpDir/su", "-c",
                "echo APP_OK; grep -m1 '^Uid:' /proc/self/status"))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (out.contains("APP_OK")) {
                val auid = Regex("Uid:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
                "✅ ran end-to-end (uid ${auid ?: "?"})"
            } else {
                "⚠️ didn't round-trip — an exec-style app may fail here:\n${(out + err).trim().take(220)}"
            }
        } catch (e: Exception) {
            "⚠️ blocked on this device (likely SELinux): ${e.message?.take(160)}"
        }
        sb.append("• App-exec test: $appExec\n\n")

        if (uid == 0) {
            sb.append("Root-level bridge: apps needing true root can work through it.")
        } else {
            sb.append("Shell-level bridge (like ADB): app features needing only shell/ADB will work; " +
                "features that require true root — e.g. reading another app's private data — cannot, " +
                "even though the app may detect \"root\".")
        }
        sb.append("\n\nRemember: the calling app must be authorized in Shizuku+ before its own commands " +
            "through the bridge succeed.")

        BridgeSelfTest(deployed && uid != null, sb.toString())
    }

    /** Runs a privileged command and returns (exitCode, stdout, stderr) via the shared
     *  [ShizukuProcessUtils.runPrivilegedCapture]. Unlike [executePrivileged] this captures
     *  output, which the self-test needs. */
    private fun runPrivilegedCapture(cmd: Array<String>): Triple<Int, String, String> {
        val result = ShizukuProcessUtils.runPrivilegedCapture(cmd, joinTimeoutMs = 1500)
        return Triple(result.exitCode, result.stdout, result.stderr)
    }

    private fun executePrivileged(cmd: Array<String>): Boolean {
        if (!Shizuku.pingBinder()) {
            Timber.w("RootCompatHelper: Shizuku binder not available, skipping command")
            return false
        }
        return ShizukuProcessUtils.runPrivilegedCapture(cmd, joinTimeoutMs = 500).exitCode == 0
    }
}
