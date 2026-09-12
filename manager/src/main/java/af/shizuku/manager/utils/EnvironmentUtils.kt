package af.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemProperties
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import com.topjohnwu.superuser.Shell

private val appContext: Context
    get() = try {
        ShizukuApplication.appContext
    } catch (e: UninitializedPropertyAccessException) {
        // Fallback for very early access if possible, or just rethrow with better message
        throw IllegalStateException("EnvironmentUtils.appContext accessed before ShizukuApplication.appContext was initialized", e)
    }

object EnvironmentUtils {

    @JvmStatic
    fun isWatch(): Boolean = af.shizuku.common.util.EnvironmentUtils.isWatch(appContext)

    @JvmStatic
    fun isTelevision(): Boolean = af.shizuku.common.util.EnvironmentUtils.isTelevision(appContext)

    fun isTlsSupported(): Boolean = af.shizuku.common.util.EnvironmentUtils.isTlsSupported(appContext)

    fun isWifiRequired(): Boolean {
        if (ShizukuSettings.getTcpMode()) return false
        if (getAdbTcpPort() > 0) return false
        if (af.shizuku.manager.adb.AdbPortProber.isPortOpen(5555, 50)) return false
        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535 && af.shizuku.manager.adb.AdbPortProber.isPortOpen(lastPort, 50)) return false
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
        val t = Thread({
            if (isRootedCached == null) {
                isRootedCached = try { checkSuExists() } catch (_: Exception) { false }
            }
        }, "root-check")
        t.isDaemon = true
        t.start()
    }

    /** Returns the cached root-check result, or computes it synchronously if not yet available. */
    fun isRooted(): Boolean {
        var result = isRootedCached
        if (result == null) {
            result = try { checkSuExists() } catch (_: Exception) { false }
            isRootedCached = result
        }
        return result
    }

    private fun checkSuExists(): Boolean {
        val paths = System.getenv("PATH")?.split(":")?.toMutableList() ?: mutableListOf()
        // Add common modern root paths that might not be in the standard PATH
        paths.addAll(listOf(
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
            "/data/local"
        ))
        for (path in paths) {
            if (java.io.File(path, "su").exists()) return true
        }
        return false
    }

    @JvmStatic
    fun getFullSdkVersion(): Int = af.shizuku.common.util.EnvironmentUtils.getFullSdkVersion()

    @JvmStatic
    fun isSamsung(): Boolean = af.shizuku.common.util.EnvironmentUtils.isSamsung()

    @JvmStatic
    fun getOneUiVersion(): Int = af.shizuku.common.util.EnvironmentUtils.getOneUiVersion()

    @JvmStatic
    fun isOneUi8(): Boolean = af.shizuku.common.util.EnvironmentUtils.isOneUi8()

    @JvmStatic
    fun isOppo(): Boolean = af.shizuku.common.util.EnvironmentUtils.isOppo()

    @JvmStatic
    fun isOnePlus(): Boolean = af.shizuku.common.util.EnvironmentUtils.isOnePlus()

    @JvmStatic
    fun getColorOsVersion(): String {
        return SystemProperties.get("ro.build.version.opporom", "unknown")
    }

    @JvmStatic
    fun isXiaomi(): Boolean = af.shizuku.common.util.EnvironmentUtils.isXiaomi()

    @JvmStatic
    fun getHyperOsVersion(): String {
        return SystemProperties.get("ro.miui.ui.version.name", "unknown")
    }

    @JvmStatic
    fun isTCL(): Boolean = af.shizuku.common.util.EnvironmentUtils.isTCL()

    @JvmStatic
    fun isDeX(context: Context): Boolean = af.shizuku.common.util.EnvironmentUtils.isDeX(context)

    @JvmStatic
    fun isSecondaryUser(): Boolean = af.shizuku.common.util.EnvironmentUtils.isSecondaryUser()

    fun getAdbTcpPort(): Int {
        var port = af.shizuku.common.util.EnvironmentUtils.getAdbTcpPort()
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
            val basePath = when {
                docId.startsWith("primary:") -> {
                    val relative = docId.removePrefix("primary:")
                    if (relative.isEmpty()) "/storage/emulated/0"
                    else "/storage/emulated/0/$relative"
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
                    if (!docId.startsWith("/")) "/storage/emulated/0/$docId"
                    else docId
                }
            }

            val path = basePath.replace("//", "/") + "/" + filename
            path.replace("//", "/")
        } catch (e: Exception) {
            null
        }
    }
}
