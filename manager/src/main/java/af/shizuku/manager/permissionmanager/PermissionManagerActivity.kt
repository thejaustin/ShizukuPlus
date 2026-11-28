package af.shizuku.manager.permissionmanager

import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext

class PermissionManagerActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AppActivity.onCreate() already calls enableEdgeToEdge() — repeating
        // setDecorFitsSystemWindows here causes inconsistent window state and the same
        // Explode-transition crash seen in HomeActivity/SettingsActivity (#483, #529).
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
                PermissionManagerScreen(onBackClick = { finishAfterTransition() })
            }
        }
    }
}
