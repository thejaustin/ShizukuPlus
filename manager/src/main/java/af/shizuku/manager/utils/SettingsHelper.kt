package af.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import android.os.PowerManager
import android.provider.Settings
import af.shizuku.manager.R
import af.shizuku.manager.utils.SettingsPage

object SettingsHelper {

    fun launchOrHighlightWirelessDebugging(context: Context) {
        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled > 0) {
            SettingsPage.Developer.WirelessDebugging.launch(context)
        } else SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun promptWriteSecureSettings(context: Context) {
        val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wadb_permission_error_notification_title)
            .setMessage(context.getString(R.string.dialog_adb_pairing_accessibility_permission, "WRITE_SECURE_SETTINGS", command))
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb command", command))
                android.widget.Toast.makeText(context, R.string.toast_copied_to_clipboard, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun requestIgnoreBatteryOptimizations(context: Context, launcher: ActivityResultLauncher<Intent>? = null) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:" + context.packageName))
        }
        if (launcher != null) {
            try {
                launcher.launch(intent)
            } catch (e: IllegalStateException) {
                // Launcher may be unregistered if the fragment was detached — fall back to startActivity
                context.startActivity(intent)
            }
        } else {
            context.startActivity(intent)
        }
    }

}