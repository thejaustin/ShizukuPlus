package rikka.shizuku.server

import android.content.pm.ApplicationInfo
import android.util.Log
import af.shizuku.common.compat.Android17Compat

/**
 * Packages that freezing (disable-user) can turn into a boot loop: the system UI, settings,
 * telephony and the permission/package plumbing, plus any app the system keeps persistent.
 * With one of these disabled the device restarts at the lock screen until it's re-enabled from
 * recovery or a factory reset, so the freeze, suspend and uninstall APIs refuse them outright.
 */
internal object BootCriticalPackages {
    private val NAMES =
        setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.keychain",
            "com.android.se",
            "com.android.server.telecom",
            "com.android.inputdevices",
            "com.android.location.fused",
        )

    fun isBootCritical(
        packageName: String,
        userId: Int,
    ): Boolean {
        if (packageName in NAMES) return true
        val ai =
            try {
                Android17Compat.getApplicationInfo(packageName, 0L, userId)
            } catch (_: Throwable) {
                null
            } ?: return false
        return ai.flags and ApplicationInfo.FLAG_PERSISTENT != 0
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
