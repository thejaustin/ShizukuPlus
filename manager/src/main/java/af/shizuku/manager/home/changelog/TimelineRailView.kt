package af.shizuku.manager.home.changelog

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ExpressiveShapes
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
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
 * latest one is drawn in [highlightColor], showing the way to the update. That stretch is a
 * wavy line flowing up toward the newest release (continuous across rows, since the wave is
 * phased on screen position), and the nodes along it are the expressive shape rather than dots.
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
                strokeWidth = 2.5f * density
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
        private var flowPeriodMs = 5000L
        private var flow: ValueAnimator? = null
        private var wantFlow = false
        private val wavePath = Path()
        private val loc = IntArray(2)
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

        /** A release newer than the one the user was on: its node is the shape, not a dot. */
        var isNewNode = false

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

        /** Animate the lit stretch of rail; like [setPulsing] it only runs while attached. */
        fun setFlowing(enabled: Boolean) {
            wantFlow = enabled
            if (isAttachedToWindow) startOrStopFlow() else invalidate()
        }

        private fun startOrStopFlow() {
            flow?.cancel()
            flow = null
            if (wantFlow) {
                val scale = ShizukuSettings.getAnimationDurationScale().coerceAtLeast(0.1f)
                // Only a frame clock: the phase itself comes from the shared uptime in drawWave, so
                // every row (each with its own animator) draws the same wave and the rail joins up.
                flowPeriodMs = (5000 * scale).toLong().coerceAtLeast(1L)
                flow =
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = flowPeriodMs
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        addUpdateListener { invalidate() }
                        start()
                    }
            }
            invalidate()
        }

        /** A sine wave along the rail from [y0] to [y1], phased on window position so rows join up. */
        private fun drawWave(
            canvas: Canvas,
            cx: Float,
            y0: Float,
            y1: Float,
        ) {
            val amp = 1.5f * density
            val wave = 30f * density
            getLocationInWindow(loc)
            val flowT = if (wantFlow) (SystemClock.uptimeMillis() % flowPeriodMs) / flowPeriodMs.toFloat() else 0f
            val step = 2f * density
            wavePath.rewind()
            var y = y0
            while (true) {
                val phase = (loc[1] + y) / wave + flowT
                // Settle to a straight line at the node so the wave joins it cleanly.
                val settle = (kotlin.math.abs(y - nodeCenterY) / (16f * density)).coerceAtMost(1f)
                val x = cx + amp * settle * kotlin.math.sin(phase * 2f * Math.PI).toFloat()
                if (y == y0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                if (y >= y1) break
                y = (y + step).coerceAtMost(y1)
            }
            highlightPaint.style = Paint.Style.STROKE
            canvas.drawPath(wavePath, highlightPaint)
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
            if (wantFlow) startOrStopFlow()
        }

        override fun onDetachedFromWindow() {
            pulse?.cancel()
            pulse = null
            flow?.cancel()
            flow = null
            morphSpring.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val top = if (isFirst) nodeCenterY else 0f
            val bottom = if (isLast) nodeCenterY else height.toFloat()
            if (bottom > top) canvas.drawLine(cx, top, cx, bottom, railPaint)
            if (highlightAbove && !isFirst) drawWave(canvas, cx, 0f, nodeCenterY)
            if (highlightBelow && !isLast) drawWave(canvas, cx, nodeCenterY, height.toFloat())

            if (wantPulse) {
                // Two breaths per turn, so the halo never looks like it's just rotating.
                val breath = kotlin.math.sin(haloPhase * 4f * Math.PI).toFloat()
                drawShape(canvas, haloPath, cx, (22f + 3f * breath) * density, 1f, haloPhase * 360f, haloPaint)
            } else {
                canvas.drawCircle(cx, nodeCenterY, 10f * density, haloPaint)
            }

            // New releases rest as a mostly-formed shape; opening one finishes the morph.
            val m = maxOf(morph, if (isNewNode) 0.7f else 0f).coerceIn(0f, 1.1f)
            if (!filled && m <= 0f) {
                canvas.drawCircle(cx, nodeCenterY, 5f * density, ringPaint)
                return
            }
            // 12dp dot -> 18dp shape, turning one point of the shape on the way.
            val size = (12f + 6f * m) * density
            val turn = ExpressiveShapes.spinDegrees(ExpressiveShapes.CIRCLE, shapeKey) * m.coerceIn(0f, 1f)
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
