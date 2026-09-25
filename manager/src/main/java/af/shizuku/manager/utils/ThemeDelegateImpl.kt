package af.shizuku.manager.utils

import android.content.Context
import android.content.res.Resources.Theme
import af.shizuku.core.ui.ThemeDelegate
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ShizukuSettings
import rikka.core.res.isNight
import af.shizuku.manager.R

class ThemeDelegateImpl : ThemeDelegate {
    override fun getThemeKey(context: Context): String {
        val customAccent = ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
        return ThemeHelper.getTheme(context) + ThemeHelper.isUsingSystemColor() + customAccent +
            ShizukuSettings.isRoundedEdgesEnabled() +
            ShizukuSettings.isExpressiveShapesEnabled() + ShizukuSettings.getShapeStyle() +
            ShizukuSettings.getIconStyle() + ShizukuSettings.getIconColorMode() +
            ShizukuSettings.isOneUiThemeEnabled() + ShizukuSettings.isOneHandedModeEnabled() +
            ShizukuSettings.isBoldTypographyEnabled() + ShizukuSettings.isExpandedHeadersEnabled() +
            ShizukuSettings.getCornerRoundingOverride() + ShizukuSettings.isAmoledPlusEnabled()
    }

    override fun isUsingSystemColor(): Boolean {
        return ThemeHelper.isUsingSystemColor()
    }

    override fun onApplyUserThemeResource(context: Context, theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (context.resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        } else {
            val customAccent = ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
            if (customAccent != "DEFAULT") {
                val isNight = context.resources.configuration.isNight()
                val styleRes = when (customAccent) {
                    "VIOLET"  -> if (isNight) R.style.ThemeOverlay_Accent_Violet_Dark  else R.style.ThemeOverlay_Accent_Violet
                    "GREEN"   -> if (isNight) R.style.ThemeOverlay_Accent_Green_Dark   else R.style.ThemeOverlay_Accent_Green
                    "CRIMSON" -> if (isNight) R.style.ThemeOverlay_Accent_Crimson_Dark else R.style.ThemeOverlay_Accent_Crimson
                    "OCEAN"   -> if (isNight) R.style.ThemeOverlay_Accent_Ocean_Dark   else R.style.ThemeOverlay_Accent_Ocean
                    "AMBER"   -> if (isNight) R.style.ThemeOverlay_Accent_Amber_Dark   else R.style.ThemeOverlay_Accent_Amber
                    "ROSE"    -> if (isNight) R.style.ThemeOverlay_Accent_Rose_Dark    else R.style.ThemeOverlay_Accent_Rose
                    "SLATE"   -> if (isNight) R.style.ThemeOverlay_Accent_Slate_Dark   else R.style.ThemeOverlay_Accent_Slate
                    else -> 0
                }
                if (styleRes != 0) {
                    theme.applyStyle(styleRes, true)
                }
            }
        }

        if (!ShizukuSettings.isRoundedEdgesEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_Shape_Sharp, true)
        } else if (!ShizukuSettings.isExpressiveShapesEnabled()) {
            // shape_style's preference entry is UI-disabled while expressive_shapes is off, so
            // it doesn't apply here regardless of its stored value - flatten to plain Material3.
            theme.applyStyle(R.style.ThemeOverlay_Shapes_Standard, true)
        } else {
            val shapeStyleRes = when (ShizukuSettings.getShapeStyle()) {
                "zen" -> 0 // "zen": keep the base Material3Expressive corner scale
                "classic" -> R.style.ThemeOverlay_Shape_Classic
                "squircle" -> R.style.ThemeOverlay_Shape_Squircle
                "cut" -> R.style.ThemeOverlay_Shape_Cut
                else -> R.style.ThemeOverlay_Shape_Modern // "modern" (default)
            }
            if (shapeStyleRes != 0) {
                theme.applyStyle(shapeStyleRes, true)
            }
        }

        // Bold typography overlay applied before One UI so One UI's values win when both are on.
        if (ShizukuSettings.isBoldTypographyEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_Typography_Bold, true)
        }

        // One UI theme overlay is structure-only (shapes/typography, see themes_overlay.xml) -
        // no color attrs, so it composes with whichever color source (dynamic/custom accent/
        // default) was applied above instead of competing with it, and needs no day/night variant.
        if (ShizukuSettings.isOneUiThemeEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_OneUI, true)
        }

        // Corner rounding overlay applied last so it wins over both shape_style and One UI corners.
        // "default" = Modern's 32dp ExtraLarge (already set by shape overlay); "system" = no overlay.
        val cornerOverlayRes = when (ShizukuSettings.getCornerRoundingOverride()) {
            "gentle"   -> R.style.ThemeOverlay_CornerRounding_Gentle
            "standard" -> R.style.ThemeOverlay_CornerRounding_Standard
            "large"    -> R.style.ThemeOverlay_CornerRounding_Large
            "maximum"  -> R.style.ThemeOverlay_CornerRounding_Maximum
            else -> 0
        }
        if (cornerOverlayRes != 0) {
            theme.applyStyle(cornerOverlayRes, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(context), true)
    }
}
