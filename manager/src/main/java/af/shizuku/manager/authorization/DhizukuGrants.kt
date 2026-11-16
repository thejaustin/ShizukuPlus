package af.shizuku.manager.authorization

import af.shizuku.manager.ShizukuApplication
import android.content.SharedPreferences
import rikka.shizuku.Shizuku

/**
 * Which apps may use Shizuku+ as their Dhizuku server. Kept in the app itself because Dhizuku runs
 * on Device Owner, not on the Shizuku service: after a reboot, with Shizuku not started yet, the
 * apps the user allowed must keep working (#680). A Shizuku grant made while the service runs
 * counts too, and is copied here so it survives the next reboot.
 *
 * Keyed by uid, package and first-install time, so an app that was uninstalled and installed again
 * (even if it lands on the same uid) asks again, while updates keep the grant. Lives in
 * device-protected storage, which is readable before the first unlock, and is never backed up.
 */
object DhizukuGrants {
    private const val FILE = "dhizuku_grants"
    private const val KEY = "granted"

    private val prefs: SharedPreferences
        get() =
            ShizukuApplication.appContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    private fun entry(
        packageName: String,
        uid: Int,
    ): String {
        val installed =
            try {
                ShizukuApplication.appContext.packageManager
                    .getPackageInfo(packageName, 0)
                    .firstInstallTime
            } catch (_: Exception) {
                -1L
            }
        return "$uid:$packageName:$installed"
    }

    private fun stored(
        packageName: String,
        uid: Int,
    ) = entry(packageName, uid) in prefs.getStringSet(KEY, emptySet()).orEmpty()

    /** Doesn't wait for the Shizuku service: when it isn't running, only the local grant counts. */
    fun isGranted(
        packageName: String,
        uid: Int,
    ): Boolean {
        if (packageName.isEmpty()) return false
        if (stored(packageName, uid)) return true
        val viaShizuku =
            try {
                Shizuku.pingBinder() && AuthorizationManager.granted(packageName, uid)
            } catch (_: Throwable) {
                false
            }
        if (viaShizuku) grant(packageName, uid)
        return viaShizuku
    }

    @Synchronized
    fun grant(
        packageName: String,
        uid: Int,
    ) {
        val set = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (set.add(entry(packageName, uid))) prefs.edit().putStringSet(KEY, set).commit()
    }

    /** Apps with a grant for their current install, as (package, uid). */
    fun all(): List<Pair<String, Int>> =
        prefs
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .mapNotNull { e ->
                val parts = e.split(':', limit = 3)
                val uid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val pkg = parts.getOrNull(1) ?: return@mapNotNull null
                (pkg to uid).takeIf { entry(pkg, uid) == e }
            }.distinct()

    @Synchronized
    fun revoke(
        packageName: String,
        uid: Int,
    ) {
        // Every entry for this uid and package, whatever install it was made for.
        val prefix = "$uid:$packageName:"
        val set = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (set.removeAll { it.startsWith(prefix) }) prefs.edit().putStringSet(KEY, set).commit()
    }
}
