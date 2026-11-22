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
}
