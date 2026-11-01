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

    /** Path for [key] (or circle -> key at [progress]) scaled into [width] x [height]. */
    fun path(
        key: String,
        width: Float,
        height: Float,
        progress: Float = 1f,
        into: Path = Path(),
    ): Path {
        into.rewind()
        val target = polygon(key)
        if (progress >= 1f) {
            target.toPath(into)
        } else {
            morphs.getOrPut(key) { Morph(circle, target) }.toPath(progress, into)
        }
        into.transform(Matrix().apply { setScale(width, height) })
        return into
    }
}

/**
 * Icon-container background drawn from an [ExpressiveShapes] polygon. [progress] morphs from a
 * plain circle (0) to the target shape (1), so switch rows can morph their icon on toggle.
 */
class MorphShapeDrawable(
    private val color: Int,
    val shapeKey: String,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()

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
        ExpressiveShapes.path(shapeKey, b.width().toFloat(), b.height().toFloat(), progress.coerceAtMost(1f), path)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        if (progress > 1f) {
            // Overshoot reads as a quick "pop" past full size before settling.
            val s = progress
            canvas.scale(s, s, b.width() / 2f, b.height() / 2f)
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun onBoundsChange(bounds: Rect) = invalidateSelf()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // A tinted ImageView mutates its LayerDrawable, which copies each layer through its constant
    // state. Without one the copy shares this instance with the original and logs a warning.
    override fun getConstantState(): ConstantState =
        object : ConstantState() {
            override fun newDrawable(): Drawable = MorphShapeDrawable(color, shapeKey).also { it.progress = progress }

            override fun getChangingConfigurations(): Int = 0
        }
}

/**
 * Springs [MorphShapeDrawable.progress] to [target] (0 = circle, 1 = shape) with the bouncy M3E
 * feel; jumps straight there when animations are off.
 */
fun MorphShapeDrawable.springTo(target: Float) {
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
