package af.shizuku.manager.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.ktx.themeCornerSizePx

/**
 * Base ItemDecoration for Material 3 Expressive segmented card-style lists.
 *
 * Each item in a group gets its own per-position background:
 *   - Headers ([isHeader] = true): always a standalone card with full corner radius
 *   - First non-header in a group: large top corners, [innerRadius] (4dp) bottom corners
 *   - Middle non-headers: [innerRadius] on all corners
 *   - Last non-header in a group: [innerRadius] top corners, large bottom corners
 *   - Single non-header between two headers: large corners on all sides
 *
 * Gaps between items (set via [getItemOffsets] in subclasses) show the page background and
 * act as the M3E segment separator — no divider lines are needed.
 */
abstract class M3ECardItemDecoration(protected val context: Context) : RecyclerView.ItemDecoration() {
    protected val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer corner radius — matches the ExtraLarge shape token used by all M3E cards.
    protected val cornerRadius = context.themeCornerSizePx(
        com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge
    )

    // Inner corner radius for connected edges between segments (M3E spec: 4dp)
    protected val innerRadius = 4f * context.resources.displayMetrics.density

    protected open val cardMargin: Float get() = context.resources.getDimension(R.dimen.m3e_spacing_medium)
    protected val density = context.resources.displayMetrics.density

    // Cached per-corner radii arrays — avoids FloatArray allocation on every draw call.
    // [topLeft-x, topLeft-y, topRight-x, topRight-y, bottomRight-x, bottomRight-y,
    //  bottomLeft-x, bottomLeft-y] per Path.addRoundRect convention.
    private val radiiTopOnly: FloatArray
    private val radiiBottomOnly: FloatArray

    // Reused across drawSegment calls to avoid per-frame Path/RectF allocation.
    private val segmentPath = Path()
    private val segmentRect = RectF()

    init {
        cardPaint.color = context.themeColor(R.attr.colorSurfaceContainerHigh)
        headerPaint.color = context.themeColor(R.attr.colorSurfaceContainerHighest)
        dividerPaint.color = context.themeColor(R.attr.colorOutlineVariant)
        dividerPaint.strokeWidth = 1f * density

        radiiTopOnly = floatArrayOf(
            cornerRadius, cornerRadius, cornerRadius, cornerRadius,
            innerRadius, innerRadius, innerRadius, innerRadius
        )
        radiiBottomOnly = floatArrayOf(
            innerRadius, innerRadius, innerRadius, innerRadius,
            cornerRadius, cornerRadius, cornerRadius, cornerRadius
        )
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0) return

        // Collect visible, decorated children in layout order so we can determine
        // each item's position (first / middle / last) within its group.
        val decorated = ArrayList<View>(count)
        for (i in 0 until count) {
            val child = parent.getChildAt(i)
            if (child.visibility == View.VISIBLE && shouldDecorate(child)) {
                decorated.add(child)
            }
        }

        for (i in decorated.indices) {
            val child = decorated[i]
            val prev = if (i > 0) decorated[i - 1] else null
            val next = if (i < decorated.size - 1) decorated[i + 1] else null

            if (isHeader(child)) {
                // Header → standalone full-corner card (never connected to adjacent items)
                drawSegment(c, parent, child, cornerRadius, cornerRadius, headerPaint)
            } else {
                val isFirst = prev == null || isHeader(prev)
                val isLast = next == null || isHeader(next)
                drawSegment(
                    c, parent, child,
                    topRadius = if (isFirst) cornerRadius else innerRadius,
                    bottomRadius = if (isLast) cornerRadius else innerRadius
                )
            }
        }
    }

    private fun drawSegment(
        c: Canvas,
        parent: RecyclerView,
        child: View,
        topRadius: Float,
        bottomRadius: Float,
        paint: Paint = cardPaint
    ) {
        val left = cardMargin
        val right = parent.width - cardMargin
        val top = child.top.toFloat()
        val bottom = child.bottom.toFloat()

        when {
            topRadius == bottomRadius -> {
                // Fast path: uniform radius, no Path needed
                c.drawRoundRect(left, top, right, bottom, topRadius, topRadius, paint)
            }
            topRadius > bottomRadius -> {
                segmentRect.set(left, top, right, bottom)
                segmentPath.rewind()
                segmentPath.addRoundRect(segmentRect, radiiTopOnly, Path.Direction.CW)
                c.drawPath(segmentPath, paint)
            }
            else -> {
                segmentRect.set(left, top, right, bottom)
                segmentPath.rewind()
                segmentPath.addRoundRect(segmentRect, radiiBottomOnly, Path.Direction.CW)
                c.drawPath(segmentPath, paint)
            }
        }
    }

    protected open fun isHeader(view: View): Boolean = false
    protected open fun shouldDecorate(view: View): Boolean = true
    protected open fun getDividerInset(view: View): Float = 56f * density
    protected open fun getDividerEndInset(view: View): Float = 16f * density

    // Kept for subclass compatibility; base class no longer draws dividers.
    protected open fun shouldDrawDivider(parent: RecyclerView, index: Int, count: Int): Boolean = false
}
