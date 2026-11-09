package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import rikka.core.util.ResourceUtils

/**
 * The "Card borders" setting, applied the same way everywhere a card is drawn: Home's cards,
 * the grouped settings rows and app list (their item decorations), Service Doctor, and the
 * Compose cards.
 */
object CardBorder {
    private const val WIDTH_DP = 1.25f

    fun isEnabled(): Boolean = ShizukuSettings.isShowHomeCardsOutlineEnabled()

    fun widthPx(context: Context): Float = WIDTH_DP * context.resources.displayMetrics.density

    fun color(context: Context): Int {
        val isNight = ResourceUtils.isNightMode(context.resources.configuration)
        val isAmoled = ShizukuSettings.isAmoledPlusEnabled() && isNight
        return if (isAmoled) {
            Color.argb(75, 255, 255, 255) // Specular luminous rim in AMOLED+
        } else {
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0x33000000)
        }
    }

    /** Adds or removes the border on [card]. */
    fun apply(card: MaterialCardView) {
        if (isEnabled()) {
            card.strokeWidth = (widthPx(card.context) + 0.5f).toInt()
            card.strokeColor = color(card.context)
        } else {
            card.strokeWidth = 0
        }
    }

    /** Recursively applies border styling to all MaterialCardViews in [view]. */
    fun applyAll(view: android.view.View) {
        if (view is MaterialCardView) {
            apply(view)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyAll(view.getChildAt(i))
            }
        }
    }

    /** A stroke paint for cards drawn on a canvas, or null with borders off. */
    fun paint(context: Context): Paint? =
        if (!isEnabled()) {
            null
        } else {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = widthPx(context)
                color = color(context)
            }
        }
}
