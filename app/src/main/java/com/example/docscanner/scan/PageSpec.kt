package com.example.docscanner.scan

import kotlin.math.abs
import kotlin.random.Random

/** How a page is flattened: a 4-point perspective crop, or the learned curved-page model. */
enum class PageMode { FLAT, CURVED }

/** Which half of a two-page book spread this page is; [NONE] for a single page. */
enum class PageSide { NONE, LEFT, RIGHT }

/**
 * The book's gutter as a straight line from the top to the bottom of the photo,
 * as fractions of the photo width (perspective can tilt it).
 */
data class Spine(val xTop: Double, val xBottom: Double) {

    fun xAt(yFraction: Double) = xTop + (xBottom - xTop) * yFraction

    fun serialize() = "$xTop,$xBottom"

    companion object {
        fun deserialize(s: String?): Spine? {
            val v = s?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null
            return if (v.size == 2) Spine(v[0], v[1]) else null
        }

        /** Fallback when no gutter shadow is found: the middle of the page outline. */
        fun middleOf(quad: Quad): Spine {
            // Extend the line through the midpoints of the top and bottom edges to y = 0 and y = 1.
            val top = Pt((quad.tl.x + quad.tr.x) / 2, (quad.tl.y + quad.tr.y) / 2)
            val bottom = Pt((quad.bl.x + quad.br.x) / 2, (quad.bl.y + quad.br.y) / 2)
            if (abs(bottom.y - top.y) < 1e-6) return Spine(top.x, top.x)
            val slope = (bottom.x - top.x) / (bottom.y - top.y)
            return Spine(top.x - slope * top.y, top.x + slope * (1 - top.y))
        }

        /**
         * Robust line fit x = a·y + b through gutter candidates (one darkest point per image row),
         * ignoring outliers such as dark pictures or text. Points are in pixels; returns the line
         * as fractions of [width] at y = 0 and y = [height], or null if too few points agree.
         */
        fun fit(points: List<Pt>, width: Int, height: Int, random: Random = Random(7)): Spine? {
            if (points.size < MIN_POINTS) return null
            val tolerance = width * 0.01
            var best: List<Pt> = emptyList()
            repeat(200) {
                val p = points[random.nextInt(points.size)]
                val q = points[random.nextInt(points.size)]
                if (p.y == q.y) return@repeat
                val a = (q.x - p.x) / (q.y - p.y)
                val b = p.x - a * p.y
                val inliers = points.filter { abs(a * it.y + b - it.x) < tolerance }
                if (inliers.size > best.size) best = inliers
            }
            if (best.size < points.size * MIN_INLIER_FRACTION) return null
            // Least-squares refit on the inliers.
            val n = best.size
            val my = best.sumOf { it.y } / n
            val mx = best.sumOf { it.x } / n
            val syy = best.sumOf { (it.y - my) * (it.y - my) }
            val a = if (syy < 1e-9) 0.0 else best.sumOf { (it.y - my) * (it.x - mx) } / syy
            val b = mx - a * my
            return Spine(b / width, (a * height + b) / width)
        }

        private const val MIN_POINTS = 10
        private const val MIN_INLIER_FRACTION = 0.6
    }
}

/** Everything needed to re-render a page from its original photo. */
data class PageSpec(
    /** Page outline as fractions of the photo; for curved pages, the area around the page. */
    val quad: Quad?,
    val mode: PageMode,
    val side: PageSide = PageSide.NONE,
    val spine: Spine? = null,
    val filter: PageFilter = ScanPipeline.DEFAULT_FILTER,
    val removeFingers: Boolean = true,
)

object Gutter {
    /** Share of the page width, at the spine side, that shadow correction may brighten. */
    const val ZONE = 0.2
    private const val MAX_GAIN = 2.5

    /**
     * Per-column brightness gains that lift the gutter shadow to the paper level of the rest
     * of the page. [paperLevels] is each column's paper brightness (a high percentile).
     */
    fun gains(paperLevels: DoubleArray, innerRight: Boolean): DoubleArray {
        val w = paperLevels.size
        if (w == 0) return DoubleArray(0)
        val smooth = DoubleArray(w) { x ->
            var sum = 0.0
            var n = 0
            for (k in maxOf(0, x - 4)..minOf(w - 1, x + 4)) { sum += paperLevels[k]; n++ }
            sum / n
        }
        val outer = if (innerRight) smooth.copyOfRange(0, (w * 0.6).toInt().coerceAtLeast(1))
        else smooth.copyOfRange((w * 0.4).toInt().coerceAtMost(w - 1), w)
        val target = outer.sorted()[outer.size / 2]
        val zone = if (innerRight) ((w * (1 - ZONE)).toInt() until w) else (0 until (w * ZONE).toInt())
        return DoubleArray(w) { x ->
            if (x in zone) (target / maxOf(smooth[x], 1.0)).coerceIn(1.0, MAX_GAIN) else 1.0
        }
    }
}
