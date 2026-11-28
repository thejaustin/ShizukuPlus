package af.shizuku.manager.home

import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.home.compose.SystemHubScreen
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext

class SystemHubActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AppActivity.onCreate() already calls enableEdgeToEdge() — repeating
        // setDecorFitsSystemWindows here causes inconsistent window state during Explode
        // transitions and crashes on Android 16+ (#483, #529).
        setContent {
            val context = LocalContext.current
            AppTheme(
                isExpressiveMotion =
                    af.shizuku.manager.ShizukuSettings
                        .isExpressiveMotionScheme(),
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(context),
                isAmoledPlus = ShizukuSettings.isAmoledPlusEnabled(),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                isBoldTypography = ShizukuSettings.isBoldTypographyEnabled(),
                isRoundedEdges = ShizukuSettings.isRoundedEdgesEnabled(),
            ) {
                SystemHubScreen(
                    onBackClick = { finishAfterTransition() },
                    showBack =
                        !af.shizuku.manager.widget.FloatingNavBar
                            .isTopLevel(this),
                )
            }
        }
    }
}
