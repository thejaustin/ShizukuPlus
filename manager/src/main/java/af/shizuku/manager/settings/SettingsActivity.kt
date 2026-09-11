package af.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.*
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import af.shizuku.manager.R
import af.shizuku.manager.settings.compose.SettingsScreen
import af.shizuku.core.ui.AppActivity

@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : AppActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private var currentTitle by mutableStateOf("")
    private var searchResults by mutableStateOf<List<SettingsSearchEngine.SettingItem>>(emptyList())
    var themeVersion by mutableStateOf(0)

    private var preferenceScrollState: TopAppBarState? = null
    private var _isScrollIdle by mutableStateOf(true)

    fun onPreferenceListScrolled(dy: Int) {
        val state = preferenceScrollState ?: return
        val limit = state.heightOffsetLimit
        when {
            dy > 0 -> state.heightOffset = (state.heightOffset - dy).coerceAtLeast(limit)
            dy < 0 -> state.heightOffset = (state.heightOffset - dy).coerceAtMost(0f)
        }
        state.contentOffset -= dy
        _isScrollIdle = false
    }

    fun onPreferenceListScrollIdle() { _isScrollIdle = true }

    fun onThemeChanged() {
        themeVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AppActivity.onCreate() already calls enableEdgeToEdge() (which sets
        // setDecorFitsSystemWindows=false) based on the user's setting and Android version.
        // Repeating it unconditionally here was inconsistent with HomeActivity and caused
        // mismatched window state during the Explode transition, crashing on Android 16 (#483).

        SettingsSearchEngine.init(this)

        currentTitle = getString(R.string.settings_title)

        setContent {
            val tv = themeVersion
            af.shizuku.core.ui.compose.AppTheme(
                isBlackNightTheme = af.shizuku.manager.app.ThemeHelper.isBlackNightTheme(this),
                isOneUi = af.shizuku.manager.ShizukuSettings.isOneUiThemeEnabled(),
                themeVersion = tv
            ) {
                SettingsScreen(
                    title = currentTitle,
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
                    onContainerCreated = {
                        if (savedInstanceState == null && supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.fragment_container, SettingsFragment())
                                .commit()
                        }
                    },
                    isScrollIdle = _isScrollIdle,
                    onScrollStateCreated = { preferenceScrollState = it }
                )
            }
        }
    }

    private fun navigateToSetting(item: SettingsSearchEngine.SettingItem) {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, item.fragmentClass)
        fragment.arguments = Bundle().apply {
            putString("highlight_key", item.key)
        }

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        currentTitle = item.title
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentName)
        fragment.arguments = pref.extras

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        currentTitle = pref.title?.toString() ?: currentTitle
        return true
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
