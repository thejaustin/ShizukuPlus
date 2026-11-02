package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings

private val appContext: Context
    get() =
        try {
            ShizukuApplication.appContext
        } catch (e: UninitializedPropertyAccessException) {
            // Fallback for very early access if possible, or just rethrow with better message
            throw IllegalStateException("EnvironmentUtils.appContext accessed before ShizukuApplication.appContext was initialized", e)
        }

object EnvironmentUtils {
    @JvmStatic
    fun isDropIn(context: Context? = null): Boolean {
        val target =
            context ?: try {
                appContext
            } catch (_: Exception) {
                null
            }
        return target?.packageName == "moe.shizuku.privileged.api"
    }

    @JvmStatic
    fun isWatch(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isWatch(appContext)

    @JvmStatic
    fun isTelevision(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isTelevision(appContext)

    fun isTlsSupported(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isTlsSupported(appContext)

    @JvmStatic
    fun isUsbConnected(context: Context? = null): Boolean =
        try {
            val ctx = context ?: appContext
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = ctx.registerReceiver(null, filter)
            val plugged = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB
        } catch (_: Exception) {
            val state = SystemProperties.get("sys.usb.state", "")
            state.contains("adb")
        }

    fun isWifiRequired(allowNetworkProbe: Boolean = true): Boolean {
        if (ShizukuSettings.getTcpMode()) return false
        if (getAdbTcpPort() > 0) return false
        if (!allowNetworkProbe || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return true
        }
        if (af.shizuku.manager.adb.AdbPortProber
                .isPortOpen(5555, 50)
        ) {
            return false
        }
        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535 &&
            af.shizuku.manager.adb.AdbPortProber
                .isPortOpen(lastPort, 50)
        ) {
            return false
        }
        return true
    }

    // Warm up the root check eagerly when the class is loaded.
    // checkSuExists() just performs basic file stats and does not spawn an su process,
    // so it is safe and fast enough to run synchronously if needed.
    @Volatile
    private var isRootedCached: Boolean? = null

    init {
        // Still prewarm in background to avoid any potential main thread hit during class load
        startRootCheck()
    }

    /** Kick off an early root-availability check. Safe to call multiple times. */
    fun prewarmAsync() {
        if (isRootedCached == null) startRootCheck()
    }

    private fun startRootCheck() {
        if (isRootedCached != null) return
        val t =
            Thread({
                if (isRootedCached == null) {
                    isRootedCached =
                        try {
                            checkSuExists()
                        } catch (_: Exception) {
                            false
                        }
                }
            }, "root-check")
        t.isDaemon = true
        t.start()
    }

    /** Returns the cached root-check result, or computes it synchronously if not yet available. */
    fun isRooted(): Boolean {
        var result = isRootedCached
        if (result == null) {
            result =
                try {
                    checkSuExists()
                } catch (_: Exception) {
                    false
                }
            isRootedCached = result
        }
        return result
    }

    /**
     * Locates a usable `su` binary path across custom user settings, standard PATH, and modern
     * KernelSU / APatch / Magisk directories. Used as a direct ProcessBuilder fallback when
     * libsu's default `Shell.getShell()` cannot find `su` in the standard environment PATH.
     */
    @JvmStatic
    fun findSuBinary(): String? {
        runCatching {
            val custom = ShizukuSettings.getPreferences().getString("custom_su_path", null)?.trim()
            if (!custom.isNullOrEmpty()) {
                val customFile = java.io.File(custom)
                if (customFile.exists()) return customFile.absolutePath
            }
        }
        val paths = System.getenv("PATH")?.split(":")?.toMutableList() ?: mutableListOf()
        paths.addAll(
            listOf(
                "/data/adb/ksu/bin",
                "/data/adb/ap/bin",
                "/data/adb/magisk",
                "/sbin",
                "/system/bin",
                "/system/xbin",
                "/data/local/xbin",
                "/data/local/bin",
                "/system/sd/xbin",
                "/system/bin/failsafe",
                "/data/local",
            ),
        )
        for (path in paths) {
            val file = java.io.File(path, "su")
            if (file.exists()) return file.absolutePath
        }
        return null
    }

    private fun checkSuExists(): Boolean = findSuBinary() != null

    /**
     * Runs [command] through the `su` binary at [suPath] directly, bypassing libsu. The output is
     * drained so a chatty starter can't fill the pipe and stall, and the wait is bounded so an
     * unanswered root prompt can't hang the caller. Returns true only on exit code 0.
     */
    @JvmStatic
    fun runWithSuBinary(
        suPath: String,
        command: String,
        timeoutSeconds: Long = 15,
    ): Boolean {
        var proc: Process? = null
        var finished = false
        return try {
            proc = ProcessBuilder(suPath, "-c", command).redirectErrorStream(true).start()
            val p = proc
            Thread {
                runCatching {
                    p.inputStream.use { input ->
                        val buf = ByteArray(4096)
                        while (input.read(buf) >= 0) Unit
                    }
                }
            }.apply { isDaemon = true }.start()
            // Process.waitFor(timeout) and isAlive() are API 26+; poll exitValue() instead (minSdk 24).
            val deadline = android.os.SystemClock.uptimeMillis() + timeoutSeconds * 1000
            var exit: Int? = null
            while (exit == null && android.os.SystemClock.uptimeMillis() < deadline) {
                exit =
                    try {
                        p.exitValue()
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(100)
                        null
                    }
            }
            finished = exit != null
            exit == 0
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Direct su ($suPath) failed")
            false
        } finally {
            if (!finished) runCatching { proc?.destroy() }
        }
    }

    @JvmStatic
    fun isSamsung(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isSamsung()

    @JvmStatic
    fun getOneUiVersion(): Int =
        af.shizuku.common.util.EnvironmentUtils
            .getOneUiVersion()

    @JvmStatic
    fun isOneUi8(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isOneUi8()

    @JvmStatic
    fun isOppo(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isOppo()

    @JvmStatic
    fun isOnePlus(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isOnePlus()

    @JvmStatic
    fun getColorOsVersion(): String = SystemProperties.get("ro.build.version.opporom", "unknown")

    @JvmStatic
    fun isXiaomi(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isXiaomi()

    @JvmStatic
    fun getHyperOsVersion(): String = SystemProperties.get("ro.miui.ui.version.name", "unknown")

    @JvmStatic
    fun isTCL(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isTCL()

    @JvmStatic
    fun isDeX(context: Context): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isDeX(context)

    @JvmStatic
    fun isSecondaryUser(): Boolean =
        af.shizuku.common.util.EnvironmentUtils
            .isSecondaryUser()

    /**
     * Android 17 (API 37) redacts Settings.Global.ADB_ENABLED to 0 for third-party apps,
     * so a 0 reading no longer means USB debugging is actually disabled.
     */
    fun isAdbEnabled(): Boolean {
        if (Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0) > 0) return true
        return Build.VERSION.SDK_INT >= 37
    }

    fun getAdbTcpPort(): Int {
        var port =
            af.shizuku.common.util.EnvironmentUtils
                .getAdbTcpPort()
        if (port == -1 && isTelevision() && !isTlsSupported()) port = ShizukuSettings.getTcpPort()
        return port
    }

    /**
     * Resolves a SAF directory URI to a physical file path for a given filename.
     */
    @JvmStatic
    fun resolveExportedPath(filename: String): String? {
        val uriStr = ShizukuSettings.getExportDirUri() ?: return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            var docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("raw:")) {
                docId = docId.removePrefix("raw:")
            }

            // Check for common volume patterns
            val basePath =
                when {
                    docId.startsWith("primary:") -> {
                        val relative = docId.removePrefix("primary:")
                        if (relative.isEmpty()) {
                            "/storage/emulated/0"
                        } else {
                            "/storage/emulated/0/$relative"
                        }
                    }
                    docId.contains(":") -> {
                        val parts = docId.split(":")
                        val volumeId = parts[0]
                        val relative = parts[1]
                        "/storage/$volumeId/$relative"
                    }
                    docId.startsWith("Download") || docId.startsWith("Documents") || docId.startsWith("Movies") -> {
                        "/storage/emulated/0/$docId"
                    }
                    else -> {
                        if (!docId.startsWith("/")) {
                            "/storage/emulated/0/$docId"
                        } else {
                            docId
                        }
                    }
                }

            val path = basePath.replace("//", "/") + "/" + filename
            path.replace("//", "/")
        } catch (_: Exception) {
            null
        }
    }
}
