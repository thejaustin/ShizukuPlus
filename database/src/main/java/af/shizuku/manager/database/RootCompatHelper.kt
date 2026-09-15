package af.shizuku.manager.database

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

object RootCompatHelper {

    /** Returns true if [packageName] can be auto-configured via global settings (no root needed).
     *  These apps read their SU path from a global settings key that the ADB shell can write. */
    fun canAutoSetupInAdbMode(packageName: String): Boolean = packageName in GLOBAL_SETTINGS_APPS

    /** Returns true if [packageName] supports Magic Setup in the current privilege mode.
     *  Pass [rootMode] = true when Shizuku is running as UID 0 or ADB mode (UID 2000).
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

    // Apps that store their SU path in shared_prefs; reachable with UID 0 (root Shizuku) or
    // via privileged shell / run-as in ADB mode.
    // Format: package → Pair(prefs file basename, XML key name)
    private val ROOT_PREFS_APPS = mapOf(
        "com.keramidas.TitaniumBackup"    to Pair("TitaniumBackup-preferences", "suCommand"),
        "com.speedsoftware.rootexplorer" to Pair("RootExplorer", "SuCommandLine"),
        "pl.solidexplorer2"              to Pair("SolidExplorer2", "su_binary_path"),
        "com.ghisler.android.TotalCommander" to Pair("tcandroid3", "supath"),
        "com.jrummy.root.browserfree"    to Pair("es_preferences", "su_path"),
        "com.estrongs.android.pop"       to Pair("es_preferences", "su_path"),
        "com.github.machiav3lli.backup"  to Pair("com.github.machiav3lli.backup_preferences", "custom_su_path")
    )

    /**
     * Automatically configures a root app to use the Shizuku+ SU Bridge.
     * Uses global settings for apps that support it; falls back to direct shared_prefs
     * editing when Shizuku is running as root (UID 0) or privileged ADB shell (UID 2000).
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
                prefsEntry != null -> {
                    // Shizuku (UID 0 root or UID 2000 ADB shell) edits another app's shared_prefs.
                    val (prefsFile, prefsKey) = prefsEntry
                    // Force-stop first: a running app periodically flushes its in-memory
                    // SharedPreferences to disk, which would overwrite the edit we are about to
                    // make. Stopping it ensures the on-disk file is stable before we touch it.
                    executePrivileged(arrayOf("am", "force-stop", packageName))
                    val escapedPath = escapeShellSingleQuote(escapeSed(suPath))
                    val escapedKey  = escapeSed(prefsKey)
                    val target = "/data/data/$packageName/shared_prefs/$prefsFile.xml"
                    // Replace existing value or append before </map> if key is absent.
                    // Also try run-as if direct shell access is blocked by permission on non-root.
                    val cmd = """
                        if [ -f '$target' ]; then
                            if grep -q 'name="$escapedKey"' '$target'; then
                                sed -i 's|<string name="$escapedKey">.*</string>|<string name="$escapedKey">$escapedPath</string>|' '$target'
                            else
                                sed -i 's|</map>|    <string name="$escapedKey">$escapedPath</string>\n</map>|' '$target'
                            fi
                        else
                            run-as $packageName sh -c "if [ -f shared_prefs/$prefsFile.xml ]; then if grep -q 'name=\"$escapedKey\"' shared_prefs/$prefsFile.xml; then sed -i 's|<string name=\"$escapedKey\">.*</string>|<string name=\"$escapedKey\">$escapedPath</string>|' shared_prefs/$prefsFile.xml; else sed -i 's|</map>|    <string name=\"$escapedKey\">$escapedPath</string>\n</map>|' shared_prefs/$prefsFile.xml; fi; fi" 2>/dev/null
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
            Shizuku.pingBinder() && (Shizuku.getUid() == 0 || Shizuku.getUid() == 2000)
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

    const val ADB_CLEANUP_COMMAND =
        "adb shell rm -f /data/local/tmp/su /data/local/tmp/rish /data/local/tmp/plus /data/local/tmp/rish_shizuku.dex /data/local/su /data/local/bin/su /data/local/xbin/su"

    /**
     * Checks whether any SU Bridge or root residue binary is present in known detection paths.
     */
    suspend fun isBridgePresentInTmp(): Boolean = withContext(Dispatchers.IO) {
        val targets = listOf(
            "/data/local/tmp/su",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su"
        )
        if (targets.any { File(it).exists() }) return@withContext true
        if (isShizukuAvailable()) {
            try {
                val result = ShizukuProcessUtils.runPrivilegedCapture(
                    arrayOf("sh", "-c", "test -f /data/local/tmp/su || test -f /data/local/su && echo EXISTS"),
                    joinTimeoutMs = 500
                )
                return@withContext result.stdout.contains("EXISTS")
            } catch (_: Exception) {}
        }
        false
    }

