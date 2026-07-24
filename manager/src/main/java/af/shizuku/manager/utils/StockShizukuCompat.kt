package af.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object StockShizukuCompat {

    const val PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean {
        // The dropin flavor's own applicationId IS "moe.shizuku.privileged.api" (by design, so it
        // replaces stock Shizuku under the same package). Without this check, every function here
        // (isStockShizukuInstalled, the Watchdog crash-fallback notification, the "launch stock
        // Shizuku" button) would detect the dropin build as its own conflicting "stock" install and
        // offer to disable/uninstall/relaunch itself (#316).
        if (context.packageName == PACKAGE) return false
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isCompatAppInstalled(context: Context): Boolean {
        // Same self-detection problem as isInstalled(): the dropin flavor's own applicationId IS
        // PACKAGE, so without this check we'd inspect our own versionName (never "compat") and
        // report the hub as "not installed" on a dropin build. That surfaced an "Install Compat
        // Hub" card whose action pm-installs the shim stub directly over this running app - same
        // package + same signing key means a silent overwrite of the working Drop-In install (#334).
        if (context.packageName == PACKAGE) return true
        return try {
            val info = context.packageManager.getPackageInfo(PACKAGE, 0)
            info.versionName?.contains("compat") == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isStockShizukuInstalled(context: Context): Boolean {
        return isInstalled(context) && !isCompatAppInstalled(context)
    }

    fun launch(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startViaStockShizuku(): Boolean {
        if (!rikka.shizuku.Shizuku.pingBinder()) return false
        return try {
            val starterCmd = af.shizuku.manager.starter.Starter.internalCommand
            // Spawn a fully detached process that waits 1 second, then starts our server.
            // We immediately force-stop the original Shizuku so the ports/ServiceManager are freed up.
            val cmd = "nohup sh -c 'sleep 1 && $starterCmd' >/dev/null 2>&1 & am force-stop $PACKAGE"
            rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isOriginalRunning(): Boolean {
        // Same self-detection problem as isInstalled(): on the dropin flavor our own server
        // process is literally named "moe.shizuku.privileged.api" in `ps`, so the grep below would
        // match it and report our own healthy service as an "incompatible original server",
        // permanently blocking the home screen behind StartStockShizukuViewHolder's conflict
        // card (#316). There is no genuinely separate "original" to detect in that case — the
        // dropin build IS that package.
        if (af.shizuku.manager.BuildConfig.APPLICATION_ID == PACKAGE) return false
        if (!rikka.shizuku.Shizuku.pingBinder()) return false
        var process: Process? = null
        return try {
            process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", "ps -A | grep shizuku_server"), null, null)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            var isOriginal = false
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("moe.shizuku.privileged.api") == true) {
                    isOriginal = true
                    break
                }
            }
            isOriginal
        } catch (e: Exception) {
            // If the stock server is running but the stock manager is uninstalled,
            // ANY call to the server will throw this specific exception because the server
            // tries to enforce a permission that no longer exists on the device.
            if (e is IllegalArgumentException && e.message?.contains("moe.shizuku.manager.permission.API_V23") == true) {
                return true
            }
            false
        } finally {
            // readLine() can throw if the binder dies mid-read; destroy in finally so the
            // process handle doesn't leak on that path.
            try { process?.destroy() } catch (_: Exception) {}
        }
    }
}
