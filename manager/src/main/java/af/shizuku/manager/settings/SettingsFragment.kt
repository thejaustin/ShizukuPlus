package af.shizuku.manager.settings

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SettingsFragment : BaseSettingsFragment() {
    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        activity?.runOnUiThread {
            updateStatusBanner()
        }
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_title)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_main, rootKey)
        findPreference<Preference>("nav_about")?.setCustomSummary(
            getString(
                R.string.settings_main_nav_about_version,
                // VERSION_NAME is "Shizuku+ 13.7.0.rNNNN"; the row title already names the app.
                BuildConfig.VERSION_NAME.substringAfter(' ', BuildConfig.VERSION_NAME),
            ),
        )
        findPreference<Preference>("status_at_a_glance")?.setOnPreferenceClickListener {
            val ctx = it.context
            if (ShizukuStateMachine.isRunning()) {
                startActivity(Intent(ctx, ServiceDoctorActivity::class.java))
            } else {
                startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            true
        }

        updateStatusBanner()
    }

    override fun onResume() {
        super.onResume()
        ShizukuStateMachine.addListener(stateListener)
        updateStatusBanner()
        updateLiveSummaries()
    }

    override fun onPause() {
        super.onPause()
        ShizukuStateMachine.removeListener(stateListener)
    }

    /** Top-level rows show what is set right now, not a list of everything inside them. */
    private fun updateLiveSummaries() {
        val ctx = context ?: return

        val theme =
            when (ShizukuSettings.getNightMode()) {
                AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.dark_theme_off)
                AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.dark_theme_on)
                else -> getString(R.string.follow_system)
            }
        val color =
            if (ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.Keys.KEY_USE_SYSTEM_COLOR, true)) {
                getString(R.string.settings_summary_dynamic_color)
            } else {
                getString(R.string.settings_summary_custom_accent)
            }
        val appearance =
            buildList {
                add(theme)
                add(color)
                if (ShizukuSettings.isOneUiThemeEnabled()) add(getString(R.string.settings_oneui_theme))
            }
        findPreference<Preference>("nav_personalization")?.setCustomSummary(appearance.joinToString(SEP))

        val behavior =
            buildList {
                if (ShizukuSettings.getStartOnBoot(ctx)) add(getString(R.string.settings_summary_starts_on_boot))
                if (ShizukuSettings.getWatchdog()) add(getString(R.string.settings_summary_watchdog_on))
                if (ShizukuSettings.isBinderFirewallEnabled()) add(getString(R.string.settings_summary_firewall_on))
            }
        findPreference<Preference>("nav_behavior")?.setCustomSummary(
            if (behavior.isEmpty()) getString(R.string.settings_summary_manual_start) else behavior.joinToString(SEP),
        )
    }

    private fun updateStatusBanner() {
        val banner = findPreference<Preference>("status_at_a_glance") ?: return
        val context = context ?: return
        val appName = ShizukuSettings.getCustomAppName(context)

        if (!ShizukuStateMachine.isRunning()) {
            banner.icon = ContextCompat.getDrawable(context, R.drawable.ic_server_error_24)
            banner.title = getString(R.string.home_status_service_not_running, appName)
            banner.setCustomSummary(getString(R.string.settings_status_banner_stopped_hint))
            return
        }

        banner.icon = ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24)
        banner.title = getString(R.string.settings_status_banner_running_title, appName)
        // Shizuku.getUid() is a binder call - keep it off the main thread (DeadObjectException /
        // jank when the server is busy or dying).
        lifecycleScope.launch {
            val uid =
                withContext(Dispatchers.IO) {
                    runCatching { Shizuku.getUid() }.getOrDefault(-1)
                }
            val isRoot =
                uid == 0 ||
                    (uid == -1 && (EnvironmentUtils.isRooted() || ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT))
            if (isAdded) {
                banner.setCustomSummary(
                    getString(if (isRoot) R.string.settings_status_banner_root_hint else R.string.settings_status_banner_adb_hint),
                )
            }
        }
    }

    private companion object {
        const val SEP = " · "
    }
}
