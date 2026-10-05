package io.github.ogshrug.flatdocscanner.scan

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

    /** True when every corner angle is within [minDeg]..[maxDeg]; rules out slivers and kites. */
    fun anglesWithin(minDeg: Double = 30.0, maxDeg: Double = 150.0): Boolean {
        val p = points
        for (i in p.indices) {
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val ux = a.x - b.x
            val uy = a.y - b.y
            val vx = c.x - b.x
            val vy = c.y - b.y
            val norm = hypot(ux, uy) * hypot(vx, vy)
            if (norm < 1e-9) return false
            val deg = Math.toDegrees(kotlin.math.acos(((ux * vx + uy * vy) / norm).coerceIn(-1.0, 1.0)))
            if (deg < minDeg || deg > maxDeg) return false
        }
        return true
    }

    /** How many sides lie along the image border; a page outline hugging 2+ borders is usually the background. */
    fun sidesOnBorder(width: Int, height: Int, margin: Double = 6.0): Int {
        val p = points
        return p.indices.count { i ->
            val a = p[i]
            val b = p[(i + 1) % 4]
            // Both ends on the same border.
            (a.x < margin && b.x < margin) || (a.y < margin && b.y < margin) ||
                (a.x > width - 1 - margin && b.x > width - 1 - margin) ||
                (a.y > height - 1 - margin && b.y > height - 1 - margin)
        }
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

        /**
         * Builds a quad from corners in TL, TR, BR, BL order where at most one is missing:
         * the missing corner completes the parallelogram of the other three.
         */
        fun completing(corners: List<Pt?>): Quad? {
            require(corners.size == 4)
            val missing = corners.indices.filter { corners[it] == null }
            if (missing.size > 1) return null
            val pts = corners.toMutableList()
            missing.firstOrNull()?.let { i ->
                val prev = pts[(i + 3) % 4]!!
                val next = pts[(i + 1) % 4]!!
                val opposite = pts[(i + 2) % 4]!!
                pts[i] = Pt(prev.x + next.x - opposite.x, prev.y + next.y - opposite.y)
            }
            return Quad(pts[0]!!, pts[1]!!, pts[2]!!, pts[3]!!)
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
