package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.SharedPreferences

/**
 * How much Service Doctor helps on its own. A preset over switches that already exist, so every
 * call site keeps reading its own key and nothing needs migrating. [CUSTOM] is only ever read,
 * never applied: it means the switches have been set one by one.
 */
enum class AssistantLevel(
    val value: String,
    private val notifications: String,
    private val problemsOnHome: Boolean,
    private val suggestions: Boolean,
    private val autoHeal: Boolean,
    private val diagnostics: Boolean,
) {
    /** Only the security warnings that can't be turned off. */
    OFF("off", "off", false, false, false, false),

    /** Tells you when something needs you; never acts or suggests by itself. */
    QUIET("quiet", "stopped", true, false, false, true),

    /** The behaviour before this setting existed, so updating changes nothing. */
    BALANCED("balanced", "stopped", true, true, true, true),

    /** Also notifies for every problem. */
    ACTIVE("active", "all", true, true, true, true),

    CUSTOM("custom", "", false, false, false, false),
    ;

    fun apply(prefs: SharedPreferences) {
        if (this == CUSTOM) return
        prefs
            .edit()
            .putString(ShizukuSettings.Keys.KEY_HELPER_NOTIFICATIONS, notifications)
            .putBoolean(ShizukuSettings.Keys.KEY_PROBLEMS_ON_HOME, problemsOnHome)
            .putBoolean(ShizukuSettings.Keys.KEY_ADAPTIVE_RECOMMENDATIONS_ENABLED, suggestions)
            .putBoolean(ShizukuSettings.Keys.KEY_DOCTOR_ADAPTIVE_AUTO_HEAL, autoHeal)
            .putBoolean("diagnostics_enabled", diagnostics)
            .apply()
    }

    private fun matches(prefs: SharedPreferences): Boolean =
        ShizukuSettings.getHelperNotificationLevel() == notifications &&
            prefs.getBoolean(ShizukuSettings.Keys.KEY_PROBLEMS_ON_HOME, true) == problemsOnHome &&
            prefs.getBoolean(ShizukuSettings.Keys.KEY_ADAPTIVE_RECOMMENDATIONS_ENABLED, true) == suggestions &&
            prefs.getBoolean(ShizukuSettings.Keys.KEY_DOCTOR_ADAPTIVE_AUTO_HEAL, true) == autoHeal &&
            prefs.getBoolean("diagnostics_enabled", true) == diagnostics

    companion object {
        /** The level whose switches match what is set now, else [CUSTOM]. Unset keys read as the old defaults, i.e. [BALANCED]. */
        fun current(prefs: SharedPreferences): AssistantLevel = entries.firstOrNull { it != CUSTOM && it.matches(prefs) } ?: CUSTOM

        fun of(value: String?): AssistantLevel = entries.firstOrNull { it.value == value } ?: BALANCED
    }
}
