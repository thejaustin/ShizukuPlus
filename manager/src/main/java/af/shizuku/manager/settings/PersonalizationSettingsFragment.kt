package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuLocales
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CustomTabsHelper
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import java.util.Locale

class PersonalizationSettingsFragment : BaseSettingsFragment() {
    private val iconPickerLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .GetContent(),
        ) { uri ->
            if (uri != null) {
                CustomIconStudioBottomSheet
                    .newInstance(uri)
                    .show(childFragmentManager, "CustomIconStudio")
            }
        }

    // Change listeners run before the new value is saved (they return true to accept it), so
    // anything that reads the setting back must run on a post. Applied inline, the icon style and
    // header refresh showed the previous choice, one change behind.
    private fun applyTheme(requiresRecreate: Boolean = false) {
        val v = view
        val apply = {
            if (requiresRecreate) {
                (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
            } else {
                (activity as? SettingsActivity)?.onThemeChanged()
            }
        }
        if (v != null) v.post { apply() } else apply()
    }

    private fun postRefreshIconStyles() {
        view?.post { refreshIconStyles() } ?: refreshIconStyles()
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_category_appearance)

    private lateinit var nightModePreference: IntegerSegmentedControlPreference
    private lateinit var darkStylePreference: SegmentedControlPreference
    private lateinit var useSystemColorPreference: TwoStatePreference
    private lateinit var customAccentPreference: Preference
    private lateinit var languagePreference: ListPreference
    private lateinit var translationPreference: Preference
    private lateinit var expressiveShapesPreference: TwoStatePreference
    private var expressiveAnimationsPreference: TwoStatePreference? = null
    private lateinit var iconStylePreference: SegmentedControlPreference
    private lateinit var iconColorModePreference: SegmentedControlPreference
    private lateinit var shapeStylePreference: Preference
    private var animationIntensityPreference: SliderPreference? = null
    private var edgeToEdgePreference: TwoStatePreference? = null
    private var blurUiPreference: TwoStatePreference? = null
    private lateinit var oneUiThemePreference: TwoStatePreference

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_personalization, rootKey)
        val context = requireContext()

        // 1. Theme and Color Controls
        nightModePreference = requireNotNull(findPreference(KEY_NIGHT_MODE))
        darkStylePreference = requireNotNull(findPreference("dark_style"))
        useSystemColorPreference = requireNotNull(findPreference(KEY_USE_SYSTEM_COLOR))
        customAccentPreference = requireNotNull(findPreference("custom_accent"))

        nightModePreference.apply {
            value = ShizukuSettings.getNightMode()
            setOnPreferenceChangeListener { _, value ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        syncDependentVisibility()
                        applyTheme(requiresRecreate = true)
                    }
                }
                true
            }
        }

        // One "Dark style" choice replaces the Black night theme + AMOLED+ switch pair (AMOLED+
        // only ever made sense on top of Black). Same two keys underneath, so nothing resets.
        setChildAvailable(darkStylePreference, ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO)
        darkStylePreference.value = currentDarkStyle()
        darkStylePreference.setOnPreferenceChangeListener { _, newValue ->
            val style = newValue as? String ?: return@setOnPreferenceChangeListener false
            ShizukuSettings
                .getPreferences()
                .edit()
                .putBoolean(KEY_BLACK_NIGHT_THEME, style != DARK_STYLE_STANDARD)
                .putBoolean(KEY_AMOLED_PLUS, style == DARK_STYLE_AMOLED)
                .apply()
            if (ResourceUtils.isNightMode(context.resources.configuration)) {
                applyTheme(requiresRecreate = false)
            }
            true
        }

        val systemColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        setChildAvailable(useSystemColorPreference, systemColorAvailable)
        useSystemColorPreference.apply {
            if (systemColorAvailable) {
                isChecked = ThemeHelper.isUsingSystemColor()
                setOnPreferenceChangeListener { _, value ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value) {
                            // Hide (not grey out) the accent picker while system colors win:
                            // a disabled slider still showed a stale accent name.
                            setChildAvailable(customAccentPreference, !value)
                            applyTheme(requiresRecreate = false)
                        }
                    }
                    true
                }
            }
        }

        customAccentPreference.apply {
            setChildAvailable(this, !ThemeHelper.isUsingSystemColor())
            setCustomSummary(getCustomAccentSummary())
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is String) {
                    val prefs = ShizukuSettings.getPreferences()
                    if (prefs.getString("custom_accent", "DEFAULT") != newValue) {
                        prefs.edit().putString("custom_accent", newValue).apply()
                        setCustomSummary(getCustomAccentSummary(newValue))
                        // An accent changes every colour role: recreate so it shows now, not on
                        // the next resume.
                        view?.post { applyTheme(requiresRecreate = true) }
                    }
                }
                true
            }
        }

        // High contrast swaps the whole colour scheme (or the outlines with system colours), which
        // only a recreate re-applies.
        findPreference<Preference>(ShizukuSettings.Keys.KEY_HIGH_CONTRAST)?.apply {
            // With Full colour schemes (experimental) High contrast swaps the whole scheme.
            if (ShizukuSettings.isExperimentalColorSchemesEnabled() && !ThemeHelper.isUsingSystemColor()) {
                setCustomSummary(getString(R.string.settings_high_contrast_summary_full))
            }
            setOnPreferenceChangeListener { _, _ ->
                view?.post { applyTheme(requiresRecreate = true) }
                true
            }
        }

        // 2. M3 Expressive Controls
        expressiveShapesPreference = requireNotNull(findPreference(KEY_EXPRESSIVE_SHAPES))
        expressiveAnimationsPreference = findPreference(KEY_EXPRESSIVE_ANIMATIONS)
        iconStylePreference = requireNotNull(findPreference(KEY_ICON_STYLE))
        iconColorModePreference = requireNotNull(findPreference(KEY_ICON_COLOR_MODE))
        shapeStylePreference = requireNotNull(findPreference(KEY_SHAPE_STYLE))
        animationIntensityPreference = findPreference(KEY_ANIMATION_INTENSITY)

        // Only meaningful for the Two-Tone icon style — updated dynamically below so the
        // color-mode row appears/disappears without requiring a fragment recreate.
        setChildAvailable(iconColorModePreference, iconStylePreference.value == "twotone")
        setChildAvailable(shapeStylePreference, expressiveShapesPreference.isChecked)
        animationIntensityPreference?.let { animInt ->
            setChildAvailable(animInt, expressiveAnimationsPreference?.isChecked ?: ShizukuSettings.isExpressiveAnimationsEnabled())
        }

        // M3E morph shapes: only meaningful with expressive shapes on. The toggle morph works with
        // a picked shape or any rounded Shape style; Zen's leaf and Cut's octagon don't morph.
        val morphShapePreference = findPreference<Preference>("icon_morph_shape")
        val morphTogglePreference = findPreference<androidx.preference.TwoStatePreference>("icon_morph_on_toggle")
        val morphOffShapePreference = findPreference<Preference>("icon_morph_off_shape")
        val morphModePreference = findPreference<Preference>("icon_morph_mode")

        fun syncMorphAvailability(
            shapesOn: Boolean = expressiveShapesPreference.isChecked,
            shape: String = ShizukuSettings.getIconMorphShape(),
            morphOn: Boolean = morphTogglePreference?.isChecked ?: ShizukuSettings.isIconMorphOnToggle(),
        ) {
            val morphable =
                shapesOn &&
                    (shape != af.shizuku.manager.utils.ExpressiveShapes.OFF || ShizukuSettings.getShapeStyle() !in setOf("zen", "cut"))
            morphShapePreference?.setAvailable(shapesOn)
            morphTogglePreference?.setAvailable(morphable)
            morphOffShapePreference?.setAvailable(morphable && morphOn)
            morphModePreference?.setAvailable(morphable && morphOn)
        }
        syncMorphAvailability()

        expressiveShapesPreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                setChildAvailable(shapeStylePreference, newValue)
                syncMorphAvailability(shapesOn = newValue)
            }
            postRefreshIconStyles()
            true
        }
        morphShapePreference?.setOnPreferenceChangeListener { _, newValue ->
            val shape = newValue as? String ?: return@setOnPreferenceChangeListener false
            syncMorphAvailability(shape = shape)
            // Restyle once the new value has been persisted.
            view?.post {
                postRefreshIconStyles()
            }
            true
        }
        morphTogglePreference?.setOnPreferenceChangeListener { _, newValue ->
            syncMorphAvailability(morphOn = newValue as? Boolean ?: true)
            view?.post { refreshIconStyles() }
            true
        }
        morphOffShapePreference?.setOnPreferenceChangeListener { _, _ ->
            // Containers are built with their off shape: restyle once the new value is saved.
            view?.post {
                postRefreshIconStyles()
            }
            true
        }
        morphModePreference?.setOnPreferenceChangeListener { _, _ ->
            view?.post { refreshIconStyles() }
            true
        }

        shapeStylePreference.setOnPreferenceChangeListener { _, _ ->
            // The style is a theme overlay (every corner token): recreate once it's saved.
            view?.post { applyTheme(requiresRecreate = true) }
            true
        }

        iconStylePreference.setOnPreferenceChangeListener { _, newValue ->
            // Show/hide the color-mode sub-option immediately rather than on next recreate.
            setChildAvailable(iconColorModePreference, newValue == "twotone")
            postRefreshIconStyles()
            true
        }

        iconColorModePreference.setOnPreferenceChangeListener { _, _ ->
            applyTheme(requiresRecreate = false)
            postRefreshIconStyles()
            true
        }

        // One corner control (Sharp ... Maximum): Sharp swaps every shape token, which only a
        // recreate re-applies; post so the new value is saved first.
        val cornerPref = findPreference<androidx.preference.ListPreference>("corner_rounding_override")
        val homeCornerPref = findPreference<androidx.preference.TwoStatePreference>("apply_corner_rounding_to_home")

        fun syncCornerAvailability(value: String?) {
            val isCustom = value != null && value != "default"
            homeCornerPref?.isEnabled = isCustom
            if (!isCustom) {
                homeCornerPref?.summary = getString(R.string.settings_apply_corner_rounding_to_home_disabled_hint)
            } else {
                homeCornerPref?.setSummary(R.string.settings_apply_corner_rounding_to_home_summary)
            }
        }
        syncCornerAvailability(cornerPref?.value ?: ShizukuSettings.getCornerRoundingOverride())

        cornerPref?.setOnPreferenceChangeListener { _, newValue ->
            syncCornerAvailability(newValue as? String)
            view?.post { applyTheme(requiresRecreate = true) }
            true
        }

        homeCornerPref?.setOnPreferenceChangeListener { _, _ ->
            true
        }

        findPreference<Preference>("expanded_headers_enabled")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { applyTheme(requiresRecreate = false) }
            true
        }

        expressiveAnimationsPreference?.setOnPreferenceChangeListener { _, _ ->
            // isChecked updates after this returns; re-sync once it has.
            view?.post { syncIntensityAvailability() }
            true
        }

        animationIntensityPreference?.setOnPreferenceChangeListener { _, _ ->
            true
        }

        // Off / Follow system / Always on replaces "Haptic feedback" + its dependent
        // "Follow system haptics" switch; writes the same two keys.
        findPreference<SegmentedControlPreference>("haptics_mode")?.apply {
            val prefs = ShizukuSettings.getPreferences()
            value =
                when {
                    !prefs.getBoolean(ShizukuSettings.Keys.KEY_HAPTIC_FEEDBACK, true) -> "off"
                    prefs.getBoolean(ShizukuSettings.Keys.KEY_FOLLOW_SYSTEM_HAPTICS, false) -> "system"
                    else -> "always"
                }
            setOnPreferenceChangeListener { _, newValue ->
                val mode = newValue as? String ?: return@setOnPreferenceChangeListener false
                prefs
                    .edit()
                    .putBoolean(ShizukuSettings.Keys.KEY_HAPTIC_FEEDBACK, mode != "off")
                    .putBoolean(ShizukuSettings.Keys.KEY_FOLLOW_SYSTEM_HAPTICS, mode == "system")
                    .apply()
                true
            }
        }

        // Intensity is ignored while following the system animation speed - hide it then.
        syncIntensityAvailability()
        findPreference<TwoStatePreference>("follow_system_animations")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { syncIntensityAvailability() }
            true
        }

        // One UI and one-handed mode always use expanded headers, so the switch does nothing there.
        findPreference<Preference>("expanded_headers_enabled")?.let {
            setChildAvailable(it, !ShizukuSettings.isOneUiThemeEnabled() && !ShizukuSettings.isOneHandedModeEnabled())
        }

        findPreference<Preference>("rearrange_home_cards")?.setOnPreferenceClickListener {
            af.shizuku.manager.home.HomeEditMode
                .enter(force = true)
            startActivity(
                android.content
                    .Intent(requireContext(), af.shizuku.manager.home.HomeActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            true
        }

        // Experimental (Developer options): hide the whole App icon & name section unless enabled.
        findPreference<Preference>("category_app_identity")?.isVisible =
            ShizukuSettings.isExperimentalAppIdentityEnabled()

        findPreference<EditTextPreference>("custom_app_name")?.apply {
            // Don't seed `text`: EditTextPreference.setText() persists, which would save this
            // flavor's own name as a "custom" one and peer sync would copy it to the other flavor.
            // Show the effective name in the summary and the dialog instead.
            summaryProvider =
                Preference.SummaryProvider<EditTextPreference> {
                    ShizukuSettings.getCustomAppName(requireContext())
                }
            setOnBindEditTextListener { editText ->
                editText.setText(ShizukuSettings.getCustomAppName(requireContext()))
                editText.setSelection(editText.text.length)
            }
            setOnPreferenceChangeListener { pref, newValue ->
                if (newValue is String) {
                    // Entering the default name (or nothing) clears the custom name.
                    val name = newValue.trim()
                    ShizukuSettings.setCustomAppName(if (name == getString(R.string.app_name)) "" else name)
                    // Re-setting the provider is the public way to refresh a provider summary.
                    pref.summaryProvider = pref.summaryProvider
                }
                // Already persisted above; returning true would write the raw value again.
                false
            }
        }

        findPreference<Preference>("custom_icon_studio")?.setOnPreferenceClickListener {
            try {
                CustomIconStudioBottomSheet
                    .newInstance(null)
                    .show(childFragmentManager, "custom_icon_studio")
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Failed to launch custom icon studio")
            }
            true
        }

        // The Home activity log card needs logging on (Advanced > Diagnostics > Enable activity log):
        // a cross-screen dependency, so grey it out here instead of android:dependency.
        findPreference<Preference>(ShizukuSettings.Keys.KEY_SHOW_ACTIVITY_LOG_HOME)?.isEnabled =
            ShizukuSettings.isActivityLogEnabled()

        findPreference<TwoStatePreference>(KEY_COMPANION_MODE)?.apply {
            isChecked = ShizukuSettings.isCompanionModeEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setCompanionModeEnabled(newValue)
                true
            }
        }

        // 3. Display settings (edge-to-edge, blur)
        edgeToEdgePreference = findPreference(KEY_EDGE_TO_EDGE)
        edgeToEdgePreference?.let { pref ->
            pref.isChecked = ShizukuSettings.isEdgeToEdgeEnabled()
            // Android 15+ (API 35) enforces E2E for targetSdk-35 apps — the toggle has no effect
            // there, so hide it to avoid confusing users (#483).
            setChildAvailable(pref, Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM)
            pref.setOnPreferenceChangeListener { _, _ ->
                applyTheme(requiresRecreate = false)
                true
            }
        }

        blurUiPreference = findPreference(KEY_BLUR_UI)
        blurUiPreference?.let { pref ->
            pref.isChecked = ShizukuSettings.isBlurUiEnabled()
            setChildAvailable(pref, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            pref.setOnPreferenceChangeListener { _, _ ->
                // window.setBackgroundBlurRadius is set in onCreate; a recreate is needed for it to take
                // effect. The AppBar translucency also needs onPostCreate to re-run (#449).
                view?.post { applyTheme(requiresRecreate = true) }
                true
            }
        }

        oneUiThemePreference = requireNotNull(findPreference(KEY_ONEUI_THEME))
        oneUiThemePreference.isChecked = ShizukuSettings.isOneUiThemeEnabled()
        oneUiThemePreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings
                    .getPreferences()
                    .edit()
                    .putBoolean(KEY_ONEUI_THEME, newValue)
                    .apply()
                view?.post { applyTheme(requiresRecreate = true) }
            }
            true
        }

        findPreference<TwoStatePreference>(KEY_ONE_HANDED_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings
                    .getPreferences()
                    .edit()
                    .putBoolean(KEY_ONE_HANDED_MODE, newValue)
                    .apply()
                view?.post { applyTheme(requiresRecreate = true) }
            }
            true
        }

        findPreference<androidx.preference.MultiSelectListPreference>(af.shizuku.manager.widget.FloatingNavBar.KEY_TABS)?.apply {
            fun describe(values: Set<String>) {
                val names =
                    entryValues
                        .zip(entries)
                        .filter { (v, _) -> v.toString() in values }
                        .joinToString(", ") { it.second }
                summary = getString(R.string.settings_floating_nav_tabs_summary, names)
            }
            describe(values)
            setOnPreferenceChangeListener { _, v ->
                @Suppress("UNCHECKED_CAST")
                val tabs = v as Set<String>
                if (tabs.isEmpty()) return@setOnPreferenceChangeListener false
                describe(tabs)
                true
            }
        }

        // 4. Language & System
        languagePreference = requireNotNull(findPreference(KEY_LANGUAGE))
        translationPreference = requireNotNull(findPreference(KEY_TRANSLATION))

        languagePreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                val locale: Locale =
                    if ("SYSTEM" == newValue) {
                        LocaleDelegate.systemLocale
                    } else {
                        Locale.forLanguageTag(newValue)
                    }
                // Immediate fallback in case setApplicationLocales() below doesn't trigger a
                // recreate for some reason; AppActivity.attachBaseContext() re-syncs this from
                // AppCompatDelegate on every activity creation regardless (#429), so this is
                // belt-and-suspenders, not the actual mechanism.
                LocaleDelegate.defaultLocale = locale

                // #429: setApplicationLocales() triggers its own recreate on API <33 (and a
                // config-change/recreate on 33+), so we must NOT also call
                // applyTheme(requiresRecreate = true) here — that would double-recreate the
                // activity or risk a recreate loop.
                val requested =
                    if ("SYSTEM" == newValue) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(newValue)
                    }
                if (AppCompatDelegate.getApplicationLocales() != requested) {
                    AppCompatDelegate.setApplicationLocales(requested)
                } else {
                    // No-op locale change (e.g. re-selecting current language) —
                    // still apply any pending theme-only changes normally.
                    applyTheme(requiresRecreate = false)
                }
            }
            true
        }

        setupLocalePreference(languagePreference)

        translationPreference.apply {
            setCustomSummary(context.getString(R.string.settings_translation_summary, context.getString(R.string.app_name)))
            widgetLayoutResource = R.layout.preference_widget_open_in_new
            setOnPreferenceClickListener {
                CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
                true
            }
        }
    }

    /** Intensity needs animations on and isn't used while following the system speed. */
    private fun syncIntensityAvailability() {
        val animationsOn = expressiveAnimationsPreference?.isChecked ?: ShizukuSettings.isExpressiveAnimationsEnabled()
        val followSystem = findPreference<TwoStatePreference>("follow_system_animations")
        animationIntensityPreference?.let {
            setChildAvailable(it, animationsOn && followSystem?.isChecked != true)
        }
        followSystem?.let { setChildAvailable(it, animationsOn) }
    }

    private fun currentDarkStyle(): String =
        when {
            ShizukuSettings.isAmoledPlusEnabled() -> DARK_STYLE_AMOLED
            ThemeHelper.isBlackNightTheme(requireContext()) -> DARK_STYLE_BLACK
            else -> DARK_STYLE_STANDARD
        }

    private fun syncDependentVisibility() {
        val nightOn = ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO
        setChildAvailable(darkStylePreference, nightOn)
    }

    /**
     * Toggle a child's availability through its collapsible category when possible, so the
     * category's expand/collapse toggle doesn't override the condition. Falls back to a plain
     * visibility change if the child isn't inside a [CollapsiblePreferenceCategory].
     */
    private fun setChildAvailable(
        pref: Preference,
        available: Boolean,
    ) {
        val key = pref.key
        val category = pref.parent as? CollapsiblePreferenceCategory
        if (key != null && category != null) {
            category.setChildAvailable(key, available)
        } else {
            pref.isVisible = available
        }
    }

    private fun setupLocalePreference(languagePreference: ListPreference) {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

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
            val rawLocaleName =
                if (!TextUtils.isEmpty(locale.script)) {
                    locale.getDisplayScript(locale)
                } else {
                    locale.getDisplayName(locale)
                }
            val localeName =
                if (displayLocale == "az" || displayLocale == "az-AZ") {
                    "Azərbaycan dili"
                } else {
                    rawLocaleName.replaceFirstChar { if (it.isLowerCase()) it.uppercase(locale) else it.toString() }
                }

            val rawLocalizedLocaleName =
                if (!TextUtils.isEmpty(locale.script)) {
                    locale.getDisplayScript(currentLocale)
                } else {
                    locale.getDisplayName(currentLocale)
                }
            val localizedLocaleName = rawLocalizedLocaleName.replaceFirstChar { if (it.isLowerCase()) it.uppercase(currentLocale) else it.toString() }

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName</small>".toHtml()
                } else {
                    localizedLocaleName
                },
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.setCustomSummary(
            when {
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
            },
        )
    }

    private fun getCustomAccentSummary(value: String? = null): String {
        val currentValue = value ?: ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
        return when (currentValue) {
            "VIOLET" -> getString(R.string.settings_accent_violet_applied)
            "GREEN" -> getString(R.string.settings_accent_green_applied)
            "CRIMSON" -> getString(R.string.settings_accent_crimson_applied)
            "OCEAN" -> getString(R.string.settings_accent_ocean_applied)
            "AMBER" -> getString(R.string.settings_accent_amber_applied)
            "ROSE" -> getString(R.string.settings_accent_rose_applied)
            "SLATE" -> getString(R.string.settings_accent_slate_applied)
            "DEFAULT", null -> getString(R.string.settings_accent_default_applied)
            // No "... applied" string for the newer accents (Cyan, Neon): use the accent's name.
            else -> {
                val values = resources.getStringArray(R.array.custom_accents_values)
                resources.getStringArray(R.array.custom_accents).getOrNull(values.indexOf(currentValue))
                    ?: getString(R.string.settings_accent_default_applied)
            }
        }
    }
}

private const val DARK_STYLE_STANDARD = "standard"
private const val DARK_STYLE_BLACK = "black"
private const val DARK_STYLE_AMOLED = "amoled"
