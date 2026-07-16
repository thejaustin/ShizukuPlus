package af.shizuku.manager.automation

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import af.shizuku.manager.ShizukuSettings
import timber.log.Timber

/**
 * Opportunistic self-heal for the AICore+ accessibility service (#320).
 *
 * OEM power managers (Samsung "sleeping apps" especially) disable an app's accessibility
 * service after it's been backgrounded for a while, even though the user still has the AICore+
 * feature turned on in-app. There's no API to stop the OEM from disabling it, but since the
 * manager holds WRITE_SECURE_SETTINGS once Shizuku is running, it can quietly turn the service
 * back on the next time the user opens the app — the same mechanism the ADB-pairing service
 * already uses to enable itself.
 *
 * Intentionally NOT a background watchdog: it only runs when the user foregrounds the app, so it
 * never fights someone who deliberately turned the service off (the AICore+ toggle being ON is
 * treated as the user's standing intent to have it enabled), and it adds no background wakeups.
 */
object AICoreAccessibilityHealer {

    fun reenableIfNeeded(context: Context) {
        try {
            // Only act on the user's standing intent: AICore+ feature toggled on in-app.
            if (!ShizukuSettings.isAICorePlusEnabled()) return

            // Re-enabling requires WRITE_SECURE_SETTINGS, granted to the manager once Shizuku
            // is running. Without it there's nothing we can do here (the UI already guides the
            // user to enable the service manually).
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return

            val target = ComponentName(context, AICorePlusService::class.java)
            if (isServiceEnabled(context, target)) return

            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val flattened = target.flattenToString()
            val updated = if (current.isNullOrEmpty()) flattened else "$current:$flattened"

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated,
            )
            // ACCESSIBILITY_ENABLED must be 1 or the framework ignores the enabled-services list.
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")

            Timber.i("Re-enabled AICore+ accessibility service after OEM disabled it (#320)")
        } catch (e: Exception) {
            // Never let self-heal crash app startup — worst case the user re-enables manually.
            Timber.w(e, "AICore+ accessibility self-heal failed")
        }
    }

    private fun isServiceEnabled(context: Context, target: ComponentName): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // Compare via ComponentName so a short-form entry (pkg/.automation.AICorePlusService)
        // still matches the fully-qualified target the OEM may have stored either way.
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }
}
