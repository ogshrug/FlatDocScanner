package com.example.docscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the page in a photo. First the learned corner model ([CornerModel]); if it sees no page,
 * a classical fallback that looks for the page in three ways (grey edges, colour edges, the
 * bright paper region), turns each large outline into 4 corners by fitting a line to each side,
 * and keeps the candidate whose outline best follows real edges.
 *
 * On 130 real photos (DocUNet benchmark) this finds the right outline in about 90% of cases,
 * against 17% for the previous edge-only detector.
 */
object PageDetector {

    /** Long side the detector works at; live camera frames are analysed at about this size. */
    const val DETECT_MAX_DIM = 640

    /** Full detection for a captured photo. Returns corners in [bitmap] pixels. */
    fun detect(bitmap: Bitmap): Quad? {
        val rgba = Mat()
        val small = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val scale = DETECT_MAX_DIM.toDouble() / max(bitmap.width, bitmap.height)
            val s = if (scale < 1.0) scale else 1.0
            if (s < 1.0) Imgproc.resize(rgba, small, Size(rgba.cols() * s, rgba.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            else rgba.copyTo(small)
            val found = CornerModel.detect(small) ?: classical(small)
            return found?.scaled(1.0 / s)?.clampedTo(bitmap.width, bitmap.height)
        } finally {
            rgba.release()
            small.release()
        }
    }

    /**
     * Live preview detection on a small RGBA frame: the learned model only, which is fast and
     * doesn't jump onto backgrounds the way the classical fallback occasionally does.
     */
    fun detectLive(rgba: Mat): Quad? = CornerModel.detect(rgba)

    // --- classical fallback ---------------------------------------------------------------

    private class Candidate(val quad: Quad, val score: Double)

    private fun classical(rgba: Mat): Quad? {
        val w = rgba.cols()
        val h = rgba.rows()
        val imageArea = w.toDouble() * h
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val masks = Masks.build(rgba, gray)
        gray.release()
        try {
            var best: Candidate? = null
            for ((mask, external) in masks.regions) {
                for (contour in largeContours(mask, external, 0.1 * imageArea)) {
                    val cand = evaluate(contour, masks.edges, w, h, imageArea)
                    contour.release()
                    if (cand != null && cand.score > (best?.score ?: 0.0)) best = cand
                }
            }
            return best?.takeIf { it.score >= MIN_SCORE }?.quad
        } finally {
            masks.release()
        }
    }

    private fun evaluate(contour: MatOfPoint, edges: Mat, w: Int, h: Int, imageArea: Double): Candidate? {
        val hull = hull(contour)
        val hullArea = Imgproc.contourArea(hull)
        val quad = refineSides(hullQuad(hull), contour.toArray())
        hull.release()
        if (!quad.isConvex() || !quad.anglesWithin()) return null
        val coverage = quad.area() / imageArea
        if (coverage < 0.1 || coverage > 0.9) return null
        // Outlines along the photo border are almost always the table or the background.
        if (quad.sidesOnBorder(w, h) >= 1) return null
        val fill = minOf(1.0, hullArea / maxOf(quad.area(), 1.0))
        val support = edgeSupport(quad, edges, w, h)
        return Candidate(quad, 0.6 * support + 0.4 * fill)
    }

    private class Masks(val regions: List<Pair<Mat, Boolean>>, val edges: Mat) {
        fun release() {
            regions.forEach { it.first.release() }
            edges.release()
        }

        companion object {
            /** Three views of the page outline, plus a combined edge map for scoring. */
            fun build(rgba: Mat, blurredGray: Mat): Masks {
                // 1. Grey edges, Canny thresholds from the median brightness.
                val canny = Mat()
                val median = median(blurredGray)
                Imgproc.Canny(blurredGray, canny, max(0.0, 0.66 * median), minOf(255.0, 1.33 * median))
                Imgproc.dilate(canny, canny, kernel(3))

                // 2. Colour edges: strongest gradient over the L, a and b channels.
                val lab = Mat()
                Imgproc.cvtColor(rgba, lab, Imgproc.COLOR_RGBA2RGB)
                Imgproc.GaussianBlur(lab, lab, Size(5.0, 5.0), 0.0)
                Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
                val channels = ArrayList<Mat>()
                Core.split(lab, channels)
                val grad = Mat.zeros(blurredGray.size(), CvType.CV_32F)
                val gx = Mat()
                val gy = Mat()
                val mag = Mat()
                for (ch in channels) {
                    Imgproc.Sobel(ch, gx, CvType.CV_32F, 1, 0)
                    Imgproc.Sobel(ch, gy, CvType.CV_32F, 0, 1)
                    Core.magnitude(gx, gy, mag)
                    Core.max(grad, mag, grad)
                }
                gx.release(); gy.release(); mag.release()
                val grad8 = Mat()
                Core.normalize(grad, grad8, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
                grad.release()
                val colorEdges = Mat()
                Imgproc.threshold(grad8, colorEdges, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                grad8.release()
                val colorMask = Mat()
                Imgproc.morphologyEx(colorEdges, colorMask, Imgproc.MORPH_CLOSE, kernel(5))

                // 3. The bright paper region.
                val light = channels[0]
                Imgproc.GaussianBlur(light, light, Size(0.0, 0.0), 3.0)
                val bright = Mat()
                Imgproc.threshold(light, bright, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                Imgproc.morphologyEx(bright, bright, Imgproc.MORPH_CLOSE, kernel(15))
                Imgproc.morphologyEx(bright, bright, Imgproc.MORPH_OPEN, kernel(9))
                channels.forEach { it.release() }
                lab.release()

                // Scoring uses grey and colour edges together.
                val edges = Mat()
                Core.bitwise_or(canny, colorEdges, edges)
                Imgproc.dilate(edges, edges, kernel(5))
                colorEdges.release()
                return Masks(listOf(canny to false, colorMask to false, bright to true), edges)
            }

            private fun kernel(size: Int) =
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size.toDouble(), size.toDouble()))
        }
    }

    /** The 6 largest outlines whose convex hull covers at least [minArea]. */
    private fun largeContours(mask: Mat, external: Boolean, minArea: Double): List<MatOfPoint> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val work = mask.clone()
        Imgproc.findContours(
            work, contours, hierarchy,
            if (external) Imgproc.RETR_EXTERNAL else Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE,
        )
        work.release()
        hierarchy.release()
        val withArea = contours.map { c -> c to hull(c).let { hl -> Imgproc.contourArea(hl).also { hl.release() } } }
        val keep = withArea.filter { it.second >= minArea }.sortedByDescending { it.second }.take(6).map { it.first }
        contours.filter { it !in keep }.forEach { it.release() }
        return keep
    }

    private fun hull(contour: MatOfPoint): MatOfPoint {
        val idx = MatOfInt()
        Imgproc.convexHull(contour, idx)
        val all = contour.toArray()
        return MatOfPoint(*idx.toArray().map { all[it] }.toTypedArray()).also { idx.release() }
    }

    /** Simplifies the hull until 4 points remain, or falls back to the smallest rotated rectangle. */
    private fun hullQuad(hull: MatOfPoint): Quad {
        val h2f = MatOfPoint2f(*hull.toArray())
        val approx = MatOfPoint2f()
        try {
            val peri = Imgproc.arcLength(h2f, true)
            var eps = 0.01
            while (eps < 0.15) {
                Imgproc.approxPolyDP(h2f, approx, eps * peri, true)
                val pts = approx.toArray()
                if (pts.size == 4) return Quad.fromUnordered(pts.map { Pt(it.x, it.y) })
                if (pts.size < 4) break
                eps += 0.005
            }
            val box = Array(4) { Point() }
            Imgproc.minAreaRect(h2f).points(box)
            return Quad.fromUnordered(box.map { Pt(it.x, it.y) })
        } finally {
            h2f.release()
            approx.release()
        }
    }

    /**
     * Fits a straight line to the outline points along the middle 80% of each side and moves
     * the corners to where adjacent lines cross. Rounded, folded or covered corners then no
     * longer pull the outline inwards.
     */
    private fun refineSides(q: Quad, outline: Array<Point>): Quad {
        val p = q.points
        val lines = ArrayList<DoubleArray>(4) // x0, y0, dx, dy
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1) return q
            val tol = max(4.0, 0.03 * len)
            val side = outline.filter { pt ->
                val t = ((pt.x - a.x) * dx + (pt.y - a.y) * dy) / (len * len)
                val dist = abs((pt.x - a.x) * -dy + (pt.y - a.y) * dx) / len
                t > 0.1 && t < 0.9 && dist < tol
            }
            if (side.size < 5) return q
            val fit = Mat()
            val pts = MatOfPoint2f(*side.toTypedArray())
            Imgproc.fitLine(pts, fit, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val v = DoubleArray(4).also { arr -> for (k in 0 until 4) arr[k] = fit.get(k, 0)[0] }
            fit.release(); pts.release()
            lines += doubleArrayOf(v[2], v[3], v[0], v[1])
        }
        val corners = (0 until 4).map { i -> intersect(lines[(i + 3) % 4], lines[i]) ?: return q }
        val refined = Quad.fromUnordered(corners)
        return if (refined.isConvex() && refined.area() > 0.5 * q.area()) refined else q
    }

