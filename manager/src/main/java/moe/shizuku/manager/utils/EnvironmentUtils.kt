package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemProperties
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
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
    fun isWatch(): Boolean {
        return (appContext.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_WATCH)
    }

    @JvmStatic
    fun isTelevision(): Boolean {
        return (appContext.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_TELEVISION ||
                appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
    }

    fun isTlsSupported(): Boolean {
        return if (isTelevision())
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            else Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun isWifiRequired(): Boolean {
        return (getAdbTcpPort() <= 0 || !ShizukuSettings.getTcpMode())
    }

    fun isRooted(): Boolean {
        return Shell.getShell().isRoot
    }

    @JvmStatic
    fun getFullSdkVersion(): Int {
        return if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 introduces SDK_INT_FULL
            try {
                val field = Build.VERSION::class.java.getField("SDK_INT_FULL")
                field.getInt(null)
            } catch (e: Exception) {
                Build.VERSION.SDK_INT * 100 // Fallback
            }
        } else {
            Build.VERSION.SDK_INT * 100
        }
    }

    @JvmStatic
    fun isSamsung(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    @JvmStatic
    fun getOneUiVersion(): Int {
        if (!isSamsung()) return -1
        try {
            val field = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT")
            return (field.get(null) as Int - 90000) / 10000
        } catch (e: Exception) {
            // Fallback for older/newer versions if the hidden field is missing
            return -1
        }
    }

    @JvmStatic
    fun isDeX(context: Context): Boolean {
        val config = context.resources.configuration
        return try {
            val field = config.javaClass.getField("semDesktopModeEnabled")
            field.getInt(config) == 1
        } catch (e: Exception) {
            (config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_DESK
        }
    }

    @JvmStatic
    fun isSecondaryUser(): Boolean {
        return try {
            val myUserHandle = android.os.Process.myUserHandle()
            val toString = myUserHandle.toString()
            // UserHandle{0} is owner. Secure Folder is usually 150+.
            !toString.contains("{0}")
        } catch (e: Exception) {
            false
        }
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (port == -1 && isTelevision() && !isTlsSupported()) port = ShizukuSettings.getTcpPort()
        return port
    }
}
