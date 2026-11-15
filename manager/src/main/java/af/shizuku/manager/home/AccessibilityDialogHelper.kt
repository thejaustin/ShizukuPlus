package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbPairingAccessibilityService
import af.shizuku.manager.ktx.findActivity
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsPage
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TypefaceSpan
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gets the pairing accessibility service turned on: automatically when the app can (run off the
 * main thread, it goes through the shell), otherwise by walking the user to the setting.
 */
fun Context.showAccessibilityDialog() {
    if (isAccessibilityEnabled()) {
        showNavigateDialog()
        return
    }
    val scope = findActivity<androidx.activity.ComponentActivity>()?.lifecycleScope ?: return showManualAccessibilitySetup()
    scope.launch {
        val enabled = withContext(Dispatchers.IO) { tryEnableAccessibilityAutomatically() }
        if (enabled) showNavigateDialog() else showManualAccessibilitySetup()
    }
}

private fun Context.showManualAccessibilitySetup() {
    val installer = packageManager.getInstallerPackageName(packageName)
    val isInstalledByPlayOrAdb = (installer == "com.android.vending") || (installer == null)
    val isRestricted = !isInstalledByPlayOrAdb && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    if (isRestricted) {
        showPermissionDialog()
    } else {
        showEnableDialog()
    }
}

/**
 * Enables the pairing accessibility service with no UI, trying in order: a direct write
 * (WRITE_SECURE_SETTINGS already held), Shizuku, then root. Blocking (shell calls) - call off the
 * main thread. Returns true when the service ends up enabled; false means the caller should fall
 * back to the system Accessibility screen.
 */
fun Context.tryEnableAccessibilityAutomatically(): Boolean {
    if (isAccessibilityEnabled()) return true

    val hasWriteSecureSettings = (checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
    if (hasWriteSecureSettings) {
        if (enableAccessibilityService()) {
            return true
        }
    }

    // 1. Attempt automatic elevation via Shizuku if alive and granted
    // An older or another app's server throws "Not an attached client" here (SHIZUKUPLUS-AX).
    val shizukuGranted =
        runCatching {
            rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    if (shizukuGranted) {
        try {
            val serviceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
            val p1 =
                rikka.shizuku.Shizuku.newProcess(
                    arrayOf("cmd", "appops", "set", packageName, "ACCESS_RESTRICTED_SETTINGS", "allow"),
                    null,
                    null,
                )
            p1?.waitFor()
            p1?.destroy()

            val p2 =
                rikka.shizuku.Shizuku.newProcess(
                    arrayOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS"),
                    null,
                    null,
                )
            p2?.waitFor()
            p2?.destroy()

            val currentServices = getEnabledAccessibilityServices()
            val newServices =
                if (currentServices.isNullOrEmpty()) {
                    serviceName
                } else if (!currentServices.contains(serviceName)) {
                    currentServices.joinToString(":") + ":$serviceName"
                } else {
                    currentServices.joinToString(":")
                }

            val p3 =
                rikka.shizuku.Shizuku.newProcess(
                    arrayOf("settings", "put", "secure", "enabled_accessibility_services", newServices),
                    null,
                    null,
                )
            p3?.waitFor()
            p3?.destroy()

            val p4 =
                rikka.shizuku.Shizuku.newProcess(
                    arrayOf("settings", "put", "secure", "accessibility_enabled", "1"),
                    null,
                    null,
                )
            p4?.waitFor()
            p4?.destroy()

            if (enableAccessibilityService() || isAccessibilityEnabled()) {
                return true
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Auto-elevation via Shizuku failed")
        }
    }

    // 2. Attempt automatic elevation via root if available on device
    if (EnvironmentUtils.isRooted()) {
        try {
            val serviceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
            val currentServices = getEnabledAccessibilityServices()
            val newServices =
                if (currentServices.isNullOrEmpty()) {
                    serviceName
                } else if (!currentServices.contains(serviceName)) {
                    currentServices.joinToString(":") + ":$serviceName"
                } else {
                    currentServices.joinToString(":")
                }
            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "cmd appops set $packageName ACCESS_RESTRICTED_SETTINGS allow " +
                            "&& pm grant $packageName android.permission.WRITE_SECURE_SETTINGS " +
                            "&& settings put secure enabled_accessibility_services $newServices " +
                            "&& settings put secure accessibility_enabled 1",
                    ),
                )
            process.waitFor()
            if (enableAccessibilityService() || isAccessibilityEnabled()) {
                return true
            }
        } catch (_: Throwable) {
        }
    }
    return false
}

private fun Context.showPermissionDialog() {
    val permissionName = "ACCESS_RESTRICTED_SETTINGS"
    val permissionCommand = "adb shell cmd appops set $packageName $permissionName allow"

    MaterialAlertDialogBuilder(this)
        .setTitle(android.R.string.dialog_alert_title)
        .setMessage(
            // The string uses %2$s for the command. expandTemplate only fills ^1/^2, so it showed a
            // literal "%2$s"; format it, then set the command in monospace.
            getString(R.string.dialog_adb_pairing_accessibility_permission, permissionName, permissionCommand).let { text ->
                SpannableString(text).apply {
                    val at = text.indexOf(permissionCommand)
                    if (at >= 0) setSpan(TypefaceSpan("monospace"), at, at + permissionCommand.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            },
        ).setPositiveButton(R.string.accessibility_action_app_info) { _, _ ->
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb command", permissionCommand))
                android.widget.Toast
                    .makeText(this, R.string.toast_copied_to_clipboard, android.widget.Toast.LENGTH_SHORT)
                    .show()
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                startActivity(intent)
            } catch (_: Throwable) {
            }
        }.setNeutralButton(R.string.action_continue) { _, _ -> showEnableDialog() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.showEnableDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(R.string.dialog_adb_pairing_accessibility_enable)
        .setPositiveButton(R.string.enable) { _, _ ->
            SettingsPage.Accessibility.launch(this)
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.showNavigateDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(R.string.dialog_adb_pairing_accessibility_navigate)
        .setPositiveButton(R.string.development_settings) { _, _ ->
            SettingsPage.Developer.HighlightWirelessDebugging.launch(this)
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.getEnabledAccessibilityServices(): List<String>? {
    val enabledServices =
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    return enabledServices?.split(":")
}

private fun Context.isAccessibilityEnabled(): Boolean {
    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    return getEnabledAccessibilityServices()?.any { it.equals(accessibilityServiceName) } ?: false
}

private fun Context.enableAccessibilityService(): Boolean {
    if (isAccessibilityEnabled()) return true

    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    val enabledServices = getEnabledAccessibilityServices()
    val newServices =
        if (enabledServices.isNullOrEmpty()) {
            accessibilityServiceName
        } else {
            enabledServices.joinToString(":") + ":$accessibilityServiceName"
        }

    return try {
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            newServices,
        )
        Settings.Secure.putInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            1,
        )
        isAccessibilityEnabled()
    } catch (_: Throwable) {
        false
    }
}
