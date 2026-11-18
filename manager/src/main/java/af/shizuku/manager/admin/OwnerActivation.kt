package af.shizuku.manager.admin

import af.shizuku.manager.ShizukuSettings
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Makes Shizuku+ the Device Owner or (limited mode) the Profile Owner of this user through the
 * running server, which is the ADB shell or root, so no PC is needed (#674).
 *
 * Android only refuses an owner while accounts are signed in once setup has completed, so for the
 * moment the owner is set this user's setup is marked not complete. That also lifts Android's own
 * refusal of a Device Owner while other users or profiles exist, and on Samsung a new Device Owner
 * then removes Secure Folder with everything in it (#688), so the Device Owner path refuses to run
 * at all while [otherUsers] finds any. A Profile Owner has no such check in Android and doesn't
 * remove profiles; it can't install apps silently or use device-wide policies.
 */
object OwnerActivation {
    /** Set while user_setup_complete is temporarily 0, so a crash in between can be undone. */
    private const val KEY_SETUP_FLIP_USER = "owner_activation_setup_flip_user"

    /** What was already limited before activating, so removal only reports what the owner left behind. */
    private const val KEY_BASELINE = "owner_activation_baseline"

    /** Restrictions a Device Owner adds, plus Samsung's Knox flag (read as the server). */
    private val PROFILE_RESTRICTIONS = listOf("no_add_managed_profile", "no_add_clone_profile", "no_add_private_profile", "no_add_user")
    private const val KNOX_FLAG = "persist.sys.knox.device_owner"

    class Result(
        val output: String,
        /** null when not activated; false when the backup service stayed off. */
        val backupOn: Boolean?,
    )

    enum class Mode { DEVICE_OWNER, PROFILE_OWNER }

    fun component(context: Context) = ComponentName(context, DhizukuAdminReceiver::class.java)

    /** The server can run dpm as the shell user or root. */
    fun serverCanActivate(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.getUid().let { it == 0 || it == 2000 } }.getOrDefault(false)

    /**
     * Every user and profile except this one, as "Name (id)" from `pm list users`
     * (UserInfo{150:Secure Folder:...}); null when the list can't be read.
     */
    fun otherUsers(): List<String>? {
        val (code, out) = runServerShell("pm list users")
        if (code != 0) return null
        val me = android.os.Process.myUid() / 100000
        val users =
            Regex("""UserInfo\{(\d+):([^:}]*)""").findAll(out).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
        if (users.isEmpty()) return null
        return users.filter { it.first != me }.map { (id, name) -> "${name.ifBlank { "User" }} ($id)" }
    }

    /**
     * Runs dpm for [mode]. The caller has already checked [otherUsers] for [Mode.DEVICE_OWNER].
     * Becoming an owner switches the backup service off for the user; it's switched back on.
     */
    fun activate(
        context: Context,
        mode: Mode,
    ): Result {
        val flat = component(context).flattenToShortString()
        val user = android.os.Process.myUid() / 100000
        val command =
            when (mode) {
                Mode.DEVICE_OWNER -> "dpm set-device-owner $flat"
                Mode.PROFILE_OWNER -> "dpm set-profile-owner --user $user $flat"
            }
        val before = currentLimits(context)
        val output = withSetupIncomplete(context) { runServerShell(command).second }
        if (!isOwner(context)) return Result(output, null)
        ShizukuSettings
            .getPreferences()
            ?.edit()
            ?.putStringSet(KEY_BASELINE, before.toSet())
            ?.apply()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        runCatching { dpm.setBackupServiceEnabled(component(context), true) }
            .onFailure { Timber.w(it, "setBackupServiceEnabled after activation failed") }
        // A Profile Owner may not be allowed to ask; unknown is not reported as off.
        val backupOn = runCatching { dpm.isBackupServiceEnabled(component(context)) }.getOrDefault(true)
        return Result(output, backupOn)
    }

    /** Limits set now that weren't before activating (empty when nothing was left behind). */
    fun leftoverLimits(context: Context): List<String> {
        val baseline = ShizukuSettings.getPreferences()?.getStringSet(KEY_BASELINE, null) ?: emptySet()
        return currentLimits(context).filter { it !in baseline }
    }

    fun clearBaseline() {
        ShizukuSettings
            .getPreferences()
            ?.edit()
            ?.remove(KEY_BASELINE)
            ?.apply()
    }

    private fun currentLimits(context: Context): List<String> {
        val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val restrictions = PROFILE_RESTRICTIONS.filter { runCatching { um.hasUserRestriction(it) }.getOrDefault(false) }
        val knox = runServerShell("getprop $KNOX_FLAG").second.trim() == "true"
        return restrictions + listOfNotNull(KNOX_FLAG.takeIf { knox })
    }

    fun isOwner(context: Context): Boolean =
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
        }.getOrDefault(false)

    /**
     * If the app was killed while setup was marked not complete, the device would keep behaving as
     * not set up (setup wizard, missing lock screen features). Called whenever the server connects.
     */
    fun recoverStuckSetup(context: Context) {
        val prefs = ShizukuSettings.getPreferences() ?: return
        val user = prefs.getInt(KEY_SETUP_FLIP_USER, -1)
        if (user < 0) return
        val (code, _) = runServerShell("settings --user $user put secure user_setup_complete 1")
        if (code != 0) return
        forceUpdateUserSetupComplete(user)
        prefs.edit().remove(KEY_SETUP_FLIP_USER).apply()
        Timber.w("Restored user_setup_complete for user %d after an interrupted owner activation", user)
    }

    private fun <T> withSetupIncomplete(
        context: Context,
        block: () -> T,
    ): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return block()
        val user = android.os.Process.myUid() / 100000
        val (_, current) = runServerShell("settings --user $user get secure user_setup_complete")
        if (current.trim() != "1") return block()
        val prefs = ShizukuSettings.getPreferences()
        // Written before the flip (commit, not apply) so recoverStuckSetup sees it after a kill.
        prefs?.edit()?.putInt(KEY_SETUP_FLIP_USER, user)?.commit()
        if (runServerShell("settings --user $user put secure user_setup_complete 0").first != 0) {
            prefs?.edit()?.remove(KEY_SETUP_FLIP_USER)?.apply()
            return block()
        }
        forceUpdateUserSetupComplete(user)
        try {
            return block()
        } finally {
            if (runServerShell("settings --user $user put secure user_setup_complete 1").first == 0) {
                prefs?.edit()?.remove(KEY_SETUP_FLIP_USER)?.apply()
            }
            forceUpdateUserSetupComplete(user)
        }
    }

    // Hidden IDevicePolicyManager.forceUpdateUserSetupComplete, called as the server (shell holds
    // the permission it needs) through a Shizuku binder wrapper. The service caches the setting.
    private fun forceUpdateUserSetupComplete(user: Int) {
        runCatching {
            val binder = rikka.shizuku.SystemServiceHelper.getSystemService(Context.DEVICE_POLICY_SERVICE) ?: return
            val service =
                Class
                    .forName("android.app.admin.IDevicePolicyManager\$Stub")
                    .getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, rikka.shizuku.ShizukuBinderWrapper(binder))
            val iface = Class.forName("android.app.admin.IDevicePolicyManager")
            runCatching { iface.getMethod("forceUpdateUserSetupComplete", Int::class.javaPrimitiveType).invoke(service, user) }
                .getOrElse { iface.getMethod("forceUpdateUserSetupComplete").invoke(service) }
        }.onFailure { Timber.w(it, "forceUpdateUserSetupComplete failed") }
    }

    // Runs [command] as the server and returns its exit code and combined output (dpm's own message
    // is the useful part when it refuses).
    @Suppress("DEPRECATION")
    private fun runServerShell(command: String): Pair<Int, String> =
        try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", "$command 2>&1"), null, null)
            val out = StringBuilder()
            val reader =
                Thread {
                    runCatching { out.append(proc.inputStream.bufferedReader().use { it.readText() }) }
                }.apply {
                    isDaemon = true
                    start()
                }
            val finished = proc.waitForTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) proc.destroy()
            reader.join(2_000)
            (if (finished) proc.exitValue() else -1) to out.toString().trim()
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
}
