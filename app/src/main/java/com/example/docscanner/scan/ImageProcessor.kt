package com.example.docscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

enum class PageFilter { ORIGINAL, COLOR, GRAY, BW }

/** Perspective correction and clean-up filters. */
object ImageProcessor {

    fun warp(source: Bitmap, quad: Quad): Bitmap {
        val (w, h) = quad.outputSize()
        val src = Mat()
        val dst = Mat()
        val from = MatOfPoint2f(*quad.points.map { Point(it.x, it.y) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(w.toDouble(), 0.0),
            Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        try {
            Utils.bitmapToMat(source, src)
            Imgproc.warpPerspective(src, dst, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR)
            return toBitmap(dst)
        } finally {
            src.release(); dst.release(); from.release(); to.release(); m.release()
        }
    }

    fun applyFilter(page: Bitmap, filter: PageFilter): Bitmap {
        if (filter == PageFilter.ORIGINAL) return page.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        val out = Mat()
        try {
            Utils.bitmapToMat(page, rgba)
            when (filter) {
                PageFilter.COLOR -> enhanceColor(rgba, out)
                PageFilter.GRAY -> enhanceGray(rgba, out)
                PageFilter.BW -> blackAndWhite(rgba, out)
                PageFilter.ORIGINAL -> rgba.copyTo(out)
            }
            return toBitmap(out)
        } finally {
            rgba.release(); out.release()
        }
    }

    /** CLAHE on the lightness channel only, so contrast improves without shifting colours. */
    private fun enhanceColor(rgba: Mat, out: Mat) {
        val lab = Mat()
        Imgproc.cvtColor(rgba, lab, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        Imgproc.cvtColor(out, out, Imgproc.COLOR_RGB2RGBA)
        channels.forEach { it.release() }
        lab.release()
    }

    private fun enhanceGray(rgba: Mat, out: Mat) {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, gray)
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
        gray.release()
    }

    /** Adaptive threshold for a "scanned" look; the block size follows the page width. */
    private fun blackAndWhite(rgba: Mat, out: Mat) {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.medianBlur(gray, gray, 3)
        var block = (gray.cols() / 40).coerceAtLeast(15)
        if (block % 2 == 0) block++
        Imgproc.adaptiveThreshold(
            gray, gray, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 10.0,
        )
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
        gray.release()
    }

    private fun toBitmap(mat: Mat): Bitmap {
        val rgba = if (mat.type() == CvType.CV_8UC4) mat else Mat().also {
            Imgproc.cvtColor(mat, it, if (mat.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_RGB2RGBA)
        }
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        if (rgba !== mat) rgba.release()
        return bmp
    }
}
