package com.example.docscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Finds the gutter of an open book: in each row across the middle of the spread, the darkest
 * point of the central band (the spine shadow), then a robust straight-line fit through them.
 */
object SpineDetector {

    private const val WORK_MAX_DIM = 640.0

    /** [spread] is the book's outline as fractions of the photo, if known. */
    fun detect(photo: Bitmap, spread: Quad?): Spine? {
        val scale = WORK_MAX_DIM / max(photo.width, photo.height)
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(photo, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            if (scale < 1.0) Imgproc.resize(gray, gray, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val w = gray.cols()
            val h = gray.rows()

            // Search inside the spread's bounding box, or the whole photo.
            val box = spread?.denormalized(w, h)?.points
            val x0 = box?.minOf { it.x }?.toInt()?.coerceIn(0, w - 1) ?: 0
            val x1 = box?.maxOf { it.x }?.toInt()?.coerceIn(x0 + 1, w) ?: w
            val y0 = box?.minOf { it.y }?.toInt()?.coerceIn(0, h - 1) ?: 0
            val y1 = box?.maxOf { it.y }?.toInt()?.coerceIn(y0 + 1, h) ?: h
            val bw = x1 - x0
            val bh = y1 - y0
            val band0 = x0 + (bw * 0.3).toInt()
            val band1 = x0 + (bw * 0.7).toInt()
            if (band1 - band0 < 8 || bh < 20) return null

            Imgproc.GaussianBlur(gray, gray, Size(0.0, 0.0), max(2.0, bw / 120.0))
            val row = ByteArray(band1 - band0)
            val levels = IntArray(row.size)
            val points = ArrayList<Pt>()
            val step = max(1, bh / 60)
            var y = y0 + (bh * 0.15).toInt()
            while (y < y0 + (bh * 0.85).toInt()) {
                gray.get(y, band0, row)
                var minI = 0
                for (i in row.indices) {
                    levels[i] = row[i].toInt() and 0xFF
                    if (levels[i] < levels[minI]) minI = i
                }
                val median = levels.sorted()[levels.size / 2]
                // Only a clear dark valley counts as the gutter shadow.
                if (median - levels[minI] > MIN_VALLEY_DEPTH) points += Pt((band0 + minI).toDouble(), y.toDouble())
                y += step
            }
            return Spine.fit(points, w, h)
        } finally {
            rgba.release()
            gray.release()
        }
    }

    private const val MIN_VALLEY_DEPTH = 18
}
