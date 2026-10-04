package com.example.docscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
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

    fun detect(bitmap: Bitmap): Quad? {
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
            return detectInGray(gray)?.scaled(1.0 / s)?.clampedTo(bitmap.width, bitmap.height)
        } finally {
            rgba.release()
            gray.release()
            if (small !== bitmap) small.recycle()
        }
    }

    /** Detects in a grayscale image already at about [DETECT_MAX_DIM]. Leaves [gray] unchanged. */
    fun detectInGray(gray: Mat): Quad? {
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val minArea = blurred.rows() * blurred.cols() * MIN_AREA_FRACTION
            return findQuad(edgeMask(blurred), minArea) ?: findQuad(thresholdMask(blurred), minArea)
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

    private fun findQuad(mask: Mat, minArea: Double): Quad? {
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
            return null
        } finally {
            contours.forEach { it.release() }
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
