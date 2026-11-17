package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.useTextAppearance
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.R as MaterialR

/**
 * Compact Home cards (experimental): applied once to each card as the adapter creates it, so the
 * card layouts and their shared styles stay as they are for everyone else.
 *
 * Moves Home toward the M3 card spec (16dp padding, at most 8dp between cards) from our roomier
 * defaults (20dp, 12dp), with smaller titles and summaries. Button labels and touch targets are
 * left alone.
 */
object HomeCompact {
    /** Read once per Home screen; HomeActivity recreates itself when the setting changes. */
    val enabled: Boolean get() = ShizukuSettings.isHomeCompactCardsEnabled()

    const val CARD_SPACING_DP = 8f
    private const val CONTENT_PADDING_DP = 16f
    private const val ICON_DP = 40
    private const val ICON_PADDING_DP = 10

    fun apply(card: View) {
        val density = card.resources.displayMetrics.density

        fun dp(v: Float) = (v * density).toInt()

        card.findViewById<View>(R.id.card_content)?.let {
            val p = dp(CONTENT_PADDING_DP)
            it.setPadding(p, p, p, p)
        }
        walk(card) { v ->
            when {
                v is TextView && v.id == android.R.id.text1 -> v.resize(MaterialR.attr.textAppearanceTitleMedium)
                v is TextView && (v.id == android.R.id.text2 || v.id == android.R.id.summary) ->
                    v.resize(MaterialR.attr.textAppearanceBodyMedium)
                v is ImageView && v.id == android.R.id.icon -> {
                    v.layoutParams =
                        v.layoutParams.apply {
                            width = dp(ICON_DP.toFloat())
                            height = dp(ICON_DP.toFloat())
                        }
                    val p = dp(ICON_PADDING_DP.toFloat())
                    v.setPadding(p, p, p, p)
                }
                // Wrapped chips (the status card's Activity log / Diagnostics) sat a row apart.
                v is ChipGroup -> v.chipSpacingVertical = 0
            }
        }
    }

    /** A text appearance can carry a colour too; the card's own colours stay. */
    private fun TextView.resize(attr: Int) {
        val colors = textColors
        useTextAppearance(attr)
        setTextColor(colors)
    }

    private fun walk(
        v: View,
        action: (View) -> Unit,
    ) {
        action(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), action)
    }
}
