package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings

/** How one surface is drawn. AUTO follows the global Blur UI and AMOLED+ switches. */
enum class SurfaceStyle { AUTO, MATERIAL, AMOLED, GLASS }

/** Surfaces whose look can be chosen on their own (Experimental > Look & motion). */
enum class Surface(
    val key: String,
) {
    STATUS_BUTTONS("style_status_buttons"),
    NAV_BAR("style_nav_bar"),
    ;

    /** Key of this surface's own corner choice. */
    val cornerKey: String get() = key.replace("style_", "corners_")
}

/** Corner choices per surface. FOLLOW uses the global Corner rounding. */
enum class SurfaceCorners { FOLLOW, SHARP, SOFT, ROUND, PILL }

/**
 * The Corner rounding choices as dp, one table for everything that draws its own corners (the nav
 * bar, icon containers, chips). The theme overlays hold the same numbers for XML and Compose
 * surfaces; keep them in step.
 */
object CornerScale {
    fun dp(value: String?): Float =
        when (value) {
            "sharp" -> 0f
            "compact" -> 8f
            "subtle" -> 14f
            "gentle" -> 20f
            "moderate" -> 24f
            "standard" -> 28f
            "large" -> 36f
            "expanded" -> 40f
            "maximum" -> 44f
            "ultra" -> 52f
            else -> 32f // "default" and "system"
        }

    /** [baseDp] (tuned for the default 32dp rounding) scaled to the chosen Corner rounding; 0 when sharp. */
    fun scaled(baseDp: Float): Float = baseDp * globalDp() / 32f

    /** The global Corner rounding in dp (0 when rounded edges are off). */
    fun globalDp(): Float = if (!ShizukuSettings.isRoundedEdgesEnabled()) 0f else dp(ShizukuSettings.getCornerRoundingOverride())
}

/**
 * One place that answers "which look does this surface get", so a person can mix Material,
 * AMOLED and glass per surface instead of everything following the two global switches.
 */
object SurfaceStyles {
    fun get(surface: Surface): SurfaceStyle {
        val value = runCatching { ShizukuSettings.getPreferences()?.getString(surface.key, null) }.getOrNull()
        return SurfaceStyle.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SurfaceStyle.AUTO
    }

    /** Whether the nav bar blurs what's behind it. */
    fun navBarBlur(): Boolean =
        when (get(Surface.NAV_BAR)) {
            SurfaceStyle.GLASS -> true
            SurfaceStyle.MATERIAL, SurfaceStyle.AMOLED -> false
            SurfaceStyle.AUTO -> ShizukuSettings.isBlurUiEnabled()
        }

    /** Whether the nav bar uses the near-black AMOLED look; it only exists in the dark theme. */
    fun navBarAmoled(isNight: Boolean): Boolean =
        isNight &&
            when (get(Surface.NAV_BAR)) {
                SurfaceStyle.AMOLED -> true
                SurfaceStyle.MATERIAL, SurfaceStyle.GLASS -> false
                SurfaceStyle.AUTO -> ShizukuSettings.isAmoledPlusEnabled()
            }

    fun corners(surface: Surface): SurfaceCorners {
        val value = runCatching { ShizukuSettings.getPreferences()?.getString(surface.cornerKey, null) }.getOrNull()
        return SurfaceCorners.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SurfaceCorners.FOLLOW
    }

    /**
     * Corner radius in dp for [surface], or [globalDp] when it follows. A pill is a large number on purpose: shape drawables clamp corners to half the height.
     */
    fun cornerDp(
        surface: Surface,
        globalDp: Float,
    ): Float =
        when (corners(surface)) {
            SurfaceCorners.FOLLOW -> globalDp
            SurfaceCorners.SHARP -> 0f
            SurfaceCorners.SOFT -> 12f
            SurfaceCorners.ROUND -> 24f
            SurfaceCorners.PILL -> 999f
        }
}
