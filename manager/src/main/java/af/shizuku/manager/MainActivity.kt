package af.shizuku.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import timber.log.Timber
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.onboarding.OnboardingActivity
import af.shizuku.manager.utils.ShizukuStateMachine

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Timber.d("Calling super.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("Calling super.onCreate"))
            super.onCreate(savedInstanceState)

            Timber.d("Checking onboarding status")
            Sentry.addBreadcrumb(Breadcrumb("Checking onboarding status"))

            if (!ShizukuSettings.hasSeenOnboarding()) {
                Timber.d("Showing onboarding")
                Sentry.addBreadcrumb(Breadcrumb("Showing onboarding"))
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }

            // Show migration dialog whenever the old package is still installed.
            // We re-check on every launch so users who dismissed without uninstalling are
            // reminded. Once they uninstall the old app, this branch is never taken again.
            if (MigrationHelper.isOldPackageInstalled(this)) {
                showMigrationDialog()
            }

            // Check for previous crashes
            if (af.shizuku.manager.utils.CrashHandler.getLastCrashReport(this) != null) {
                showCrashReportDialog()
            }

            Timber.d("MainActivity onCreate complete")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate complete"))
        } catch (e: Exception) {
            Timber.e(e, "Crash in MainActivity.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
            Sentry.captureException(e)
            throw e
        }
    }

    override fun onStart() {
        try {
            super.onStart()
            // Update state machine on app start
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Error in onStart")
            Sentry.captureException(e)
            throw e
        }
    }

    private fun showMigrationDialog() {
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { MigrationHelper.isRootAvailable() }
            if (isFinishing || isDestroyed) return@launch
            try {
                val builder = if (hasRoot) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.migration_dialog_title)
                        .setMessage(R.string.migration_dialog_message_root)
                        .setPositiveButton(R.string.migration_migrate_settings) { _, _ -> performMigration() }
                        .setNeutralButton(R.string.migration_uninstall_old) { _, _ -> launchUninstall(MigrationHelper.OLD_PACKAGE) }
                        .setNegativeButton(R.string.migration_dismiss, null)
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.migration_dialog_title)
                        .setMessage(R.string.migration_no_root_message)
                        .setPositiveButton(R.string.migration_uninstall_old) { _, _ -> launchUninstall(MigrationHelper.OLD_PACKAGE) }
                        .setNegativeButton(R.string.migration_dismiss, null)
                }
                builder.show()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration dialog")
            }
        }
    }

    private fun showCrashReportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_report_title)
            .setMessage("Shizuku+ detected a crash from your last session. Would you like to generate a report to help us fix it?")
            .setPositiveButton(R.string.manual_report_button_generate) { _, _ ->
                val report = af.shizuku.manager.utils.CrashReporter.generateReport(this)
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash Report", report))
                android.widget.Toast.makeText(this, R.string.manual_report_toast_copied, android.widget.Toast.LENGTH_SHORT).show()
                
                MaterialAlertDialogBuilder(this)
                    .setTitle("Report Copied")
                    .setMessage("The report is copied. Open GitHub to paste it, or share the report as a file instead?")
                    .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                        af.shizuku.manager.utils.CustomTabsHelper.launchUrlOrCopy(this, "https://github.com/thejaustin/ShizukuPlus/issues/new")
                        af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
                    }
                    .setNeutralButton("Share File") { _, _ ->
                        af.shizuku.manager.utils.CrashReporter.shareAsFile(this)
                        af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton("Ignore") { _, _ ->
                af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun performMigration() {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                MigrationHelper.migrateSettings(this@MainActivity)
            }

            if (isFinishing || isDestroyed) return@launch

            val (title, message) = if (success) {
                Pair(R.string.migration_success_title, R.string.migration_success_message)
            } else {
                Pair(R.string.migration_failure_title, R.string.migration_failure_message)
            }

            try {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.migration_uninstall_old) { _, _ ->
                        launchUninstall(MigrationHelper.OLD_PACKAGE)
                    }
                    .setNegativeButton(R.string.migration_dismiss, null)
                    .show()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration result dialog")
            }
        }
    }

    private fun launchUninstall(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch uninstall for $packageName")
        }
    }
}
