package af.shizuku.manager.home.changelog

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ExpressiveShapes
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Vertical rail with a node near the top, one per timeline row. The rail runs through the middle
 * of the view; the first row starts at its node and the last row ends at it.
 *
 * Expressive touches: an opened release's node springs from a circle into the expressive shape
 * (with a part turn, like the shape switches); the latest release wears a slowly turning,
 * breathing halo in that shape; and the stretch of rail between the installed release and the
 * latest one is drawn in [highlightColor], showing the way to the update.
 */
class TimelineRailView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val density = resources.displayMetrics.density
        private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
        private val highlightPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 3f * density
                strokeCap = Paint.Cap.ROUND
            }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            }
        private val nodePath = Path()
        private val haloPath = Path()

        /** The user's expressive shape, or cookie when they use the plain shape styles. */
        private val shapeKey =
            ShizukuSettings.getIconMorphShape().takeIf { it != ExpressiveShapes.OFF } ?: "cookie"

        private var haloPhase = 0f
        private var pulse: ValueAnimator? = null

        /** 0 = circle node, 1 = the expressive shape (an opened release). */
        private var morph = 0f
        private val morphSpring =
            SpringAnimation(
                this,
                object : FloatPropertyCompat<TimelineRailView>("morph") {
                    override fun getValue(v: TimelineRailView) = v.morph

                    override fun setValue(
                        v: TimelineRailView,
                        value: Float,
                    ) {
                        v.morph = value
                        v.invalidate()
                    }
                },
            ).apply {
                spring =
                    SpringForce().apply {
                        // M3 Expressive default spatial: a little bounce.
                        dampingRatio = 0.8f
                        stiffness = 380f
                    }
                minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            }

        var isFirst = false
        var isLast = false
        var nodeCenterY = 16f * density

        /** Rail above / below the node is part of the way from the installed release to the latest. */
        var highlightAbove = false
        var highlightBelow = false

        fun setColors(
            rail: Int,
            node: Int,
            halo: Int,
            highlight: Int = node,
        ) {
            railPaint.color = rail
            nodePaint.color = node
            ringPaint.color = node
            haloPaint.color = halo
            highlightPaint.color = highlight
            invalidate()
        }

        /** Filled node = a release you can act on / are on; hollow ring = history. */
        var filled = true

        /** Shape for an opened release; springs there unless [animate] is false (binding). */
        fun setOpen(
            open: Boolean,
            animate: Boolean,
        ) {
            val target = if (open) 1f else 0f
            val scale = ShizukuSettings.getAnimationDurationScale()
            if (!animate || scale <= 0f || !ShizukuSettings.isExpressiveAnimationsEnabled()) {
                morphSpring.cancel()
                morph = target
                invalidate()
                return
            }
            morphSpring.spring.stiffness = 380f / (scale * scale)
            morphSpring.animateToFinalPosition(target)
        }

        private var wantPulse = false

        /**
         * Rows are bound before RecyclerView attaches them, so this only records the wish; the
         * halo turns while the view is attached.
         */
        fun setPulsing(enabled: Boolean) {
            wantPulse = enabled
            if (isAttachedToWindow) startOrStopPulse() else invalidate()
        }

        private fun startOrStopPulse() {
            pulse?.cancel()
            pulse = null
            haloPhase = 0f
            if (wantPulse) {
                val scale = ShizukuSettings.getAnimationDurationScale().coerceAtLeast(0.1f)
                pulse =
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = (9000 * scale).toLong()
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        addUpdateListener {
                            haloPhase = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
            }
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (wantPulse) startOrStopPulse()
        }

        override fun onDetachedFromWindow() {
            pulse?.cancel()
            pulse = null
            morphSpring.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val top = if (isFirst) nodeCenterY else 0f
            val bottom = if (isLast) nodeCenterY else height.toFloat()
            if (bottom > top) canvas.drawLine(cx, top, cx, bottom, railPaint)
            if (highlightAbove && !isFirst) canvas.drawLine(cx, 0f, cx, nodeCenterY, highlightPaint)
            if (highlightBelow && !isLast) canvas.drawLine(cx, nodeCenterY, cx, height.toFloat(), highlightPaint)

            if (wantPulse) {
                // Two breaths per turn, so the halo never looks like it's just rotating.
                val breath = kotlin.math.sin(haloPhase * 4f * Math.PI).toFloat()
                drawShape(canvas, haloPath, cx, (22f + 3f * breath) * density, 1f, haloPhase * 360f, haloPaint)
            } else {
                canvas.drawCircle(cx, nodeCenterY, 10f * density, haloPaint)
            }

            val m = morph.coerceIn(0f, 1.1f)
            if (!filled && m <= 0f) {
                canvas.drawCircle(cx, nodeCenterY, 5f * density, ringPaint)
                return
            }
            // 12dp dot -> 18dp shape, turning one point of the shape on the way.
            val size = (12f + 6f * m) * density
            val turn = ExpressiveShapes.spinDegrees(ExpressiveShapes.CIRCLE, shapeKey) * morph.coerceIn(0f, 1f)
            drawShape(canvas, nodePath, cx, size, m.coerceAtMost(1f), turn, nodePaint)
        }

        private fun drawShape(
            canvas: Canvas,
            path: Path,
            cx: Float,
            size: Float,
            progress: Float,
            degrees: Float,
            paint: Paint,
        ) {
            ExpressiveShapes.path(shapeKey, size, size, progress, path)
            canvas.save()
            canvas.translate(cx - size / 2f, nodeCenterY - size / 2f)
            if (degrees != 0f) canvas.rotate(degrees, size / 2f, size / 2f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
