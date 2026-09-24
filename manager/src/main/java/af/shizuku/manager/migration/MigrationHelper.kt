package af.shizuku.manager.migration

import com.topjohnwu.superuser.Shell
import timber.log.Timber

/**
 * Detects and migrates settings from the old `moe.shizuku.privileged.api` package
 * to the current `af.shizuku.plus.api` package.
 *
 * The applicationId changed, so users cannot do an in-place update — they must uninstall
 * and reinstall. This helper copies the old SharedPreferences file to the current app's
 * data directory via a root shell so no settings are lost.
 */
object MigrationHelper {

    private const val TAG = "MigrationHelper"

    /** Returns true if root or Shizuku privileged shell is available. */
    fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot || rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Exception) {
        try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Exception) {
            Timber.tag(TAG).d(e, "Privileged shell check failed")
            false
        }
    }
}