    /**
     * Removes all SU Bridge artifacts and root residue from /data/local/tmp and /data/local.
     *
     * Multi-tier fallback architecture:
     *  1. Direct unprivileged file deletion.
     *  2. Privileged Shizuku shell removal.
     *  3. Direct root shell execution (`su -c rm -f ...`) if Shizuku is stopped but root exists.
     *  4. In-place zeroing/truncation (`> /data/local/tmp/su && chmod 000`) if unlinking is blocked.
     */
    suspend fun cleanupBridgeFromTmp(context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        val targets = listOf(
            "/data/local/tmp/su",
            "/data/local/tmp/rish",
            "/data/local/tmp/plus",
            "/data/local/tmp/rish_shizuku.dex",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su"
        )

        // Tier 1: Best-effort unprivileged deletion
        for (path in targets) {
            try {
                File(path).delete()
            } catch (_: Exception) {}
        }

        // Tier 2: Privileged Shizuku removal
        if (isShizukuAvailable()) {
            try {
                val cmd = arrayOf(
                    "sh", "-c",
                    "rm -f ${targets.joinToString(" ")}"
                )
                ShizukuProcessUtils.runPrivilegedCapture(cmd, joinTimeoutMs = 1000)
            } catch (e: Exception) {
                Timber.w(e, "cleanupBridgeFromTmp Shizuku rm failed")
            }
        }

        // Tier 3: Direct root shell fallback (if Shizuku is unavailable or rm failed, but device has root)
        if (File("/data/local/tmp/su").exists()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -f ${targets.joinToString(" ")}"))
                try { p.waitFor() } finally { p.destroy() }
            } catch (_: Exception) {}
        }

        // Tier 4: In-place zeroing / truncation and permission stripping if file still exists
        if (File("/data/local/tmp/su").exists() && isShizukuAvailable()) {
            try {
                val truncateCmd = arrayOf(
                    "sh", "-c",
                    "> /data/local/tmp/su 2>/dev/null; chmod 000 /data/local/tmp/su 2>/dev/null"
                )
                ShizukuProcessUtils.runPrivilegedCapture(truncateCmd, joinTimeoutMs = 500)
            } catch (_: Exception) {}
        }

