package af.shizuku.manager.activitylog

import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.home.compose.ActivityLogScreen
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext

class ActivityLogActivity : AppActivity() {
    companion object {
        const val EXTRA_FILTER_CONNECTED = "extra_filter_connected"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filterConnected = intent?.getBooleanExtra(EXTRA_FILTER_CONNECTED, false) ?: false
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
                ActivityLogScreen(
                    showTopBar = true,
                    onBackClick = { finishAfterTransition() },
                    initialFilterConnected = filterConnected,
                )
            }
        }
    }
}
