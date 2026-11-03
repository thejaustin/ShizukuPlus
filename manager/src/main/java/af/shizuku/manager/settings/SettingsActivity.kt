package af.shizuku.manager.settings

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.settings.compose.SettingsScreen
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.*
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity :
    AppActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private var currentTitle by mutableStateOf("")
    private var searchResults by mutableStateOf<List<SettingsSearchEngine.SettingItem>>(emptyList())
    var themeVersion by mutableStateOf(0)
    private var isOneUi by mutableStateOf(
        af.shizuku.manager.ShizukuSettings
            .isOneUiThemeEnabled(),
    )
    private var isOneHanded by mutableStateOf(
        af.shizuku.manager.ShizukuSettings
            .isOneHandedModeEnabled(),
    )

    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == af.shizuku.manager.ShizukuSettings.Keys.KEY_ONEUI_THEME ||
                key == af.shizuku.manager.ShizukuSettings.Keys.KEY_ONE_HANDED_MODE
            ) {
                isOneUi =
                    af.shizuku.manager.ShizukuSettings
                        .isOneUiThemeEnabled()
                isOneHanded =
                    af.shizuku.manager.ShizukuSettings
                        .isOneHandedModeEnabled()
                themeVersion++
            }
        }

    private var preferenceScrollState: TopAppBarState? = null
    private var _isScrollIdle by mutableStateOf(true)

    fun getExpandedHeaderHeightPx(): Int {
        val isOneUi =
            af.shizuku.manager.ShizukuSettings
                .isOneUiThemeEnabled()
        val isOneHanded =
            af.shizuku.manager.ShizukuSettings
                .isOneHandedModeEnabled()
        val isExpanded =
            isOneUi ||
                isOneHanded ||
                af.shizuku.manager.ShizukuSettings
                    .isExpandedHeadersEnabled()
        val dp = if (isExpanded) expandedHeaderHeightDp(resources.configuration.screenHeightDp) else 64f
        return (dp * resources.displayMetrics.density).toInt()
    }

    fun onPreferenceListScrolled(
        rv: androidx.recyclerview.widget.RecyclerView,
        dy: Int,
    ) {
        val state = preferenceScrollState ?: return
        val limit = state.heightOffsetLimit
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val firstPos = lm?.findFirstVisibleItemPosition() ?: 0
        if (firstPos <= 0) {
            val firstChild = lm?.findViewByPosition(0)
            if (firstChild != null) {
                val scrolledPx = (rv.paddingTop - firstChild.top).coerceAtLeast(0)
                state.heightOffset = (-scrolledPx.toFloat()).coerceIn(limit, 0f)
            } else {
                state.heightOffset = 0f
            }
        } else {
            state.heightOffset = limit
        }
        state.contentOffset -= dy
        _isScrollIdle = false
    }

    fun onPreferenceListScrolled(
        dy: Int,
        canScrollUp: Boolean = true,
        scrollOffset: Int = 0,
    ) {
        val state = preferenceScrollState ?: return
        val limit = state.heightOffsetLimit
        if (!canScrollUp) {
            state.heightOffset = 0f
        } else {
            state.heightOffset = (state.heightOffset - dy).coerceIn(limit, 0f)
        }
        state.contentOffset -= dy
        _isScrollIdle = false
    }

    fun onPreferenceListScrollIdle() {
        _isScrollIdle = true
    }

    fun onThemeChanged() {
        val newOneUi =
            af.shizuku.manager.ShizukuSettings
                .isOneUiThemeEnabled()
        val newOneHanded =
            af.shizuku.manager.ShizukuSettings
                .isOneHandedModeEnabled()
        if (isOneUi != newOneUi || isOneHanded != newOneHanded) {
            isOneUi = newOneUi
            isOneHanded = newOneHanded
            themeVersion++
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? BaseSettingsFragment)?.updateHeaderPadding()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        af.shizuku.manager.ShizukuSettings
            .getPreferences()
            ?.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AppActivity.onCreate() already calls enableEdgeToEdge() (which sets
        // setDecorFitsSystemWindows=false) based on the user's setting and Android version.
        // Repeating it unconditionally here was inconsistent with HomeActivity and caused
        // mismatched window state during the Explode transition, crashing on Android 16 (#483).

        af.shizuku.manager.ShizukuSettings
            .getPreferences()
            ?.registerOnSharedPreferenceChangeListener(prefsListener)

        supportFragmentManager.addOnBackStackChangedListener {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BaseSettingsFragment
            val title = currentFragment?.getTitle()?.toString()
            if (title != null) {
                currentTitle = title
            } else if (supportFragmentManager.backStackEntryCount == 0) {
                currentTitle = getString(R.string.settings_title)
            }
        }

        SettingsSearchEngine.init(this)

        currentTitle = getString(R.string.settings_title)

        setContent {
            val tv = themeVersion
            af.shizuku.core.ui.compose.AppTheme(
                isExpressiveMotion =
                    af.shizuku.manager.ShizukuSettings
                        .isExpressiveAnimationsEnabled(),
                isBlackNightTheme =
                    af.shizuku.manager.app.ThemeHelper
                        .isBlackNightTheme(this),
                isAmoledPlus =
                    af.shizuku.manager.ShizukuSettings
                        .isAmoledPlusEnabled(),
                isOneUi = isOneUi,
                isBoldTypography =
                    af.shizuku.manager.ShizukuSettings
                        .isBoldTypographyEnabled(),
                isHighContrast =
                    af.shizuku.manager.ShizukuSettings
                        .isHighContrastEnabled(),
                isRoundedEdges =
                    af.shizuku.manager.ShizukuSettings
                        .isRoundedEdgesEnabled(),
                themeVersion = tv,
            ) {
                val customName =
                    af.shizuku.manager.ShizukuSettings
                        .getCustomAppName(this)
                val displayTitle =
                    if (currentTitle == getString(R.string.settings_title) && customName != getString(R.string.app_name)) {
                        "$customName Settings"
                    } else {
                        currentTitle
                    }
                SettingsScreen(
                    title = displayTitle,
                    isOneUi = isOneUi,
                    isOneHanded = isOneHanded,
                    showNavigateUp =
                        !(
                            af.shizuku.manager.widget.FloatingNavBar
                                .isTopLevel(this@SettingsActivity) &&
                                currentTitle == getString(R.string.settings_title)
                        ),
                    onNavigateUp = {
                        if (!onSupportNavigateUp()) {
                            finish()
                        }
                    },
                    onNavigateToSetting = { item -> navigateToSetting(item) },
                    searchResults = searchResults,
                    onSearchQueryChanged = { query ->
                        if (query.isBlank()) {
                            searchResults = emptyList()
                        } else {
                            searchResults = SettingsSearchEngine.search(this, query)
                        }
                    },
                    onContainerCreated = containerCreated@{
                        if (isFinishing || isDestroyed || supportFragmentManager.isDestroyed) return@containerCreated
                        val existing = supportFragmentManager.findFragmentById(R.id.fragment_container)
                        if (existing == null) {
                            val highlightKey = intent?.getStringExtra("highlight_key")
                            val initialItem = highlightKey?.let { SettingsSearchEngine.findItemByKey(this@SettingsActivity, it) }
                            if (initialItem != null) {
                                intent?.removeExtra("highlight_key")
                                val rootFrag = SettingsFragment()
                                val targetFrag =
                                    supportFragmentManager.fragmentFactory.instantiate(classLoader, initialItem.fragmentClass).apply {
                                        arguments =
                                            Bundle().apply {
                                                putString("highlight_key", initialItem.key)
                                            }
                                    }
                                applySharedAxis(targetFrag)
                                runCatching {
                                    supportFragmentManager
                                        .beginTransaction()
                                        .replace(R.id.fragment_container, rootFrag)
                                        .commitNowAllowingStateLoss()
                                    supportFragmentManager
                                        .beginTransaction()
                                        .setReorderingAllowed(true)
                                        .replace(R.id.fragment_container, targetFrag)
                                        .addToBackStack(null)
                                        .commitAllowingStateLoss()
                                }.onFailure {
                                    runCatching {
                                        supportFragmentManager
                                            .beginTransaction()
                                            .replace(R.id.fragment_container, SettingsFragment())
                                            .commitAllowingStateLoss()
                                    }
                                }
                            } else {
                                runCatching {
                                    supportFragmentManager
                                        .beginTransaction()
                                        .replace(R.id.fragment_container, SettingsFragment())
                                        .commitNowAllowingStateLoss()
                                }.onFailure {
                                    // commitNow throws while the FM is mid-transaction; a queued
                                    // commit still fills the container instead of leaving it blank.
                                    runCatching {
                                        supportFragmentManager
                                            .beginTransaction()
                                            .replace(R.id.fragment_container, SettingsFragment())
                                            .commitAllowingStateLoss()
                                    }
                                }
                            }
                        } else if (existing.view == null) {
                            runCatching {
                                supportFragmentManager
                                    .beginTransaction()
                                    .detach(existing)
                                    .attach(existing)
                                    .commitNowAllowingStateLoss()
                                if (existing.view == null) {
                                    val fresh =
                                        supportFragmentManager.fragmentFactory.instantiate(classLoader, existing.javaClass.name)
                                    fresh.arguments = existing.arguments
                                    supportFragmentManager
                                        .beginTransaction()
                                        .replace(R.id.fragment_container, fresh)
                                        .commitNowAllowingStateLoss()
                                }
                            }.onFailure {
                                runCatching {
                                    supportFragmentManager
                                        .beginTransaction()
                                        .replace(R.id.fragment_container, SettingsFragment())
                                        .commitAllowingStateLoss()
                                }
                            }
                        }
                    },
                    isScrollIdle = _isScrollIdle,
                    onScrollStateCreated = { preferenceScrollState = it },
                )
            }
        }
    }

    // A tap can land after the screen is closing (SHIZUKUPLUS-A2: "FragmentManager has been
    // destroyed"); a commit then throws, so drop the navigation instead.
    private fun canNavigate() = !isFinishing && !isDestroyed && !supportFragmentManager.isDestroyed && !supportFragmentManager.isStateSaved

    private fun navigateToSetting(item: SettingsSearchEngine.SettingItem) {
        if (!canNavigate()) return
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, item.fragmentClass)
        fragment.arguments =
            Bundle().apply {
                putString("highlight_key", item.key)
            }

        applySharedAxis(fragment)
        supportFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
        // Title is updated by the fragment's onResume → updateTitle(); no need to set it here.
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragmentName = pref.fragment ?: return false
        if (!canNavigate()) return true
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentName)
        fragment.arguments = pref.extras

        applySharedAxis(fragment)
        supportFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        currentTitle = pref.title?.toString() ?: currentTitle
        return true
    }

    /**
     * M3 Shared Axis X screen transitions: settings pages slide on the shared X axis
     * (forward in, back out) instead of cutting.
     */
    private fun applySharedAxis(next: androidx.fragment.app.Fragment) {
        if (!af.shizuku.manager.ShizukuSettings
                .isExpressiveAnimationsEnabled() &&
            !af.shizuku.manager.ShizukuSettings
                .isExperimentalScreenTransitionsEnabled()
        ) {
            return
        }
        val axis = com.google.android.material.transition.MaterialSharedAxis.X
        next.enterTransition =
            com.google.android.material.transition
                .MaterialSharedAxis(axis, true)
        next.returnTransition =
            com.google.android.material.transition
                .MaterialSharedAxis(axis, false)
        supportFragmentManager.findFragmentById(R.id.fragment_container)?.apply {
            exitTransition =
                com.google.android.material.transition
                    .MaterialSharedAxis(axis, true)
            reenterTransition =
                com.google.android.material.transition
                    .MaterialSharedAxis(axis, false)
        }
    }

    fun updateTitle(title: String) {
        currentTitle = title
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return super.onSupportNavigateUp()
    }
}

/**
 * Expanded settings header height, shared by the Compose header and the preference list's top
 * padding so the two can never drift apart. One UI / one-handed keep a tall reach-friendly header;
 * plain "expanded headers" use the M3 large top app bar range instead of a third of the screen.
 */
internal fun expandedHeaderHeightDp(screenHeightDp: Int): Float {
    val reachMode =
        af.shizuku.manager.ShizukuSettings
            .isOneUiThemeEnabled() ||
            af.shizuku.manager.ShizukuSettings
                .isOneHandedModeEnabled()
    return if (reachMode) {
        (screenHeightDp * 0.30f).coerceIn(200f, 290f)
    } else {
        (screenHeightDp * 0.22f).coerceIn(152f, 200f)
    }
}
