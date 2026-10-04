package com.example.docscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Classical page detection: edges → contours → the largest convex 4-point polygon.
 * Works on a downscaled grayscale copy and returns corners in the coordinates of the input.
 */
object PageDetector {

    /** Long side the detector works at; live camera frames are analysed at about this size. */
    const val DETECT_MAX_DIM = 640
    private const val MIN_AREA_FRACTION = 0.15

    /**
     * @param loose also accept outlines that are not clean 4-point polygons (curved pages,
     *   open books): the largest page-sized contour is reduced to 4 corners.
     */
    fun detect(bitmap: Bitmap, loose: Boolean = false): Quad? {
        val scale = DETECT_MAX_DIM.toDouble() / max(bitmap.width, bitmap.height)
        val small = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true,
            )
        } else bitmap
        val s = if (scale < 1.0) scale else 1.0

        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return detectInGray(gray, loose)?.scaled(1.0 / s)?.clampedTo(bitmap.width, bitmap.height)
        } finally {
            rgba.release()
            gray.release()
            if (small !== bitmap) small.recycle()
        }
    }

    /** Detects in a grayscale image already at about [DETECT_MAX_DIM]. Leaves [gray] unchanged. */
    fun detectInGray(gray: Mat, loose: Boolean = false): Quad? {
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val minArea = blurred.rows() * blurred.cols() * MIN_AREA_FRACTION
            return findQuad(edgeMask(blurred), minArea, false)
                ?: findQuad(thresholdMask(blurred), minArea, loose)
        } finally {
            blurred.release()
        }
    }

    /** Canny with thresholds picked from the image's median brightness, then dilated to close gaps. */
    private fun edgeMask(gray: Mat): Mat {
        val median = median(gray)
        val edges = Mat()
        Imgproc.Canny(gray, edges, max(0.0, 0.66 * median), minOf(255.0, 1.33 * median))
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()
        return edges
    }

    /** Fallback for low-contrast edges: Otsu split of a bright page against a darker background. */
    private fun thresholdMask(gray: Mat): Mat {
        val mask = Mat()
        Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        return mask
    }

    private fun findQuad(mask: Mat, minArea: Double, loose: Boolean): Quad? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        mask.release()
        try {
            val candidates = contours
                .map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(10)
            for ((contour, _) in candidates) {
                val c2f = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                val peri = Imgproc.arcLength(c2f, true)
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                val pts = approx.toArray()
                c2f.release()
                approx.release()
                if (pts.size != 4) continue
                val quad = Quad.fromUnordered(pts.map { Pt(it.x, it.y) })
                if (quad.isConvex() && quad.area() >= minArea) return quad
            }
            return if (loose) candidates.firstOrNull()?.let { looseQuad(it.first) } else null
        } finally {
            contours.forEach { it.release() }
        }
    }

    /** Reduces a curved outline to 4 corners: its convex hull, simplified until 4 points remain. */
    private fun looseQuad(contour: MatOfPoint): Quad? {
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val all = contour.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { all[it] }.toTypedArray())
        hullIdx.release()
        val peri = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        try {
            var eps = 0.02
            while (eps <= 0.12) {
                Imgproc.approxPolyDP(hull, approx, eps * peri, true)
                val pts = approx.toArray()
                if (pts.size == 4) {
                    val q = Quad.fromUnordered(pts.map { Pt(it.x, it.y) })
                    if (q.isConvex()) return q
                }
                if (pts.size < 4) break
                eps += 0.01
            }
            // Last resort: the smallest rotated rectangle around the outline.
            val box = Array(4) { org.opencv.core.Point() }
            Imgproc.minAreaRect(hull).points(box)
            return Quad.fromUnordered(box.map { Pt(it.x, it.y) })
        } finally {
            hull.release()
            approx.release()
        }
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
        return Core.mean(gray).`val`[0]
    }
}
