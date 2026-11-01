package af.shizuku.manager.utils

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * M3 Expressive shape set for icon containers, built on androidx.graphics.shapes so any two shapes
 * can be morphed into each other. Uses Material's own [MaterialShapes] where one exists, and fits
 * every shape so its area centroid sits at the middle of the square, where the icon is drawn.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ExpressiveShapes {
    const val OFF = "off"

    /** Picker order; [OFF] means "use the regular Shape style". */
    val KEYS =
        listOf(OFF, "cookie", "clover", "sunny", "gem", "flower", "star", "burst") +
            CLASSIC.map { it + CLASSIC_SUFFIX }

    /**
     * The hand-built shapes from before the switch to [MaterialShapes], kept as their own options
     * ("cookie_classic"...) for whoever preferred them. Star never changed, so it has none.
     */
    private val CLASSIC get() = listOf("cookie", "clover", "sunny", "gem", "flower", "burst")
    const val CLASSIC_SUFFIX = "_classic"

    private val cache = HashMap<String, RoundedPolygon>()

    // Building a Morph matches up the two outlines, too slow to redo on every animation frame.
    private val morphs = HashMap<String, Morph>()

    val circle: RoundedPolygon by lazy { MaterialShapes.Circle.centeredInUnitSquare() }

    /**
     * Area centroid of the outline: where the eye puts the middle of the shape. The polygon's own
     * center is the average of its corners and the bounding box ignores where the area actually
     * is, so on lopsided shapes either one left the icon visibly off-center.
     */
    internal fun RoundedPolygon.areaCentroid(): Pair<Float, Float> {
        var area = 0.0
        var cx = 0.0
        var cy = 0.0
        var px = cubics.first().anchor0X.toDouble()
        var py = cubics.first().anchor0Y.toDouble()
        for (c in cubics) {
            for (i in 1..CENTROID_STEPS) {
                val t = i.toDouble() / CENTROID_STEPS
                val u = 1 - t
                val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
                val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
                val cross = px * y - x * py
                area += cross
                cx += (px + x) * cross
                cy += (py + y) * cross
                px = x
                py = y
            }
        }
        if (kotlin.math.abs(area) < 1e-9) return centerX to centerY
        return (cx / (3 * area)).toFloat() to (cy / (3 * area)).toFloat()
    }

    /** Fit into (0,0)-(1,1) with the area centroid at (0.5, 0.5), where the icon is drawn. */
    private fun RoundedPolygon.centeredInUnitSquare(): RoundedPolygon {
        val b = calculateBounds(approximate = false)
        val (cx, cy) = areaCentroid()
        // Twice the largest center-to-edge distance: the center stays put and the curve fits.
        val size = 2f * maxOf(cx - b[0], b[2] - cx, cy - b[1], b[3] - cy)
        if (size <= 0f) return normalized()
        return transformed { x, y -> TransformResult((x - cx) / size + 0.5f, (y - cy) / size + 0.5f) }
    }

    private const val CENTROID_STEPS = 16

    private val iconFractions = HashMap<String, Float>()

    /**
     * How much of the container's width the icon should take for [key]. Shapes that dip in toward
     * the middle (flower, star) get a smaller icon so it stays inside the shape; full shapes get
     * up to [max]. Scales with the inradius, the closest the outline comes to the center.
     */
    fun iconFraction(
        key: String,
        max: Float,
    ): Float {
        val inradius =
            iconFractions.getOrPut(key) {
                var m = 0.5f
                for (c in polygon(key).cubics) {
                    for (i in 0..CENTROID_STEPS) {
                        val t = i.toFloat() / CENTROID_STEPS
                        val u = 1 - t
                        val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
                        val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
                        m = minOf(m, kotlin.math.hypot(x - 0.5f, y - 0.5f))
                    }
                }
                m
            }
        // Glyphs leave their corners empty, so the icon box may reach a little past the inradius.
        return (inradius * 1.3f).coerceIn(0.42f, max)
    }

    fun polygon(key: String): RoundedPolygon =
        cache.getOrPut(key) {
            if (key.startsWith("rect:")) {
                val r = key.removePrefix("rect:").toFloatOrNull() ?: 0.3f
                return@getOrPut RoundedPolygon.rectangle(width = 2f, height = 2f, rounding = CornerRounding(r)).centeredInUnitSquare()
            }
            when (key) {
                // Classic set, exactly as before. Only the cookie turns a quarter like the star:
                // its 9 points left it lopsided; even point counts are already symmetric.
                "cookie_classic" ->
                    RoundedPolygon
                        .star(9, innerRadius = 0.82f, rounding = CornerRounding(0.3f, smoothing = 0.5f))
                        .transformed { x, y -> TransformResult(y, -x) }
                "clover_classic" -> RoundedPolygon.star(4, innerRadius = 0.45f, rounding = CornerRounding(0.42f, smoothing = 0.3f))
                "sunny_classic" -> RoundedPolygon.star(8, innerRadius = 0.86f, rounding = CornerRounding(0.12f))
                "gem_classic" -> RoundedPolygon(6, rounding = CornerRounding(0.3f, smoothing = 0.6f))
                "flower_classic" -> RoundedPolygon.star(6, innerRadius = 0.62f, rounding = CornerRounding(0.36f, smoothing = 0.4f))
                "burst_classic" -> RoundedPolygon.star(12, innerRadius = 0.84f, rounding = CornerRounding(0.12f, smoothing = 0.2f))
                "cookie" -> MaterialShapes.Cookie9Sided
                "clover" -> MaterialShapes.Clover4Leaf
                "sunny" -> MaterialShapes.Sunny
                "gem" -> MaterialShapes.Gem
                "flower" -> MaterialShapes.Flower
                // Burst's sharp spikes left most of an icon outside the shape; SoftBurst is the
                // rounded variant made for holding content.
                "burst" -> MaterialShapes.SoftBurst
                // No Material star: rotated so a point faces up, which keeps it symmetric left to
                // right (star() starts its first point facing right, which pushed it off-center).
                "star" ->
                    RoundedPolygon
                        .star(5, innerRadius = 0.52f, rounding = CornerRounding(0.18f, smoothing = 0.2f))
                        .transformed { x, y -> TransformResult(y, -x) }
                else -> MaterialShapes.Circle
            }.centeredInUnitSquare()
        }

    /** The plain circle, the default off shape. */
    const val CIRCLE = "circle"

    /**
     * Key of a rounded rectangle whose corner radius is [fraction] of half its side. The regular
     * Shape styles' containers use it, so they morph like the Expressive shapes do.
     */
    fun rectKey(fraction: Float): String = "rect:" + String.format(java.util.Locale.US, "%.2f", fraction.coerceIn(0f, 1f))

    /** Path for [key], or [from] -> [key] at [progress], scaled into [width] x [height]. */
    fun path(
        key: String,
        width: Float,
        height: Float,
        progress: Float = 1f,
        into: Path = Path(),
        from: String = CIRCLE,
    ): Path {
        into.rewind()
        when {
            progress >= 1f -> polygon(key).toPath(into)
            progress <= 0f -> polygon(from).toPath(into)
            else -> morphs.getOrPut("$from>$key") { Morph(polygon(from), polygon(key)) }.toPath(progress, into)
        }
        into.transform(Matrix().apply { setScale(width, height) })
        return into
    }

    // Outline radius around the center, by angle (0 = up, clockwise). 2520 divides evenly by every
    // point count from 2 to 10 and 12, which the symmetry check relies on.
    internal const val POLAR_BINS = 2520
    private val polar = HashMap<String, FloatArray>()
    private val symmetries = HashMap<String, Int>()

    internal fun polarRadii(key: String): FloatArray =
        polar.getOrPut(key) {
            val r = FloatArray(POLAR_BINS)
            for (c in polygon(key).cubics) {
                for (i in 0..POLAR_STEPS) {
                    val t = i.toFloat() / POLAR_STEPS
                    val u = 1 - t
                    val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
                    val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
                    val dx = x - 0.5f
                    val dy = y - 0.5f
                    var a = kotlin.math.atan2(dx, -dy)
                    if (a < 0) a += TAU
                    val bin = ((a / TAU) * POLAR_BINS).toInt().coerceIn(0, POLAR_BINS - 1)
                    r[bin] = maxOf(r[bin], kotlin.math.hypot(dx, dy))
                }
            }
            // Bins no sample landed in take their neighbour's radius.
            repeat(2) { for (i in r.indices) if (r[i] == 0f) r[i] = r[(i + POLAR_BINS - 1) % POLAR_BINS] }
            r
        }

    private const val POLAR_STEPS = 400
    private const val TAU = (2 * Math.PI).toFloat()

    /**
     * Rotational symmetry of [key]: how many identical points it has (a rounded square is 4,
     * the 9-scallop cookie 9), 1 for none, 0 for a circle. Measured from the outline, since
     * Material's shapes don't all have the point count their names suggest.
     */
    fun symmetry(key: String): Int =
        symmetries.getOrPut(key) {
            val r = polarRadii(key)
            if (r.max() - r.min() < 0.004f) return@getOrPut 0
            for (n in 12 downTo 2) {
                if (POLAR_BINS % n != 0) continue
                val shift = POLAR_BINS / n
                var same = true
                var i = 0
                while (i < POLAR_BINS) {
                    if (kotlin.math.abs(r[i] - r[(i + shift) % POLAR_BINS]) > 0.008f) {
                        same = false
                        break
                    }
                    i += 3
                }
                if (same) return@getOrPut n
            }
            1
        }

    /** Spin's turn for [from] -> [to]: one point of the destination, so it lands looking unrotated. */
    fun spinDegrees(
        from: String,
        to: String,
    ): Float {
        val n = symmetry(to).takeIf { it > 0 } ?: symmetry(from).takeIf { it > 0 } ?: 4
        return 360f / n
    }

    /**
     * Sweep and Liquid move each part of the outline on its own timing, which a [Morph] can't, so
     * mid-animation they interpolate the two outlines' polar radii. At rest the exact path is drawn.
     */
    fun polarPath(
        from: String,
        to: String,
        width: Float,
        height: Float,
        progress: Float,
        forward: Boolean,
        mode: MorphMode,
        into: Path = Path(),
    ): Path {
        into.rewind()
        val a = polarRadii(from)
        val b = polarRadii(to)
        val p = progress.coerceIn(0f, 1f)
        var k = 0
        while (k < POLAR_BINS) {
            val frac = k.toFloat() / POLAR_BINS
            val q = polarProgress(frac, p, forward, mode)
            val r = a[k] + (b[k] - a[k]) * q
            val angle = frac * TAU
            val x = (0.5f + kotlin.math.sin(angle) * r) * width
            val y = (0.5f - kotlin.math.cos(angle) * r) * height
            if (k == 0) into.moveTo(x, y) else into.lineTo(x, y)
            k += POLAR_PATH_STEP
        }
        into.close()
        return into
    }

    /** How far the part of the outline at [frac] of the way round is, at overall progress [p]. */
    internal fun polarProgress(
        frac: Float,
        p: Float,
        forward: Boolean,
        mode: MorphMode,
    ): Float =
        if (mode == MorphMode.LIQUID) {
            // Uneven, zero at both ends: parts of the outline lead and lag like a drop.
            val wobble = kotlin.math.sin(Math.PI * p).toFloat()
            (p + 0.22f * wobble * kotlin.math.sin(3 * TAU * frac + 1.3f)).coerceIn(0f, 1f)
        } else {
            // Sweep: the change travels clockwise from the top.
            val delay = frac * 0.7f
            val prog = if (forward) p else 1f - p
            val local = ((prog - delay) / 0.3f).coerceIn(0f, 1f)
            if (forward) local else 1f - local
        }

    private const val POLAR_PATH_STEP = 10
}

