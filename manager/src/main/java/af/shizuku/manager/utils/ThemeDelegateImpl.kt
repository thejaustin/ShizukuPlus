package af.shizuku.manager.utils

import af.shizuku.core.ui.ThemeDelegate
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import android.app.UiModeManager
import android.content.Context
import android.content.res.Resources.Theme
import android.os.Build
import rikka.core.res.isNight

class ThemeDelegateImpl : ThemeDelegate {
    override fun getThemeKey(context: Context): String {
        val customAccent = ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
        return ThemeHelper.getTheme(context) + ThemeHelper.isUsingSystemColor() + customAccent +
            ShizukuSettings.isRoundedEdgesEnabled() +
            ShizukuSettings.isExpressiveShapesEnabled() + ShizukuSettings.getShapeStyle() +
            ShizukuSettings.getIconStyle() + ShizukuSettings.getIconColorMode() +
            ShizukuSettings.isOneUiThemeEnabled() + ShizukuSettings.isOneHandedModeEnabled() +
            ShizukuSettings.isBoldTypographyEnabled() + ShizukuSettings.isExpandedHeadersEnabled() +
            ShizukuSettings.getCornerRoundingOverride() + ShizukuSettings.isAmoledPlusEnabled() +
            ShizukuSettings.isHighContrastEnabled() + ShizukuSettings.isExpressiveAnimationsEnabled() +
            ShizukuSettings.isExperimentalColorSchemesEnabled() + contrastLevel(context)
    }

    override fun isUsingSystemColor(): Boolean = ThemeHelper.isUsingSystemColor()

