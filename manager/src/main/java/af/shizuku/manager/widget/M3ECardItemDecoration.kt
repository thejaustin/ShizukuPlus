package af.shizuku.manager.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.ktx.themeCornerSizePx

/**
 * Base ItemDecoration for Material 3 Expressive card-style lists.
 * Handles background card drawing and dividers with consistent spacing.
 */
abstract class M3ECardItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
    protected val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Matches every other 28dp/ExtraLarge card in the app (see #333) and follows the Shape
    // Style setting (Modern/Classic/Squircle) instead of a fixed radius.
    protected val cornerRadius = context.themeCornerSizePx(com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge)
    protected val cardMargin = context.resources.getDimension(R.dimen.m3e_spacing_medium)
    protected val density = context.resources.displayMetrics.density

    init {
        cardPaint.color = context.themeColor(R.attr.colorSurfaceContainerHigh)
        dividerPaint.color = context.themeColor(R.attr.colorOutlineVariant)
        dividerPaint.strokeWidth = 1f * density
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0) return

        var currentCardTop = Float.MIN_VALUE
        var lastItemBottom = Float.MIN_VALUE

        for (i in 0 until count) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            if (isHeader(child)) {
                if (currentCardTop != Float.MIN_VALUE) {
                    drawCard(c, parent, currentCardTop, lastItemBottom)
                }
                currentCardTop = child.top.toFloat()
                lastItemBottom = child.bottom.toFloat()

                // Draw a divider under the header if it's expanded (i.e. has visible children)
                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            } else {
                if (currentCardTop == Float.MIN_VALUE) {
                    currentCardTop = child.top.toFloat()
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
            drawCard(c, parent, currentCardTop, lastItemBottom)
        }
    }

    protected open fun isHeader(view: View): Boolean = false

    protected open fun getDividerInset(view: View): Float = 56f * density

    protected open fun getDividerEndInset(view: View): Float = 16f * density

    protected open fun shouldDrawDivider(parent: RecyclerView, index: Int, count: Int): Boolean {
        return false
    }

    protected fun drawCard(c: Canvas, parent: RecyclerView, top: Float, bottom: Float) {
        c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, cornerRadius, cornerRadius, cardPaint)
    }
}
