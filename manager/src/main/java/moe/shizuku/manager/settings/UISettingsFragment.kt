package moe.shizuku.manager.settings

import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.manager.ShizukuLocales
import java.util.Locale

class UISettingsFragment : BaseSettingsFragment() {

    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var useSystemColorPreference: TwoStatePreference
    private lateinit var languagePreference: ListPreference
    private lateinit var translationPreference: Preference

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_ui, rootKey)
        val context = requireContext()

        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!
        languagePreference = findPreference(KEY_LANGUAGE)!!
        translationPreference = findPreference(KEY_TRANSLATION)!!

        nightModePreference.apply {
            value = ShizukuSettings.getNightMode()
            setOnPreferenceChangeListener { _, value ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        syncDependentVisibility()
                        activity?.recreate()
                    }
                }
                true
            }
        }

        blackNightThemePreference.apply {
            isVisible = ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO
            if (isVisible) {
                isChecked = ThemeHelper.isBlackNightTheme(context)
                setOnPreferenceChangeListener { _, _ ->
                    if (ResourceUtils.isNightMode(context.resources.configuration))
                        activity?.recreate()
                    true
                }
            }
        }

        useSystemColorPreference.apply {
            isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (isVisible) {
                isChecked = ThemeHelper.isUsingSystemColor()
                setOnPreferenceChangeListener { _, value ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value)
                            activity?.recreate()
                    }
                    true
                }
            }
        }

        languagePreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                val locale: Locale = if ("SYSTEM" == newValue) {
                    LocaleDelegate.systemLocale
                } else {
                    Locale.forLanguageTag(newValue)
                }
                LocaleDelegate.defaultLocale = locale
                activity?.recreate()
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
        blackNightThemePreference.isVisible = ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO
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
}
