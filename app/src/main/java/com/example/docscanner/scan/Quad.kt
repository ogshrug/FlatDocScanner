package com.example.docscanner.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

data class Pt(val x: Double, val y: Double) {
    fun scaled(sx: Double, sy: Double = sx) = Pt(x * sx, y * sy)
    fun distanceTo(o: Pt) = hypot(x - o.x, y - o.y)
}

/** A page outline, always ordered top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    fun scaled(sx: Double, sy: Double = sx) =
        Quad(tl.scaled(sx, sy), tr.scaled(sx, sy), br.scaled(sx, sy), bl.scaled(sx, sy))

    /** Corners as fractions of the image size, so they survive decoding at a different resolution. */
    fun normalized(width: Int, height: Int) = scaled(1.0 / width, 1.0 / height)

    fun denormalized(width: Int, height: Int) = scaled(width.toDouble(), height.toDouble())

    fun withPoint(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        3 -> copy(bl = p)
        else -> throw IndexOutOfBoundsException("corner $index")
    }

    /** Output size of the flattened page, from the longer of each pair of opposite edges. */
    fun outputSize(): Pair<Int, Int> {
        val w = maxOf(tl.distanceTo(tr), bl.distanceTo(br))
        val h = maxOf(tl.distanceTo(bl), tr.distanceTo(br))
        return maxOf(1, w.roundToInt()) to maxOf(1, h.roundToInt())
    }

    fun area(): Double {
        val p = points
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
    }

    /** True when the four corners form a convex shape with no crossing edges. */
    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0.0) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    fun clampedTo(width: Int, height: Int): Quad {
        fun c(p: Pt) = Pt(p.x.coerceIn(0.0, width.toDouble()), p.y.coerceIn(0.0, height.toDouble()))
        return Quad(c(tl), c(tr), c(br), c(bl))
    }

    fun serialize(): String = points.joinToString(",") { "${it.x},${it.y}" }

    companion object {
        /** Orders any four points clockwise, starting from the one nearest the top-left. */
        fun fromUnordered(points: List<Pt>): Quad {
            require(points.size == 4) { "need 4 points, got ${points.size}" }
            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            // Image y points down, so increasing atan2 angle runs clockwise on screen.
            val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
            val o = List(4) { clockwise[(start + it) % 4] }
            return Quad(o[0], o[1], o[2], o[3])
        }

        /** Default outline when detection finds nothing: the image inset by [insetFraction]. */
        fun inset(width: Int, height: Int, insetFraction: Double = 0.1): Quad {
            val dx = width * insetFraction
            val dy = height * insetFraction
            return Quad(
                Pt(dx, dy), Pt(width - dx, dy),
                Pt(width - dx, height - dy), Pt(dx, height - dy),
            )
        }

        fun deserialize(s: String?): Quad? {
            val v = s?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null
            if (v.size != 8) return null
            return Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7]))
        }
    }
}
