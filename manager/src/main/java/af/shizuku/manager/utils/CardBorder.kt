package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.graphics.Paint
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * The "Card borders" setting, applied the same way everywhere a card is drawn: Home's cards,
 * the grouped settings rows and app list (their item decorations), Service Doctor, and the
 * Compose cards. It used to reach Home's cards only.
 */
object CardBorder {
    private const val WIDTH_DP = 1.5f

    fun isEnabled(): Boolean = ShizukuSettings.isShowHomeCardsOutlineEnabled()

    fun widthPx(context: Context): Float = WIDTH_DP * context.resources.displayMetrics.density

    fun color(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0x33000000)

    /** Adds or removes the border on [card]. */
    fun apply(card: MaterialCardView) {
        if (isEnabled()) {
            card.strokeWidth = (widthPx(card.context) + 0.5f).toInt()
            card.strokeColor = color(card.context)
        } else {
            card.strokeWidth = 0
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
