package af.shizuku.manager.utils

import af.shizuku.manager.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

object SettingsHelper {
    fun launchOrHighlightWirelessDebugging(context: Context) {
        if (EnvironmentUtils.isAdbEnabled()) {
            SettingsPage.Developer.WirelessDebugging.launch(context)
        } else {
            SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
        }
    }

    /**
     * Tests only: forces [isIgnoringBatteryOptimizations] to this value, so a scenario controls the
     * exemption deterministically instead of through PowerManager's shadow (whose static map is
     * shared across tests). Null defers to the real check.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var isIgnoringBatteryOptimizationsForTesting: Boolean? = null

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        isIgnoringBatteryOptimizationsForTesting?.let { return it }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasWriteSecureSettings(context: Context): Boolean = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun autoGrantPrivileges(context: Context) {
        if (rikka.shizuku.Shizuku.pingBinder()) {
            try {
                val p1 =
                    rikka.shizuku.Shizuku.newProcess(
                        arrayOf("cmd", "appops", "set", context.packageName, "ACCESS_RESTRICTED_SETTINGS", "allow"),
                        null,
                        null,
                    )
                p1?.waitFor()
                p1?.destroy()

                val p2 =
                    rikka.shizuku.Shizuku.newProcess(
                        arrayOf("pm", "grant", context.packageName, "android.permission.WRITE_SECURE_SETTINGS"),
                        null,
                        null,
                    )
                p2?.waitFor()
                p2?.destroy()
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Auto-grant privileges via Shizuku failed")
            }
        } else if (EnvironmentUtils.isRooted()) {
            try {
                com.topjohnwu.superuser.Shell
                    .cmd(
                        "cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow",
                        "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                    ).exec()
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Auto-grant privileges via Root failed")
            }
        }
    }

    fun promptWriteSecureSettings(context: Context) {
        if (hasWriteSecureSettings(context)) {
            android.widget.Toast
                .makeText(context, R.string.accessibility_permission_granted, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }

        // 1. Automatic grant via Shizuku or Root if available
        autoGrantPrivileges(context)
        if (hasWriteSecureSettings(context)) {
            android.widget.Toast
                .makeText(context, R.string.accessibility_permission_granted, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }

        // 2. Fallback: manual copy command dialog
        val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wadb_permission_error_notification_title)
            .setMessage(context.getString(R.string.dialog_adb_pairing_accessibility_permission, "WRITE_SECURE_SETTINGS", command))
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb command", command))
                android.widget.Toast
                    .makeText(context, R.string.toast_copied_to_clipboard, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val expectedComponent = ComponentName(context, serviceClass).flattenToString()
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        return enabled.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    fun requestIgnoreBatteryOptimizations(
        context: Context,
        launcher: ActivityResultLauncher<Intent>? = null,
    ) {
        val intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                setData(Uri.parse("package:" + context.packageName))
            }
        try {
            launcher?.launch(intent) ?: context.startActivity(intent)
        } catch (_: IllegalStateException) {
            // Launcher may be unregistered if the fragment was detached — fall back to startActivity
            context.startActivity(intent)
        }
    }

    /**
     * The battery-exemption request the notification's tap opens: the AOSP "ignore battery
     * optimizations" dialog for this package, which grants exactly what the guard checks
     * (PowerManager.isIgnoringBatteryOptimizations), on every device including Samsung. Carries
     * FLAG_ACTIVITY_NEW_TASK so it works as a notification content intent, not just from an Activity.
     */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:" + context.packageName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
