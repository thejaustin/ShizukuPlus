package rikka.shizuku.server

import android.util.Log
import af.shizuku.common.compat.Android17Compat

/** Server side of [af.shizuku.common.util.BootCriticalPackages]: looks up the package itself. */
internal object BootCriticalPackages {
    fun isBootCritical(
        packageName: String,
        userId: Int,
    ): Boolean {
        val ai =
            try {
                Android17Compat.getApplicationInfo(packageName, 0L, userId)
            } catch (_: Throwable) {
                null
            }
        return af.shizuku.common.util.BootCriticalPackages.isBootCritical(packageName, ai)
    }

    /** True (and logged) when [action] (freeze, suspend, uninstall) must not touch [packageName]. */
    fun refuse(
        tag: String,
        action: String,
        packageName: String,
        userId: Int,
    ): Boolean {
        if (!isBootCritical(packageName, userId)) return false
        Log.w(tag, "$action refused for $packageName: boot-critical system package")
        return true
    }
}

/**
 * Settings that put a device back into first-boot setup: with one of these at 0 the setup wizard
 * takes over at every boot, or the lock screen and home never come up.
 */
internal object BootCriticalSettings {
    private val PROVISIONING = setOf("global/device_provisioned", "secure/user_setup_complete", "secure/tv_user_setup_complete")

    /** True (and logged) when writing [value] to [namespace]/[key] would undo device setup. */
    fun refuse(
        tag: String,
        namespace: String,
        key: String,
        value: String?,
    ): Boolean {
        if ("$namespace/$key" !in PROVISIONING || value == "1") return false
        Log.w(tag, "settings put $namespace $key $value refused: it would undo device setup")
        return true
    }
}
