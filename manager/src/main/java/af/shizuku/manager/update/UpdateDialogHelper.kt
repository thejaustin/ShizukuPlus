package af.shizuku.manager.update

import af.shizuku.manager.R
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Unified dialog controller for in-app downloads, progress monitoring,
 * and graceful signature mismatch handling.
 */
object UpdateDialogHelper {
    fun showDownloadProgressDialog(
        activity: FragmentActivity,
        updateManager: UpdateManager,
        updateInfo: UpdateChecker.UpdateInfo,
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val statusView = dialogView.findViewById<TextView>(R.id.progress_status)
        val indicator = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_indicator)
        val detailView = dialogView.findViewById<TextView>(R.id.progress_detail)

        statusView.text = activity.getString(R.string.update_downloading_description, updateInfo.versionName)
        indicator.isIndeterminate = false
        indicator.progress = 0
        detailView.text = activity.getString(R.string.update_downloading_progress, updateInfo.versionName, 0)

        var dialog: AlertDialog? = null
        dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_downloading_title)
                .setView(dialogView)
                .setNegativeButton(R.string.update_run_in_background) { d, _ ->
                    d.dismiss()
                }.setNeutralButton(android.R.string.cancel) { d, _ ->
                    updateManager.cancel()
                    d.dismiss()
                }.setCancelable(false)
                .create()

        dialog.show()

        var handledCompletion = false

        activity.lifecycleScope.launch {
            updateManager.state.collect { state ->
                when (state) {
                    is UpdateManager.UpdateState.Downloading -> {
                        indicator.isIndeterminate = false
                        indicator.progress = state.percent
                        if (state.totalBytes > 0) {
                            val dlMb = formatBytes(state.bytesDownloaded)
                            val totMb = formatBytes(state.totalBytes)
                            detailView.text = "${state.percent}% ($dlMb / $totMb)"
                        } else {
                            detailView.text = "${state.percent}%"
                        }
                    }
                    is UpdateManager.UpdateState.Installing -> {
                        indicator.isIndeterminate = true
                        statusView.text = activity.getString(R.string.update_installing_progress, updateInfo.versionName)
                        detailView.text = activity.getString(R.string.update_installing_title)
                    }
                    is UpdateManager.UpdateState.Ready -> {
                        if (!handledCompletion) {
                            handledCompletion = true
                            indicator.isIndeterminate = true
                            statusView.text = activity.getString(R.string.update_installing_progress, updateInfo.versionName)
                            detailView.text = activity.getString(R.string.update_installing_title)

                            val result = updateManager.performSmartInstall(state.file)
                            if (dialog.isShowing) {
                                dialog.dismiss()
                            }
                            handleInstallResult(activity, updateManager, result)
                        }
                    }
                    is UpdateManager.UpdateState.Completed -> {
                        if (dialog.isShowing) {
                            dialog.dismiss()
                        }
                        Toast.makeText(activity, state.message, Toast.LENGTH_SHORT).show()
                    }
                    is UpdateManager.UpdateState.Error -> {
                        if (dialog.isShowing) {
                            dialog.dismiss()
                        }
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.update_download_failed_title)
                            .setMessage(state.message)
                            .setPositiveButton(R.string.update_view_on_github) { _, _ ->
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/releases"))
                                activity.startActivity(intent)
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    is UpdateManager.UpdateState.Idle -> {
                        // Idle
                    }
                }
            }
        }

        updateManager.downloadUpdate(updateInfo.downloadUrl, updateInfo.versionName, manual = true)
    }

    private fun handleInstallResult(
        activity: FragmentActivity,
        updateManager: UpdateManager,
        result: UpdateManager.InstallResult,
    ) {
        when (result) {
            is UpdateManager.InstallResult.Success -> {
                Toast.makeText(activity, R.string.update_installed_success, Toast.LENGTH_SHORT).show()
            }
            is UpdateManager.InstallResult.NeedsSystemInstaller -> {
                if (!updateManager.canRequestPackageInstalls()) {
                    showPermissionRequiredDialog(activity, result.file)
                } else {
                    updateManager.launchSystemInstaller(result.file)
                }
            }
            is UpdateManager.InstallResult.SignatureMismatchCanMigrate -> {
                showSignatureMismatchMigrateDialog(activity, result.file)
            }
            is UpdateManager.InstallResult.SignatureMismatchManualRequired -> {
                showSignatureMismatchManualDialog(activity)
            }
            is UpdateManager.InstallResult.Failed -> {
                Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * @param expectedSha256 when set (the file path came from an intent), the file must still hash
     * to this right before it is installed.
     */
    fun showSignatureMismatchMigrateDialog(
        activity: FragmentActivity,
        file: File,
        expectedSha256: String? = null,
    ) {
        // Android won't uninstall a Device Owner, which the migration needs: say so up front
        // instead of failing after the user agreed (the script would just put the old app back).
        val dpm = activity.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(activity.packageName) == true) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_signature_mismatch_title)
                .setMessage(R.string.update_migrate_device_owner)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_signature_mismatch_title)
            .setMessage(R.string.update_signature_mismatch_migrate_message)
            .setPositiveButton(R.string.update_migrate_now) { _, _ ->
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val unchanged = expectedSha256 == null || UpdateManager.sha256(file) == expectedSha256
                    val success = unchanged && UpdateInstaller.forceUpdateWithShizuku(activity, file)
                    if (!success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, R.string.update_migrate_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showSignatureMismatchManualDialog(activity: FragmentActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_signature_mismatch_title)
            .setMessage(R.string.update_signature_mismatch_manual_message)
            .setPositiveButton(R.string.update_open_backup_settings) { _, _ ->
                val intent = Intent(activity, af.shizuku.manager.settings.SettingsActivity::class.java)
                activity.startActivity(intent)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPermissionRequiredDialog(
        activity: FragmentActivity,
        file: File,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_required_title)
            .setMessage(R.string.update_permission_required_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent =
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    activity.startActivity(intent)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
