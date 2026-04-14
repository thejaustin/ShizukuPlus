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

            // One-time migration dialog for users coming from moe.shizuku.privileged.api
            if (!ShizukuSettings.hasMigrationBeenOffered() &&
                MigrationHelper.isOldPackageInstalled(this)) {
                showMigrationDialog()
            }

            Timber.d("MainActivity onCreate complete")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate complete"))
        } catch (e: Exception) {
            Timber.e(e, "Crash in MainActivity.onCreate")
            Sentry.captureException(e)
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
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

            val message = if (hasRoot) {
                getString(R.string.migration_dialog_message_root)
            } else {
                getString(R.string.migration_dialog_message)
            }

            val builder = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.migration_dialog_title)
                .setMessage(message)
                .setCancelable(false)
                .setNeutralButton(R.string.migration_dismiss) { _, _ ->
                    ShizukuSettings.setMigrationOffered()
                }

            if (hasRoot) {
                builder.setPositiveButton(R.string.migration_migrate_settings) { _, _ ->
                    ShizukuSettings.setMigrationOffered()
                    performMigration()
                }
            }

            builder.setNegativeButton(R.string.migration_uninstall_old) { _, _ ->
                ShizukuSettings.setMigrationOffered()
                launchUninstall(MigrationHelper.OLD_PACKAGE)
            }

            try {
                builder.show()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration dialog")
                ShizukuSettings.setMigrationOffered()
            }
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
