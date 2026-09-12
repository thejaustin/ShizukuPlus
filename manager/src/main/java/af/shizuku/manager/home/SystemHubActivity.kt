package af.shizuku.manager.home

import android.os.Bundle
import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import af.shizuku.manager.home.compose.SystemHubScreen

class SystemHubActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val context = LocalContext.current
            AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(context),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                isRoundedEdges = ShizukuSettings.isRoundedEdgesEnabled()
            ) {
                SystemHubScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }
}