/** How an icon container moves between its off and on shapes. */
enum class MorphMode(
    val key: String,
) {
    /** The outline flows straight from one shape to the other. */
    MORPH("morph"),

    /** Turns one point of the destination shape while morphing. */
    SPIN("spin"),

    /** Swells a little midway and settles. */
    POP("pop"),

    /** The change travels clockwise from the top. */
    SWEEP("sweep"),

    /** Parts of the outline lead and lag, like a drop of water. */
    LIQUID("liquid"),
    ;

    companion object {
        fun of(key: String?): MorphMode = entries.firstOrNull { it.key == key } ?: SPIN
    }
}

/**
 * Icon-container background drawn from an [ExpressiveShapes] polygon. [progress] morphs from
 * [offKey] (0) to [shapeKey] (1), the way switch rows and Home's status icon show on and off,
 * moving as [mode] says. With [cornerPx] set, [shapeKey] is a rounded rectangle with that
 * corner radius, recomputed for the drawable's size.
 */
class MorphShapeDrawable(
    private var color: Int,
    shapeKey: String,
    val offKey: String = ExpressiveShapes.CIRCLE,
    private val cornerPx: Float = 0f,
) : Drawable() {
    var shapeKey: String = shapeKey
        private set

    var mode: MorphMode = MorphMode.MORPH

    /** 1 = filled, 0 = an outline only (the "icon is the switch" design's off state). */
    var fillFraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    var strokeColor: Int = 0
    var strokeWidthPx: Float = 0f

    /** Draws smaller while off, like an M3 switch thumb. */
    var shrinkWhenOff: Boolean = false

    /** Size reported to layouts that size by drawable (a switch thumb); -1 = none. */
    var intrinsicSizePx: Int = -1

    /** Springs between off and on itself when its checked state changes (a switch thumb). */
    var followsCheckedState: Boolean = false
    private var lastChecked: Boolean? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private var baseAlpha = 255
    private var tint: android.content.res.ColorStateList? = null

    // Direction of the running change, for Sweep.
    internal var forward = true

    /** Reused by [springTo] so a rapid re-toggle retargets the running spring instead of racing it. */
    internal var morphSpring: androidx.dynamicanimation.animation.SpringAnimation? = null

    var progress: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1.15f) // a touch of overshoot for spring animations
            if (field != v) {
                field = v
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val p = progress.coerceAtMost(1f)
        val moving = p > 0f && p < 1f
        if (moving && (mode == MorphMode.SWEEP || mode == MorphMode.LIQUID)) {
            ExpressiveShapes.polarPath(offKey, shapeKey, w, h, p, forward, mode, path)
        } else {
            ExpressiveShapes.path(shapeKey, w, h, p, path, offKey)
        }
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        var scale = if (progress > 1f) progress else 1f // overshoot reads as a quick "pop"
        if (mode == MorphMode.POP && moving) scale *= 1f + 0.14f * kotlin.math.sin(Math.PI * p).toFloat()
        if (shrinkWhenOff) scale *= 0.67f + 0.33f * p.coerceAtLeast(0f)
        if (scale != 1f) canvas.scale(scale, scale, w / 2f, h / 2f)
        if (mode == MorphMode.SPIN && moving) canvas.rotate(ExpressiveShapes.spinDegrees(offKey, shapeKey) * p, w / 2f, h / 2f)
        if (fillFraction > 0f) {
            paint.alpha = (baseAlpha * fillFraction).toInt()
            canvas.drawPath(path, paint)
        }
        if (fillFraction < 1f && strokeWidthPx > 0f) {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidthPx
            strokePaint.alpha = (baseAlpha * (1f - fillFraction) * android.graphics.Color.alpha(strokeColor) / 255f).toInt()
            // Inset by half the stroke so it stays inside the container.
            val sx = (w - strokeWidthPx) / w
            val sy = (h - strokeWidthPx) / h
            canvas.scale(sx, sy, w / 2f, h / 2f)
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }

    override fun onBoundsChange(bounds: Rect) {
        if (cornerPx > 0f && !bounds.isEmpty) {
            shapeKey = ExpressiveShapes.rectKey(cornerPx / (minOf(bounds.width(), bounds.height()) / 2f))
        }
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = intrinsicSizePx

    override fun getIntrinsicHeight(): Int = intrinsicSizePx

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTintList(tint: android.content.res.ColorStateList?) {
        this.tint = tint
        applyTint(state)
    }

    override fun isStateful(): Boolean = tint?.isStateful == true || followsCheckedState

    override fun onStateChange(state: IntArray): Boolean {
        val changed = applyTint(state)
        if (followsCheckedState) {
            val checked = state.contains(android.R.attr.state_checked)
            val previous = lastChecked
            lastChecked = checked
            if (previous == null) {
                progress = if (checked) 1f else 0f
            } else if (previous != checked) {
                springTo(if (checked) 1f else 0f)
            }
        }
        return changed
    }

    private fun applyTint(state: IntArray): Boolean {
        val t = tint ?: return false
        val c = t.getColorForState(state, color)
        if (c == paint.color) return false
        paint.color = c
        invalidateSelf()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // A tinted ImageView mutates its LayerDrawable, which copies each layer through its constant
    // state. Without one the copy shares this instance with the original and logs a warning.
    override fun getConstantState(): ConstantState =
        object : ConstantState() {
            override fun newDrawable(): Drawable =
                MorphShapeDrawable(color, shapeKey, offKey, cornerPx).also {
                    it.progress = progress
                    it.mode = mode
                    it.fillFraction = fillFraction
                    it.strokeColor = strokeColor
                    it.strokeWidthPx = strokeWidthPx
                    it.shrinkWhenOff = shrinkWhenOff
                    it.intrinsicSizePx = intrinsicSizePx
                    it.followsCheckedState = followsCheckedState
                }

            override fun getChangingConfigurations(): Int = 0
        }
}

/**
 * Springs [MorphShapeDrawable.progress] to [target] (0 = circle, 1 = shape) with the bouncy M3E
 * feel; jumps straight there when animations are off.
 */
fun MorphShapeDrawable.springTo(target: Float) {
    forward = target >= progress
    if (af.shizuku.manager.ShizukuSettings
            .getAnimationDurationScale() <= 0f
    ) {
        morphSpring?.cancel()
        progress = target
        return
    }
    val anim =
        morphSpring ?: androidx.dynamicanimation.animation
            .SpringAnimation(
                this,
                object : androidx.dynamicanimation.animation.FloatPropertyCompat<MorphShapeDrawable>("morph") {
                    override fun getValue(d: MorphShapeDrawable) = d.progress

                    override fun setValue(
                        d: MorphShapeDrawable,
                        value: Float,
                    ) {
                        d.progress = value
                    }
                },
            ).apply {
                spring =
                    androidx.dynamicanimation.animation.SpringForce().apply {
                        dampingRatio = androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                        stiffness = androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW
                    }
                // The default threshold is one pixel, which on a 0..1 value ends the spring
                // almost as soon as it starts.
                minimumVisibleChange = androidx.dynamicanimation.animation.DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            }.also { morphSpring = it }
    anim.animateToFinalPosition(target)
}