    override fun onApplyUserThemeResource(
        context: Context,
        theme: Theme,
        isDecorView: Boolean,
    ) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (context.resources.configuration.isNight()) {
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            } else {
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
            }
        } else {
            val accent = ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
            val dark = context.resources.configuration.isNight()
            if (ShizukuSettings.isExperimentalColorSchemesEnabled()) {
                applyGeneratedAccent(context, theme, accent, dark)
            } else {
                applyClassicAccent(context, theme, accent, dark)
            }
        }

        // Rounded edges off: no shape style here - ThemeOverlay.Shape.Sharp is applied last,
        // after One UI and the corner rounding override, since both set corner tokens too.
        val roundedEdges = ShizukuSettings.isRoundedEdgesEnabled()
        if (roundedEdges && !ShizukuSettings.isExpressiveShapesEnabled()) {
            // shape_style's preference entry is UI-disabled while expressive_shapes is off, so
            // it doesn't apply here regardless of its stored value - flatten to plain Material3.
            theme.applyStyle(R.style.ThemeOverlay_Shapes_Standard, true)
        } else if (roundedEdges) {
            val shapeStyleRes =
                when (ShizukuSettings.getShapeStyle()) {
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
        val cornerOverlayRes =
            when (ShizukuSettings.getCornerRoundingOverride()) {
                "compact" -> R.style.ThemeOverlay_CornerRounding_Compact
                "subtle" -> R.style.ThemeOverlay_CornerRounding_Subtle
                "gentle" -> R.style.ThemeOverlay_CornerRounding_Gentle
                "moderate" -> R.style.ThemeOverlay_CornerRounding_Moderate
                "standard" -> R.style.ThemeOverlay_CornerRounding_Standard
                "large" -> R.style.ThemeOverlay_CornerRounding_Large
                "expanded" -> R.style.ThemeOverlay_CornerRounding_Expanded
                "maximum" -> R.style.ThemeOverlay_CornerRounding_Maximum
                "ultra" -> R.style.ThemeOverlay_CornerRounding_Ultra
                else -> 0
            }
        if (cornerOverlayRes != 0) {
            theme.applyStyle(cornerOverlayRes, true)
        }

        if (!roundedEdges) {
            theme.applyStyle(R.style.ThemeOverlay_Shape_Sharp, true)
        }

        // Outline-only High contrast: system colours always (they have no generated scheme), and
        // every colour source while Full colour schemes is off.
        if (ShizukuSettings.isHighContrastEnabled() &&
            (ThemeHelper.isUsingSystemColor() || !ShizukuSettings.isExperimentalColorSchemesEnabled())
        ) {
            val dark = context.resources.configuration.isNight()
            theme.applyStyle(if (dark) R.style.ThemeOverlay_HighContrast_Dark else R.style.ThemeOverlay_HighContrast, true)
        }

        if (!ShizukuSettings.isExpressiveAnimationsEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_Motion_Standard, true)
        }

        val blackStyle = ThemeHelper.getThemeStyleRes(context)
        theme.applyStyle(blackStyle, true)
        // AMOLED's dim outlines only at standard contrast; with High contrast or a raised level the
        // stronger outlines stay (applied after them, Black's used to silently replace them).
        if (!ShizukuSettings.isHighContrastEnabled() && contrastLevel(context) == 0) {
            when (blackStyle) {
                R.style.ThemeOverlay_Black -> theme.applyStyle(R.style.ThemeOverlay_Black_Outlines, true)
                R.style.ThemeOverlay_Black_Plus -> theme.applyStyle(R.style.ThemeOverlay_Black_Plus_Outlines, true)
            }
        }
    }

    /** Generated schemes (themes_accents.xml): light Standard/Medium/High, then dark. */
    private fun applyGeneratedAccent(
        context: Context,
        theme: Theme,
        accent: String?,
        dark: Boolean,
    ) {
        // Default's standard entry is @null - the base theme already is that scheme.
        val array =
            when (accent) {
                "VIOLET" -> R.array.theme_overlay_accent_violet
                "GREEN" -> R.array.theme_overlay_accent_green
                "CRIMSON" -> R.array.theme_overlay_accent_crimson
                "OCEAN" -> R.array.theme_overlay_accent_ocean
                "AMBER" -> R.array.theme_overlay_accent_amber
                "ROSE" -> R.array.theme_overlay_accent_rose
                "SLATE" -> R.array.theme_overlay_accent_slate
                "CYAN" -> R.array.theme_overlay_accent_cyan
                "NEON" -> R.array.theme_overlay_accent_neon
                else -> R.array.theme_overlay_accent_default
            }
        applyFromArray(context, theme, array, (if (dark) 3 else 0) + contrastLevel(context))
    }

    /** The released hand-made accents (themes_accents_classic.xml): light, dark. */
    private fun applyClassicAccent(
        context: Context,
        theme: Theme,
        accent: String?,
        dark: Boolean,
    ) {
        val array =
            when (accent) {
                "VIOLET" -> R.array.theme_overlay_accent_classic_violet
                "GREEN" -> R.array.theme_overlay_accent_classic_green
                "CRIMSON" -> R.array.theme_overlay_accent_classic_crimson
                "OCEAN" -> R.array.theme_overlay_accent_classic_ocean
                "AMBER" -> R.array.theme_overlay_accent_classic_amber
                "ROSE" -> R.array.theme_overlay_accent_classic_rose
                "SLATE" -> R.array.theme_overlay_accent_classic_slate
                "CYAN" -> R.array.theme_overlay_accent_classic_cyan
                "NEON" -> R.array.theme_overlay_accent_classic_neon
                else -> return
            }
        applyFromArray(context, theme, array, if (dark) 1 else 0)
    }

    private fun applyFromArray(
        context: Context,
        theme: Theme,
        array: Int,
        index: Int,
    ) {
        val styles = context.resources.obtainTypedArray(array)
        val styleRes = styles.getResourceId(index, 0)
        styles.recycle()
        if (styleRes != 0) theme.applyStyle(styleRes, true)
    }

    /**
     * 0 standard, 1 medium, 2 high (always 0 while Full colour schemes is off). The in-app High contrast switch wins; otherwise Android 14+'s
     * contrast setting (-1..1), with MDC's thresholds: 1/3 and up is medium, 2/3 and up is high.
     */
    private fun contrastLevel(context: Context): Int {
        if (!ShizukuSettings.isExperimentalColorSchemesEnabled()) return 0
        if (ShizukuSettings.isHighContrastEnabled()) return 2
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0
        val contrast = context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f
        return when {
            contrast >= 2f / 3f -> 2
            contrast >= 1f / 3f -> 1
            else -> 0
        }
    }
}
