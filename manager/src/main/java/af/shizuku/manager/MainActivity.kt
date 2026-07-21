package af.shizuku.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import af.shizuku.manager.home.ChangelogDialogFragment
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.onboarding.OnboardingActivity
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.utils.ShizukuStateMachine

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Timber.d("Calling super.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("Calling super.onCreate"))
            super.onCreate(savedInstanceState)

            // Check for previous crashes and offer to report — only for developers if Sentry is disabled.
            // Take manual reporting out of the general purpose UI for end users.
            if (af.shizuku.manager.utils.CrashHandler.getLastCrashReport(this) != null) {
                if (ShizukuSettings.isVectorEnabled() && BuildConfig.SENTRY_DSN.isEmpty()) {
                    showCrashReportDialog()
                }
            }

            Timber.d("Checking onboarding status")
            Sentry.addBreadcrumb(Breadcrumb("Checking onboarding status"))

            // Auto-restore settings if a force-update backup exists
            checkAndRestoreBackup()

            // Show what's new after an update. Separate from the Sentry-quota-reset version
            // tracking in ShizukuApplication.onCreate() — that one bumps its own flag before any
            // Activity runs, so this needs its own last-seen key or it would never see an advance.
            checkAndShowChangelog()

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
            // Self-heal the AICore+ accessibility service if an OEM power manager disabled it
            // while the app was backgrounded but the user still has the feature on (#320).
            af.shizuku.manager.automation.AICoreAccessibilityHealer.reenableIfNeeded(this)
        } catch (e: Exception) {
            Timber.e(e, "Error in onStart")
            Sentry.captureException(e)
            throw e
        }
    }

    private fun checkAndRestoreBackup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val backupFile = af.shizuku.manager.update.UpdateInstaller.getBackupFile(this@MainActivity)
            if (backupFile != null && backupFile.exists()) {
                try {
                    val json = backupFile.readText()
                    if (af.shizuku.manager.utils.SettingsBackupManager.import(this@MainActivity, json)) {
                        Timber.i("Successfully auto-restored settings from force-update backup")
                        backupFile.delete()
                        // Notify user or refresh UI if needed
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, R.string.migration_success_message, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-restore settings")
                }
            }
        }
    }

    /**
     * Shows "What's New" once per version bump, using the real GitHub release notes for the
     * exact version just installed (not "latest" — a newer build may already be out by the
     * time the user opens the app).
     */
    private fun checkAndShowChangelog() {
        val currentCode = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (e: Exception) { 0 }
        if (currentCode <= ShizukuSettings.getLastSeenChangelogVersion()) return

        val versionPart = Regex("""\d+\.\d+\.\d+\.r\d+""").find(BuildConfig.VERSION_NAME)?.value
        if (versionPart == null) {
            // Can't build a release tag from this build's version string — mark seen so we
            // don't retry every launch, and skip the dialog rather than showing a broken one.
            ShizukuSettings.setLastSeenChangelogVersion(currentCode)
            return
        }
        val tagName = "v$versionPart"

        lifecycleScope.launch {
            val notes = try {
                UpdateChecker.fetchReleaseNotesForTag(tagName)
            } catch (e: Exception) {
                Timber.tag("MainActivity").w(e, "Failed to fetch changelog for $tagName")
                null
            }

            // Mark seen regardless of fetch success — an offline user shouldn't be re-prompted
            // on every cold start; the dialog's fallback message covers that case once.
            ShizukuSettings.setLastSeenChangelogVersion(currentCode)

            if (isFinishing || isDestroyed) return@launch
            try {
                ChangelogDialogFragment.newInstance(notes, tagName)
                    .show(supportFragmentManager, ChangelogDialogFragment.TAG)
            } catch (e: Exception) {
                Timber.e(e, "Failed to show changelog dialog")
            }
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
        // Sentry already captured the original crash; this dialog lets users share a
        // human-readable report. It is optional — if the themed context is unavailable
        // (e.g. theme mismatch on old ROM) we silently clear the crash file and move on.
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manual_report_title)
                .setMessage(R.string.crash_detected_dialog_message)
                .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                    af.shizuku.manager.utils.CrashReporter.shareAsFile(this)
                    af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
                }
                .setNegativeButton(R.string.crash_detected_dialog_ignore) { _, _ ->
                    af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
                }
                .show()
        } catch (e: Exception) {
            Timber.e(e, "showCrashReportDialog failed — clearing crash file silently")
            Sentry.captureException(e)
            af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
        }
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
