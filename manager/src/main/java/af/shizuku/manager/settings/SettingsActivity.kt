package af.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import af.shizuku.manager.R
import af.shizuku.manager.settings.compose.SettingsScreen
import af.shizuku.core.ui.AppActivity

class SettingsActivity : AppActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private var currentTitle by mutableStateOf("")
    private var searchResults by mutableStateOf<List<SettingsSearchEngine.SettingItem>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        SettingsSearchEngine.init(this)

        currentTitle = getString(R.string.settings_title)

        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                isBlackNightTheme = af.shizuku.manager.app.ThemeHelper.isBlackNightTheme(this)
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
                    }
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
