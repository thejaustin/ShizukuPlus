package af.shizuku.manager.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.preference.PreferenceGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.utilities.Hct
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.themeColor
import kotlin.math.abs
import kotlin.math.min

object IconStyleHelper {

    enum class Style(val key: String) {
        STANDARD("standard"),
        OUTLINED("outlined"),
        TWO_TONE("twotone");

        companion object {
            fun fromKey(key: String?): Style = values().firstOrNull { it.key == key } ?: STANDARD
        }
    }

    enum class ColorMode(val key: String) {
        NONE("none"),
        UNIFORM("uniform"),
        PER_ICON("per_icon");

        companion object {
            fun fromKey(key: String?): ColorMode = values().firstOrNull { it.key == key } ?: UNIFORM
        }
    }

    // Evenly-spaced hue slots a per-icon color is picked from, keeping tone/chroma fixed to the
    // theme's own primary so varied hues still read as "this app's palette" rather than random.
    private const val HUE_SLOTS = 12

    fun current(): Style = Style.fromKey(ShizukuSettings.getIconStyle())
    fun currentColorMode(): ColorMode = ColorMode.fromKey(ShizukuSettings.getIconColorMode())

    fun applyToTree(
        context: Context,
        group: PreferenceGroup,
        style: Style = current(),
        colorMode: ColorMode = currentColorMode()
    ) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                applyToTree(context, pref, style, colorMode)
            }
            pref.icon?.let { original ->
                pref.icon = stylize(context, original, style, colorMode, seedKey = pref.key)
            }
        }
    }

    fun stylize(
        context: Context,
        original: Drawable,
        style: Style = current(),
        colorMode: ColorMode = currentColorMode(),
        seedKey: String? = null
    ): Drawable {
        val mutable = original.mutate()
        return when (style) {
            Style.STANDARD -> tinted(mutable, resolveColor(context, R.attr.colorPrimary))
            Style.OUTLINED -> tinted(mutable, resolveColor(context, R.attr.colorOnSurfaceVariant))
            Style.TWO_TONE -> {
                val (bgColor, fgColor) = twoToneColors(context, colorMode, seedKey)
                val bg = pillBackground(context, bgColor)
                val fg = tinted(mutable, fgColor)
                val padding = (4 * context.resources.displayMetrics.density).toInt()
                LayerDrawable(arrayOf(bg, InsetDrawable(fg, padding)))
            }
        }
    }

    // The 12dp CardIcon/CardIcon.Droplet style used, so a plain Standard/Outlined vector still
    // renders at its native ~24dp size centered in the 48dp Home card icon slot.
    private const val CARD_ICON_PADDING_DP = 12

    /**
     * Applies the current icon style/color-mode/shape to a Home-screen card's 48dp icon slot.
     * Those cards previously always used a fixed two-tone "droplet" pill via the CardIcon.Droplet
     * XML style, ignoring the Personalization icon settings entirely. This clears that hardcoded
     * background/tint so [stylize] fully controls the result, and swaps the ImageView's own
     * padding per style: Two-Tone's pill needs to fill the whole slot (stylize's own InsetDrawable
     * already provides the icon-to-pill breathing room), while Standard/Outlined need the
     * original 12dp padding so the bare vector isn't stretched to fill the whole 48dp box.
     *
     * [original] must be the icon's untouched, freshly-inflated drawable - callers should capture
     * it once (e.g. in a ViewHolder's init block) rather than reading it back from the ImageView,
     * since after the first call the ImageView holds the already-styled result.
     */
    fun applyToCardIcon(
        imageView: ImageView,
        original: Drawable,
        seedKey: String,
        style: Style = current(),
        colorMode: ColorMode = currentColorMode()
    ) {
        val context = imageView.context
        imageView.background = null
        imageView.imageTintList = null
        val padding = if (style == Style.TWO_TONE) 0 else (CARD_ICON_PADDING_DP * context.resources.displayMetrics.density).toInt()
        imageView.setPadding(padding, padding, padding, padding)
        imageView.setImageDrawable(stylize(context, original, style, colorMode, seedKey))
    }

    /**
     * Resolves the Two-Tone background/foreground colors for the given mode.
     * - NONE: neutral surface tones, no hue - a plain monochrome pill.
     * - UNIFORM: the theme's own primary container pair, same for every icon (previous behavior).
     * - PER_ICON: a distinct hue per icon, derived from the theme's primary HCT tone/chroma so
     *   the varied palette still reads as "this app's colors" rather than arbitrary hues, then
     *   harmonized toward primary and run through Material's color-role derivation for correct
     *   contrast in both light and dark themes. Mirrors Material Color Utilities' recommended
     *   pattern (Hct hue-rotation + MaterialColors.harmonize/getColorRoles) rather than a
     *   hand-picked fixed palette - see IconStyleHelper's audit notes.
     */
    private fun twoToneColors(context: Context, colorMode: ColorMode, seedKey: String?): Pair<Int, Int> {
        return when (colorMode) {
            ColorMode.NONE ->
                resolveColor(context, R.attr.colorSurfaceVariant) to resolveColor(context, R.attr.colorOnSurfaceVariant)
            ColorMode.UNIFORM ->
                resolveColor(context, R.attr.colorPrimaryContainer) to resolveColor(context, R.attr.colorOnPrimaryContainer)
            ColorMode.PER_ICON -> {
                val primary = resolveColor(context, R.attr.colorPrimary)
                val slot = if (seedKey.isNullOrEmpty()) 0 else abs(seedKey.hashCode()) % HUE_SLOTS
                val baseHct = Hct.fromInt(primary)
                val hue = (baseHct.hue + slot * (360.0 / HUE_SLOTS)) % 360.0
                val seedColor = Hct.from(hue, baseHct.chroma, baseHct.tone).toInt()
                val harmonized = MaterialColors.harmonize(seedColor, primary)
                val roles = MaterialColors.getColorRoles(context, harmonized)
                roles.accentContainer to roles.onAccentContainer
            }
        }
    }

    /**
     * The Two-Tone pill's background shape, following the same "app personality" dial as
     * container corners (see ThemeDelegateImpl's shape_style overlays) instead of always being
     * a plain circle - "zen" keeps an asymmetric leaf/droplet look, others use progressively
     * more/less rounded rectangles. Falls back to a plain circle when expressive shapes are off.
     *
     * Public so status-driven icons (e.g. ServerStatusViewHolder, StartStockShizukuViewHolder)
     * that must keep their own semantic ok/error color logic instead of the user's color-mode
     * setting can still share the same shape, rather than reaching for the static
     * shape_droplet_background drawable directly and drifting out of sync with shape_style.
     */
    fun pillBackground(context: Context, color: Int): Drawable {
        if (!ShizukuSettings.isExpressiveShapesEnabled()) {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        return when (ShizukuSettings.getShapeStyle()) {
            "zen" -> leafDrawable(context, color)
            "classic" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * context.resources.displayMetrics.density
                setColor(color)
            }
            "squircle" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * context.resources.displayMetrics.density
                setColor(color)
            }
            "cut" -> cutDrawable(context, color)
            else -> GradientDrawable().apply { // "modern"
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * context.resources.displayMetrics.density
                setColor(color)
            }
        }
    }

    /**
     * The Cut style's icon pill: an octagon (all four rectangle corners cut at 45°), matching the
     * shape_style overlay's CUT corner family (see themes_overlay.xml) instead of ROUNDED - keeps
     * the same "distinct silhouette per style" treatment leafDrawable() gives "zen" rather than
     * falling back to a rounded rectangle indistinguishable from the other styles.
     * GradientDrawable has no native cut-corner support (only rounded), so this draws the
     * octagon path directly via a small custom Drawable instead.
     */
    private fun cutDrawable(context: Context, color: Int): Drawable {
        val cutFraction = 0.28f // corner cut as a fraction of the shorter side
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            this.color = color
        }
        return object : Drawable() {
            private val path = android.graphics.Path()

            override fun onBoundsChange(bounds: android.graphics.Rect) {
                super.onBoundsChange(bounds)
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cut = min(w, h) * cutFraction
                path.reset()
                path.moveTo(cut, 0f)
                path.lineTo(w - cut, 0f)
                path.lineTo(w, cut)
                path.lineTo(w, h - cut)
                path.lineTo(w - cut, h)
                path.lineTo(cut, h)
                path.lineTo(0f, h - cut)
                path.lineTo(0f, cut)
                path.close()
            }

            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /**
     * An asymmetric "leaf/droplet" pill: three tight corners, one wide corner.
     * cornerRadii values are in pixels (unlike cornerRadius's dp-friendly single value used
     * elsewhere in this file's other branches), hence the explicit density multiplication here.
     */
    private fun leafDrawable(context: Context, color: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val tight = 4f * density
        val wide = 20f * density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                tight, tight, // top-left
                wide, wide,   // top-right
                tight, tight, // bottom-right
                tight, tight  // bottom-left
            )
            setColor(color)
        }
    }

    /**
     * Applies a semantic status pill (server ok/error, ADB-permission-limited warning,
     * incompatible-server warning) — these always render as a Two-Tone-style pill regardless of
     * the user's icon Style setting, since muting an error/warning to Standard/Outlined would lose
     * the color signal. Still routes the shape through [pillBackground] so it follows shape_style,
     * and clears the ImageView's static 12dp `CardIcon.Droplet` XML padding to 0 - without this,
     * these pills render visibly smaller/off-center than every Style-driven card's Two-Tone pill,
     * which gets 0 padding via [applyToCardIcon].
     */
    fun applyToStatusCardIcon(imageView: ImageView, pillColor: Int, tintColor: Int) {
        val context = imageView.context
        imageView.background = pillBackground(context, pillColor)
        imageView.imageTintList = ColorStateList.valueOf(tintColor)
        imageView.setPadding(0, 0, 0, 0)
    }

    private fun tinted(drawable: Drawable, color: Int): Drawable {
        drawable.setTintList(ColorStateList.valueOf(color))
        return drawable
    }

    private fun resolveColor(context: Context, attr: Int): Int = context.themeColor(attr)
}