    /** Where two lines (x0, y0, dx, dy) cross, or null if they are parallel. */
    private fun intersect(l1: DoubleArray, l2: DoubleArray): Pt? {
        val det = l1[2] * -l2[3] - (-l2[2]) * l1[3]
        if (abs(det) < 1e-6) return null
        val rx = l2[0] - l1[0]
        val ry = l2[1] - l1[1]
        val t = (rx * -l2[3] - (-l2[2]) * ry) / det
        return Pt(l1[0] + t * l1[2], l1[1] + t * l1[3])
    }

    /** Share of 120 points along the outline that sit on an edge (or on the photo border). */
    private fun edgeSupport(q: Quad, edges: Mat, w: Int, h: Int): Double {
        val p = q.points
        var hit = 0
        var total = 0
        val px = ByteArray(1)
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            for (k in 0 until 30) {
                val t = 0.05 + 0.9 * k / 29
                val x = (a.x + t * (b.x - a.x)).roundToInt()
                val y = (a.y + t * (b.y - a.y)).roundToInt()
                total++
                if (x <= 3 || y <= 3 || x >= w - 4 || y >= h - 4) { hit++; continue }
                edges.get(y, x, px)
                if (px[0].toInt() != 0) hit++
            }
        }
        return hit.toDouble() / total
    }

    private fun median(gray: Mat): Double {
        val hist = IntArray(256)
        val data = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, data)
        for (b in data) hist[b.toInt() and 0xFF]++
        var count = 0
        val half = data.size / 2
        for (i in 0 until 256) {
            count += hist[i]
            if (count >= half) return i.toDouble()
        }
        return 128.0
    }

    private const val MIN_SCORE = 0.7
}
