package af.shizuku.manager.widget

import af.shizuku.manager.R
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.ktx.themeCornerSizePx
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Base ItemDecoration for Material 3 Expressive card-style lists.
 * Handles unified background card drawing and dividers with consistent spacing.
 */
abstract class M3ECardItemDecoration(
    protected val context: Context,
) : RecyclerView.ItemDecoration() {
    protected val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // The "Card borders" setting, stroked inside each card's edge (null when off). Read at draw
    // time, so switching it in Settings shows on the next frame.
    private var borderCache: Paint? = null
    protected val borderPaint: Paint?
        get() =
            if (!af.shizuku.manager.utils.CardBorder
                    .isEnabled()
            ) {
                null
            } else {
                borderCache ?: af.shizuku.manager.utils.CardBorder
                    .paint(context)
                    .also { borderCache = it }
            }
    protected val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer corner radius — matches the ExtraLarge shape token used by all M3E cards.
    protected val cornerRadius =
        context.themeCornerSizePx(
            com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge,
        )

    protected open val cardMargin: Float get() = context.resources.getDimension(R.dimen.m3e_spacing_medium)
    protected val density = context.resources.displayMetrics.density

    init {
        cardPaint.color = context.themeColor(R.attr.colorSurfaceContainerHigh)
        dividerPaint.color = context.themeColor(R.attr.colorOutlineVariant)
        dividerPaint.strokeWidth = 1f * density
    }

    override fun onDraw(
        c: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val count = parent.childCount
        if (count == 0) return

        var currentCardTop = Float.MIN_VALUE
        var lastItemBottom = Float.MIN_VALUE
        var currentCardLeft = Float.MIN_VALUE
        var currentCardRight = Float.MIN_VALUE

        for (i in 0 until count) {
            val child = parent.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE || !shouldDecorate(child)) {
                if (currentCardTop != Float.MIN_VALUE) {
                    drawCard(c, parent, currentCardTop, lastItemBottom, currentCardLeft, currentCardRight)
                    currentCardTop = Float.MIN_VALUE
                    lastItemBottom = Float.MIN_VALUE
                    currentCardLeft = Float.MIN_VALUE
                    currentCardRight = Float.MIN_VALUE
                }
                continue
            }

            if (isHeader(child)) {
                if (currentCardTop != Float.MIN_VALUE) {
                    drawCard(c, parent, currentCardTop, lastItemBottom, currentCardLeft, currentCardRight)
                }
                currentCardTop = child.top.toFloat()
                lastItemBottom = child.bottom.toFloat()
                currentCardLeft = child.left.toFloat()
                currentCardRight = child.right.toFloat()

                // Draw a divider under the header if it has visible child preferences following
                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            } else {
                if (currentCardTop == Float.MIN_VALUE) {
                    currentCardTop = child.top.toFloat()
                    currentCardLeft = child.left.toFloat()
                    currentCardRight = child.right.toFloat()
                }
                lastItemBottom = child.bottom.toFloat()

                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            }
        }

        if (currentCardTop != Float.MIN_VALUE) {
            drawCard(c, parent, currentCardTop, lastItemBottom, currentCardLeft, currentCardRight)
        }
    }

    protected open fun isHeader(view: View): Boolean = false

    protected open fun shouldDecorate(view: View): Boolean = true

    protected open fun getDividerInset(view: View): Float = 56f * density

    protected open fun getDividerEndInset(view: View): Float = 16f * density

    protected open fun shouldDrawDivider(
        parent: RecyclerView,
        index: Int,
        count: Int,
    ): Boolean = false

    protected fun drawCard(
        c: Canvas,
        parent: RecyclerView,
        top: Float,
        bottom: Float,
        left: Float = cardMargin,
        right: Float = parent.width - cardMargin,
    ) {
        val actualLeft = if (left != Float.MIN_VALUE) left else cardMargin
        val actualRight = if (right != Float.MIN_VALUE) right else parent.width - cardMargin
        c.drawRoundRect(actualLeft, top, actualRight, bottom, cornerRadius, cornerRadius, cardPaint)
        borderPaint?.let {
            val h = it.strokeWidth / 2f
            c.drawRoundRect(actualLeft + h, top + h, actualRight - h, bottom - h, cornerRadius - h, cornerRadius - h, it)
        }
    }
}
