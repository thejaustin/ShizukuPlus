package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Theme wrapper for Compose content hosted inside a preference row. The preference RecyclerView
 * lives in a Fragment embedded via AndroidView, so MaterialTheme from SettingsActivity's
 * setContent does not reach a nested ComposeView - re-apply the same AppTheme here.
 */
@Composable
internal fun PreferenceComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    AppTheme(
        isExpressiveMotion =
            af.shizuku.manager.ShizukuSettings
                .isExpressiveAnimationsEnabled(),
        isBlackNightTheme = ThemeHelper.isBlackNightTheme(context),
        isAmoledPlus = ShizukuSettings.isAmoledPlusEnabled(),
        isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
        isBoldTypography = ShizukuSettings.isBoldTypographyEnabled(),
        isHighContrast = ShizukuSettings.isHighContrastEnabled(),
        isRoundedEdges = ShizukuSettings.isRoundedEdgesEnabled(),
        content = content,
    )
}

/** Sets themed content on a preference's ComposeView. Safe to call on every bind. */
internal fun ComposeView.setPreferenceContent(content: @Composable () -> Unit) {
    setContent { PreferenceComposeTheme(content) }
}

/**
 * Helper to display stylized drawables inside Compose preference rows matching
 * standard Material 3 preference icon styling.
 */
@Composable
internal fun PreferenceIconView(
    drawable: Drawable?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (drawable == null) return
    val disabledAlpha = 0.38f
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        },
        update = { iv ->
            iv.setImageDrawable(drawable)
            iv.alpha = if (enabled) 1.0f else disabledAlpha
        },
        modifier = modifier.size(40.dp),
    )
}

/** Standard title/content/summary layout used by the Compose-backed preferences. */
@Composable
internal fun ComposePreferenceRow(
    title: CharSequence?,
    summary: CharSequence?,
    enabled: Boolean,
    icon: Drawable? = null,
    content: @Composable () -> Unit,
) {
    val disabledAlpha = 0.38f
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!title.isNullOrEmpty() || icon != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    PreferenceIconView(
                        drawable = icon,
                        enabled = enabled,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            MaterialTheme.colorScheme.onSurface.let {
                                if (enabled) it else it.copy(alpha = disabledAlpha)
                            },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.let {
                        if (enabled) it else it.copy(alpha = disabledAlpha)
                    },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Resolves dynamic M3E corner roundness for settings grid cards based on the corner override setting. */
internal fun resolveGridCardCorner(
    cornerOverride: String,
    checked: Boolean,
): androidx.compose.ui.unit.Dp =
    when (cornerOverride) {
        "sharp" -> if (checked) 6.dp else 2.dp
        "gentle" -> if (checked) 18.dp else 10.dp
        "standard" -> if (checked) 22.dp else 12.dp
        "large" -> if (checked) 28.dp else 16.dp
        "maximum" -> if (checked) 36.dp else 20.dp
        else -> if (checked) 24.dp else 14.dp
    }

/** Resolves dynamic M3E corner roundness for settings grid icons based on the corner override setting. */
internal fun resolveGridIconCorner(
    cornerOverride: String,
    checked: Boolean,
): androidx.compose.ui.unit.Dp =
    when (cornerOverride) {
        "sharp" -> if (checked) 2.dp else 4.dp
        "gentle" -> if (checked) 6.dp else 12.dp
        "standard" -> if (checked) 8.dp else 14.dp
        "large" -> if (checked) 10.dp else 18.dp
        "maximum" -> if (checked) 14.dp else 22.dp
        else -> if (checked) 8.dp else 16.dp
    }
