package af.shizuku.manager.settings

import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import af.shizuku.manager.ShizukuLocales
import java.util.Locale

class PersonalizationSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = "Appearance"

    private var colorThemeCategory: CollapsiblePreferenceCategory? = null
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var useSystemColorPreference: TwoStatePreference
    private lateinit var customAccentPreference: Preference
    private lateinit var languagePreference: ListPreference
    private lateinit var translationPreference: Preference
    private lateinit var expressiveShapesPreference: TwoStatePreference
    private lateinit var expressiveAnimationsPreference: TwoStatePreference
    private lateinit var iconStylePreference: ListPreference
    private lateinit var iconColorModePreference: Preference
    private lateinit var shapeStylePreference: ListPreference
    private lateinit var animationIntensityPreference: ListPreference

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_personalization, rootKey)
        val context = requireContext()

        // 1. Theme and Color Controls
        colorThemeCategory = findPreference("category_color_theme")
        nightModePreference = requireNotNull(findPreference(KEY_NIGHT_MODE))
        blackNightThemePreference = requireNotNull(findPreference(KEY_BLACK_NIGHT_THEME))
        useSystemColorPreference = requireNotNull(findPreference(KEY_USE_SYSTEM_COLOR))
        customAccentPreference = requireNotNull(findPreference("custom_accent"))

        nightModePreference.apply {
            value = ShizukuSettings.getNightMode()
            setOnPreferenceChangeListener { _, value ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        syncDependentVisibility()
                        (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
                    }
                }
                true
            }
        }

        val blackNightAvailable = ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO
        setChildAvailable(blackNightThemePreference, blackNightAvailable)
        blackNightThemePreference.apply {
            if (blackNightAvailable) {
                isChecked = ThemeHelper.isBlackNightTheme(context)
                setOnPreferenceChangeListener { _, _ ->
                    if (ResourceUtils.isNightMode(context.resources.configuration))
                        (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
                    true
                }
            }
        }

        val systemColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        setChildAvailable(useSystemColorPreference, systemColorAvailable)
        useSystemColorPreference.apply {
            if (systemColorAvailable) {
                isChecked = ThemeHelper.isUsingSystemColor()
                setOnPreferenceChangeListener { _, value ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value) {
                            customAccentPreference.isEnabled = !value
                            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
                        }
                    }
                    true
                }
            }
        }

        customAccentPreference.apply {
            val isUsingSysColor = ThemeHelper.isUsingSystemColor()
            isEnabled = !isUsingSysColor
            summary = getCustomAccentSummary()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is String) {
                    val prefs = ShizukuSettings.getPreferences()
                    if (prefs.getString("custom_accent", "DEFAULT") != newValue) {
                        prefs.edit().putString("custom_accent", newValue).apply()
                        summary = getCustomAccentSummary(newValue)
                        (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
                    }
                }
                true
            }
        }

        // 2. M3 Expressive Controls
        expressiveShapesPreference = requireNotNull(findPreference(KEY_EXPRESSIVE_SHAPES))
        expressiveAnimationsPreference = requireNotNull(findPreference(KEY_EXPRESSIVE_ANIMATIONS))
        iconStylePreference = requireNotNull(findPreference(KEY_ICON_STYLE))
        iconColorModePreference = requireNotNull(findPreference(KEY_ICON_COLOR_MODE))
        shapeStylePreference = requireNotNull(findPreference(KEY_SHAPE_STYLE))
        animationIntensityPreference = requireNotNull(findPreference(KEY_ANIMATION_INTENSITY))

        // Only meaningful for the Two-Tone icon style - recreate() (triggered by iconStylePreference's
        // own listener below) recalculates this fresh from the persisted value on every style change.
        iconColorModePreference.isVisible = iconStylePreference.value == "twotone"

        expressiveShapesPreference.setOnPreferenceChangeListener { _, _ ->
            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            true
        }

        shapeStylePreference.setOnPreferenceChangeListener { _, _ ->
            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            true
        }

        iconStylePreference.setOnPreferenceChangeListener { _, _ ->
            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            true
        }

        iconColorModePreference.setOnPreferenceChangeListener { _, _ ->
            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            true
        }

        expressiveAnimationsPreference.setOnPreferenceChangeListener { _, _ ->
            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            true
        }

        animationIntensityPreference.setOnPreferenceChangeListener { _, _ ->
            (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            true
        }

        val simulatorPref = findPreference<HomeLayoutSimulatorPreference>("home_layout_simulator")
        simulatorPref?.setFragment(this)

        val switchKeys = listOf(
            "show_start_adb_home",
            "show_terminal_home",
            "show_automation_home",
            "show_activity_log_home",
            "show_learn_more_home"
        )

        for (prefKey in switchKeys) {
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, newValue)?.apply()
                    simulatorPref?.let { it.isVisible = false; it.isVisible = true }
                }
                true
            }
        }

        findPreference<TwoStatePreference>(KEY_COMPANION_MODE)?.apply {
            isChecked = ShizukuSettings.isCompanionModeEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setCompanionModeEnabled(newValue)
                true
            }
        }

        // 4. Language & System
        languagePreference = requireNotNull(findPreference(KEY_LANGUAGE))
        translationPreference = requireNotNull(findPreference(KEY_TRANSLATION))

        languagePreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                val locale: Locale = if ("SYSTEM" == newValue) {
                    LocaleDelegate.systemLocale
                } else {
                    Locale.forLanguageTag(newValue)
                }
                LocaleDelegate.defaultLocale = locale
                (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            }
            true
        }

        setupLocalePreference(languagePreference)

        translationPreference.apply {
            summary = context.getString(R.string.settings_translation_summary, context.getString(R.string.app_name))
            setOnPreferenceClickListener {
                CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
                true
            }
        }
    }

    private fun syncDependentVisibility() {
        setChildAvailable(
            blackNightThemePreference,
            ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Toggle a child's availability through its collapsible category when possible, so the
     * category's expand/collapse toggle doesn't override the condition. Falls back to a plain
     * visibility change if the child isn't inside a [CollapsiblePreferenceCategory].
     */
    private fun setChildAvailable(pref: Preference, available: Boolean) {
        val category = colorThemeCategory
        val key = pref.key
        if (category != null && key != null) {
            category.setChildAvailable(key, available)
        } else {
            pref.isVisible = available
        }
    }

    private fun setupLocalePreference(languagePreference: ListPreference) {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(locale)
            else
                locale.getDisplayName(locale)

            val localizedLocaleName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(currentLocale)
            else
                locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName<small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }
            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }
            else -> {
                ""
            }
        }
    }

    private fun getCustomAccentSummary(value: String? = null): String {
        val currentValue = value ?: ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
        return when (currentValue) {
            "VIOLET" -> "Midnight Violet applied"
            "GREEN" -> "Forest Green applied"
            "CRIMSON" -> "Crimson Rose applied"
            "OCEAN" -> "Deep Ocean applied"
            "DEFAULT" -> "Standard Blue (Default)"
            else -> "Standard Blue (Default)"
        }
    }
}