        val stillExists = File("/data/local/tmp/su").exists()
        val success = !stillExists
        Timber.i("cleanupBridgeFromTmp finished, su exists=$stillExists, success=$success")
        success
    }

    /**
     * Describes what level of Play Integrity refresh was achieved.
     * Callers use this to show appropriate follow-up messaging and recovery options.
     */
    enum class WalletRefreshResult {
        /**
         * Shizuku root (uid 0): GMS DroidGuard/integrity verdict files deleted directly.
         * Wallet should recover on next launch with no further user action needed.
         */
        CACHE_CLEARED_ROOT,
        /**
         * Shizuku ADB shell (uid 2000): GMS data dir is SELinux-protected and unwritable by
         * shell, so the verdict file is still on disk. Best-effort steps taken:
         *   1. DroidGuard process (com.google.android.gms.unstable) force-stopped — clears
         *      in-memory verdict state so GMS must re-read or re-attest on next start.
         *   2. GMS core force-stopped.
         *   3. Wallet data cleared (pm clear) — forces Wallet to make a fresh Play Integrity
         *      API call on next launch rather than re-using its own cached result.
         *   4. NFC payment component re-asserted — pm clear on Wallet doesn't wipe this
         *      setting, but we write it explicitly as a safeguard.
         * Wallet may recover immediately (disk verdict can be re-evaluated after a cold
         * DroidGuard start). If still blocked, the verdict expires on TTL (~1 hour typical)
         * or the user can clear GMS data for guaranteed immediate recovery.
         */
        WALLET_CLEARED_PROCESSES_KILLED,
        /** Shizuku not available. Only unprivileged Wallet force-stop attempted. */
        FORCE_STOPPED_ONLY,
    }

    /**
     * Best-effort Play Integrity verdict refresh after su bridge cleanup.
     *
     * Play Integrity verdicts live in GMS's DATA directory (not cache), owned by GMS's UID
     * and guarded by SELinux. A "compromised" verdict from when the su bridge was present
     * persists through process restarts until its TTL expires or the files are deleted.
     *
     * Confirmed no-ops (tested on Android 16, intentionally omitted):
     *  - `pm clear-cache`: removed from Android 10+, returns "Unknown command" with exit 0.
     *  - `com.google.android.gms.INITIALIZE` broadcast: result=0, no registered receivers.
     *
     * What each mode actually does:
     *  - Root (uid 0): deletes verdict files directly → immediate recovery.
     *  - ADB shell (uid 2000): clears Wallet data + kills DroidGuard + kills GMS → gives
     *    Wallet the best chance at a fresh cold-start re-attestation; manual GMS data clear
     *    available as guaranteed fallback.
     *  - No Shizuku: unprivileged Wallet force-stop only.
     */
    suspend fun refreshGoogleWalletAttestation(context: Context? = null): WalletRefreshResult = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) {
            try { Runtime.getRuntime().exec(arrayOf("am", "force-stop", "com.google.android.apps.walletnfcrel")).waitFor() } catch (_: Exception) {}
            return@withContext WalletRefreshResult.FORCE_STOPPED_ONLY
        }

        val shizukuUid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
        val isRoot = shizukuUid == 0

        // Root path: delete verdict files directly.
        val cacheCleared = if (isRoot) clearPlayIntegrityCacheAsRoot() else false

        // Kill DroidGuard first (the isolated process holding in-memory verdict state),
        // then GMS core. Order matters — killing unstable before GMS core prevents GMS
        // from restarting unstable immediately during its own teardown.
        try {
            ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("am", "force-stop", "com.google.android.gms.unstable"),
                joinTimeoutMs = 1500
            )
        } catch (_: Exception) {}
        try {
            ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("am", "force-stop", "com.google.android.gms"),
                joinTimeoutMs = 1500
            )
        } catch (_: Exception) {}

        // ADB-mode extra steps: attempt to force a fresh DroidGuard evaluation before
        // clearing Wallet, so GMS has a clean result cached by the time Wallet launches.
        if (!isRoot) {
            // Best-effort: tell GMS to treat its verdict cache as expired immediately.
            // Key names are not published; unknown keys are silently ignored.
            attemptGservicesTtlOverride()
            // Wait briefly for GMS to finish starting up after force-stop, then trigger
            // a Play Integrity request. If GMS re-runs DroidGuard on cold start (which it
            // typically does), this caches a fresh "clean" result before Wallet launches.
            delay(800)
            if (context != null) attemptPlayIntegrityWarmup(context)
            // Clear Wallet's own data so it calls Play Integrity with a fresh nonce
            // and cannot replay a cached stale result. Re-assert NFC routing as safeguard.
            // Confirmed via ADB: pm clear on Wallet succeeds as shell uid 2000, and does NOT
            // wipe nfc_payment_default_component (safe to do without losing tap-to-pay routing).
            try {
                ShizukuProcessUtils.runPrivilegedCapture(
                    arrayOf("pm", "clear", "com.google.android.apps.walletnfcrel"),
                    joinTimeoutMs = 3000
                )
            } catch (_: Exception) {}
            try {
                ShizukuProcessUtils.runPrivilegedCapture(
                    arrayOf("settings", "put", "secure", "nfc_payment_default_component",
                        "com.google.android.gms/com.google.android.gms.tapandpay.hce.service.TpHceService"),
                    joinTimeoutMs = 1000
                )
            } catch (_: Exception) {}
        } else {
            // Root path: also force-stop Wallet after file deletion.
            try {
                ShizukuProcessUtils.runPrivilegedCapture(
                    arrayOf("am", "force-stop", "com.google.android.apps.walletnfcrel"),
                    joinTimeoutMs = 1000
                )
            } catch (_: Exception) {}
        }

        when {
            cacheCleared -> WalletRefreshResult.CACHE_CLEARED_ROOT
            else -> WalletRefreshResult.WALLET_CLEARED_PROCESSES_KILLED
        }
    }

    /**
     * Deletes GMS's Play Integrity / DroidGuard verdict cache files. Only callable as root
     * (uid 0) — the shell uid (2000) cannot access GMS's data directory.
     *
     * GMS stores verdict state across two locations:
     *  - databases/droidguard*        — SQLite files for hardware attestation blobs
     *  - files/dg_cache*, files/play_integrity* — flat-file verdict caches
     *
     * Deliberately avoids accounts.db, gaia/, and token-store paths so Google account
     * auth survives the wipe. GMS is force-stopped first to release file locks.
     *
     * Returns true only if at least one target file/dir was successfully deleted — a false
     * positive (returning true when nothing was deleted) is worse than a false negative here
     * because callers show "Wallet should recover immediately" on CACHE_CLEARED_ROOT.
     */
    private suspend fun clearPlayIntegrityCacheAsRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            // GMS must be stopped before we touch its databases to avoid corruption.
            ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("am", "force-stop", "com.google.android.gms"),
                joinTimeoutMs = 2000
            )
            val gms = "/data/data/com.google.android.gms"
            // Probe whether root can actually read the dir before claiming success.
            // If this ls fails the rm commands will too — return false rather than lying.
            val probe = ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("sh", "-c", "ls $gms/databases/ > /dev/null 2>&1 && echo READABLE"),
                joinTimeoutMs = 1000
            )
            if (!probe.stdout.contains("READABLE")) {
                Timber.w("clearPlayIntegrityCacheAsRoot: GMS databases dir not readable — uid may not be 0")
                return@withContext false
            }
            val cmd = """
                deleted=0
                for target in \
                    "$gms/databases/droidguard" \
                    "$gms/databases/droidguard-journal" \
                    "$gms/databases/droidguard-shm" \
                    "$gms/databases/droidguard-wal" \
                    "$gms/files/dg_cache" \
                    "$gms/files/play_integrity"; do
                    if [ -e "${'$'}target" ]; then
                        rm -rf "${'$'}target" && deleted=$((deleted+1))
                    fi
                done
                echo "DELETED:${'$'}deleted"
            """.trimIndent()
            val result = ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("sh", "-c", cmd),
                joinTimeoutMs = 4000
            )
            val deleted = Regex("DELETED:(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Timber.i("clearPlayIntegrityCacheAsRoot: deleted $deleted integrity cache entries")
            deleted > 0
        } catch (e: Exception) {
            Timber.w(e, "clearPlayIntegrityCacheAsRoot failed")
            false
        }
    }

    /**
     * Broadcasts GSERVICES_OVERRIDE with candidate Play Integrity TTL keys set to 1ms.
     * Causes GMS to treat its verdict cache as immediately expired on next start.
     * Key names are not published by Google; unknown keys are silently ignored, so
     * this is safe to call even if none of the candidate names are correct.
     */
    private suspend fun attemptGservicesTtlOverride() {
        try {
            val cmd = """
                am broadcast -a com.google.gservices.intent.action.GSERVICES_OVERRIDE \
                    --es play_integrity_verdict_ttl_ms 1 2>/dev/null
                am broadcast -a com.google.gservices.intent.action.GSERVICES_OVERRIDE \
                    --es play_integrity_token_ttl_ms 1 2>/dev/null
                am broadcast -a com.google.gservices.intent.action.GSERVICES_OVERRIDE \
                    --es droidguard_token_ttl_secs 1 2>/dev/null
            """.trimIndent()
            ShizukuProcessUtils.runPrivilegedCapture(arrayOf("sh", "-c", cmd), joinTimeoutMs = 1500)
        } catch (_: Exception) {}
    }

    /**
     * Requests a Play Integrity token from GMS. Even if the request fails (ShizukuPlus is
     * a sideloaded app, so app-integrity won't pass), calling this forces GMS to re-run
     * DroidGuard against the current device state. With su binaries already removed,
     * the resulting device-integrity verdict should be "clean" and gets cached to disk —
     * overwriting the old "compromised" verdict before Wallet launches.
     *
     * Blocking — must be called from [Dispatchers.IO].
     */
    private fun attemptPlayIntegrityWarmup(context: Context): Boolean {
        return try {
            val manager = IntegrityManagerFactory.create(context)
            val request = IntegrityTokenRequest.builder()
                .setNonce(UUID.randomUUID().toString())
                .build()
            Tasks.await(manager.requestIntegrityToken(request), 5, TimeUnit.SECONDS)
            Timber.i("refreshGoogleWalletAttestation: Play Integrity warmup call succeeded")
            true
        } catch (e: Exception) {
            // Expected if GMS is still starting up after force-stop, or on a device
            // where the Play Integrity API is not available. Either way, not an error —
            // the force-stop + fresh nonce path (pm clear Wallet) still applies.
            Timber.i("refreshGoogleWalletAttestation: Play Integrity warmup: ${e.javaClass.simpleName}")
            false
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
                    "ADB/shell mode detected. If your device restricts writes to /data/local/tmp from " +
                        "the shell process, use the exported path instead. Tap \"Export\" in the " +
                        "compatibility hub and direct root apps to that path, or switch to root mode for full access."
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
            try {
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                p.waitFor()
                if (out.contains("APP_OK")) {
                    val auid = Regex("Uid:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
                    "✅ ran end-to-end (uid ${auid ?: "?"})"
                } else {
                    "⚠️ didn't round-trip — an exec-style app may fail here:\n${(out + err).trim().take(220)}"
                }
            } finally {
                p.destroy()
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
