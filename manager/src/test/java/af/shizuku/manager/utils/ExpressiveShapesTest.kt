package af.shizuku.manager.utils

import af.shizuku.manager.utils.ExpressiveShapes.areaCentroid
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExpressiveShapesTest {
    private val shapeKeys = ExpressiveShapes.KEYS.filter { it != ExpressiveShapes.OFF }

    @Test
    fun everyShapeIsVisuallyCenteredInTheUnitSquare() {
        for (key in shapeKeys) {
            val p = ExpressiveShapes.polygon(key)
            // The icon is drawn at the drawable's center; the shape's area centroid must match.
            val (cx, cy) = p.areaCentroid()
            assertEquals(0.5f, cx, 0.002f, "$key centroid x")
            assertEquals(0.5f, cy, 0.002f, "$key centroid y")
            val b = p.calculateBounds(approximate = false)
            assertTrue(b[0] >= -0.001f && b[1] >= -0.001f && b[2] <= 1.001f && b[3] <= 1.001f, "$key fits: ${b.toList()}")
        }
    }

    @Test
    fun everyShapeIsMirrorSymmetricLeftToRight() {
        // A shape that leans to one side reads as off-center even with a centered centroid.
        for (key in shapeKeys) {
            val b = ExpressiveShapes.polygon(key).calculateBounds(approximate = false)
            assertEquals(0.5f - b[0], b[2] - 0.5f, 0.01f, "$key left/right extent")
        }
    }

    @Test
    fun iconStaysInsideEveryShape() {
        for (key in shapeKeys) {
            val p = ExpressiveShapes.polygon(key)
            // Material icons draw within a 20dp circle of their 24dp box.
            val glyphRadius = ExpressiveShapes.iconFraction(key, max = 0.6f) * (20f / 24f) / 2f
            var inradius = Float.MAX_VALUE
            for (c in p.cubics) {
                for (i in 0..32) {
                    val t = i / 32f
                    val u = 1 - t
                    val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
                    val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
                    inradius = minOf(inradius, kotlin.math.hypot(x - 0.5f, y - 0.5f))
                }
            }
            assertTrue(glyphRadius < inradius, "$key glyph $glyphRadius vs inradius $inradius")
        }
    }

    @Test
    fun circleIsCenteredSoMorphsDoNotDrift() {
        val (cx, cy) = ExpressiveShapes.circle.areaCentroid()
        assertEquals(0.5f, cx, 0.002f)
        assertEquals(0.5f, cy, 0.002f)
    }

    @Test
    fun aStarPointingRightWasOffCenter() {
        // Documents the bug: star() starts with a point facing right, so the bounding box and
        // the shape's middle disagree.
        val star = RoundedPolygon.star(5, innerRadius = 0.52f, rounding = CornerRounding(0.18f, smoothing = 0.2f)).normalized()
        val (cx, _) = star.areaCentroid()
        assertTrue(kotlin.math.abs(cx - 0.5f) > 0.02f)
    }

    @Test
    fun roundedRectanglesAreCenteredAndFit() {
        for (f in listOf(0.1f, 0.3f, 0.6f)) {
            val key = ExpressiveShapes.rectKey(f)
            val (cx, cy) = ExpressiveShapes.polygon(key).areaCentroid()
            assertEquals(0.5f, cx, 0.002f, "$key centroid x")
            assertEquals(0.5f, cy, 0.002f, "$key centroid y")
            assertEquals(4, ExpressiveShapes.symmetry(key), "$key symmetry")
        }
    }

    @Test
    fun spinLandsOnAnIdenticalOutline() {
        // Spin turns by one point of the shape; the shape must map onto itself at that angle.
        for (key in shapeKeys + ExpressiveShapes.rectKey(0.3f)) {
            val n = ExpressiveShapes.symmetry(key)
            if (n <= 1) continue
            val r = ExpressiveShapes.polarRadii(key)
            val shift = ExpressiveShapes.POLAR_BINS / n
            var worst = 0f
            for (i in r.indices) worst = maxOf(worst, kotlin.math.abs(r[i] - r[(i + shift) % r.size]))
            assertTrue(worst < 0.01f, "$key: rotated by 360/$n differs by $worst")
        }
    }

    @Test
    fun sweepAndLiquidRestAtTheEnds() {
        // At progress 0 and 1 every part of the outline must be fully off or fully on, so the
        // polar drawing meets the exact shape drawn at rest.
        for (mode in listOf(MorphMode.SWEEP, MorphMode.LIQUID)) {
            for (forward in listOf(true, false)) {
                for (i in 0..100) {
                    val frac = i / 100f
                    assertEquals(0f, ExpressiveShapes.polarProgress(frac, 0f, forward, mode), 1e-4f, "$mode $forward at 0")
                    assertEquals(1f, ExpressiveShapes.polarProgress(frac, 1f, forward, mode), 1e-4f, "$mode $forward at 1")
                }
            }
        }
    }
}
