package af.shizuku.core.ui.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * Reads the ColorScheme from the hosting Activity's own theme instead of having Compose decide
 * dynamic-color/custom-accent/default independently. By the time this runs, AppActivity's
 * onApplyUserThemeResource (see ThemeDelegateImpl) has already applied the correct ThemeOverlay -
 * system dynamic color, a custom accent, the default blue, or black night theme - to the hosting
 * Activity's theme. Reading those resolved attributes keeps Compose screens visually identical to
 * the rest of the (XML-themed) app instead of falling back to Compose's own generic baseline
 * scheme whenever dynamic color isn't available or a custom accent is set.
 */
private fun androidColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    val fallback = if (darkTheme) darkColorScheme() else lightColorScheme()
    fun color(attr: Int, fallbackColor: Color) =
        Color(MaterialColors.getColor(context, attr, fallbackColor.toArgb()))

    // colorPrimary and colorError are declared by appcompat, not material - with non-transitive
    // R classes they only exist in appcompat's R.
    val primary = color(androidx.appcompat.R.attr.colorPrimary, fallback.primary)
    val onPrimary = color(MaterialR.attr.colorOnPrimary, fallback.onPrimary)
    val primaryContainer = color(MaterialR.attr.colorPrimaryContainer, fallback.primaryContainer)
    val onPrimaryContainer = color(MaterialR.attr.colorOnPrimaryContainer, fallback.onPrimaryContainer)
    val secondary = color(MaterialR.attr.colorSecondary, fallback.secondary)
    val onSecondary = color(MaterialR.attr.colorOnSecondary, fallback.onSecondary)
    val secondaryContainer = color(MaterialR.attr.colorSecondaryContainer, fallback.secondaryContainer)
    val onSecondaryContainer = color(MaterialR.attr.colorOnSecondaryContainer, fallback.onSecondaryContainer)
    val tertiary = color(MaterialR.attr.colorTertiary, fallback.tertiary)
    val onTertiary = color(MaterialR.attr.colorOnTertiary, fallback.onTertiary)
    val tertiaryContainer = color(MaterialR.attr.colorTertiaryContainer, fallback.tertiaryContainer)
    val onTertiaryContainer = color(MaterialR.attr.colorOnTertiaryContainer, fallback.onTertiaryContainer)
    val error = color(androidx.appcompat.R.attr.colorError, fallback.error)
    val onError = color(MaterialR.attr.colorOnError, fallback.onError)
    val errorContainer = color(MaterialR.attr.colorErrorContainer, fallback.errorContainer)
    val onErrorContainer = color(MaterialR.attr.colorOnErrorContainer, fallback.onErrorContainer)
    val background = color(android.R.attr.colorBackground, fallback.background)
    val onBackground = color(MaterialR.attr.colorOnBackground, fallback.onBackground)
    val surface = color(MaterialR.attr.colorSurface, fallback.surface)
    val onSurface = color(MaterialR.attr.colorOnSurface, fallback.onSurface)
    val surfaceVariant = color(MaterialR.attr.colorSurfaceVariant, fallback.surfaceVariant)
    val onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant, fallback.onSurfaceVariant)
    val outline = color(MaterialR.attr.colorOutline, fallback.outline)

    // darkColorScheme()/lightColorScheme() are plain functions (not a shared type with a common
    // named-argument call), so a stored function reference can't be invoked with named args here -
    // hence the duplicated call rather than picking one reference to invoke once.
    return if (darkTheme) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            outline = outline,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            outline = outline,
        )
    }
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isBlackNightTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var colorScheme = remember(context, darkTheme) { androidColorScheme(context, darkTheme) }

    // Belt-and-suspenders: ThemeOverlay.Black (applied via onApplyUserThemeResource) already
    // forces colorSurface/android:colorBackground to black, so this should be redundant with the
    // read above - kept in case a future overlay change misses one of the two attributes.
    if (darkTheme && isBlackNightTheme) {
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color.Black
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
