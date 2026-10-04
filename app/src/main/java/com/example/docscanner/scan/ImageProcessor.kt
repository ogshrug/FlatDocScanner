package com.example.docscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.min

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

    /**
     * Crops [photo] to the bounding box of [quad] (fractions of the photo) plus [margin] of its
     * size on every side, so the curved-page model sees the page with a little background.
     */
    fun cropAround(photo: Bitmap, quad: Quad?, margin: Double = 0.08): Bitmap {
        val q = quad?.denormalized(photo.width, photo.height) ?: return photo
        val xs = q.points.map { it.x }
        val ys = q.points.map { it.y }
        val mx = (xs.max() - xs.min()) * margin
        val my = (ys.max() - ys.min()) * margin
        val x0 = (xs.min() - mx).toInt().coerceIn(0, photo.width - 1)
        val y0 = (ys.min() - my).toInt().coerceIn(0, photo.height - 1)
        val x1 = (xs.max() + mx).toInt().coerceIn(x0 + 1, photo.width)
        val y1 = (ys.max() + my).toInt().coerceIn(y0 + 1, photo.height)
        return Bitmap.createBitmap(photo, x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * One page of a two-page spread: the other page, beyond [spine], is painted over with the
     * background colour (so the curved-page model sees a single page), then the photo is cropped
     * to this side of the spread.
     */
    fun halfPage(photo: Bitmap, spread: Quad?, spine: Spine, side: PageSide): Bitmap {
        require(side != PageSide.NONE)
        val w = photo.width
        val h = photo.height
        val mat = Mat()
        Utils.bitmapToMat(photo, mat)
        try {
            val fill = backgroundColor(mat)
            val top = spine.xTop * w
            val bottom = spine.xBottom * w
            val other = if (side == PageSide.LEFT) {
                listOf(Point(top, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), Point(bottom, h.toDouble()))
            } else {
                listOf(Point(0.0, 0.0), Point(top, 0.0), Point(bottom, h.toDouble()), Point(0.0, h.toDouble()))
            }
            Imgproc.fillConvexPoly(mat, MatOfPoint(*other.toTypedArray()), fill)

            val box = spread?.denormalized(w, h)?.points
            val pad = w * 0.03
            val spineMin = minOf(top, bottom)
            val spineMax = maxOf(top, bottom)
            val boxX0 = box?.let { it.minOf { p -> p.x } - pad } ?: 0.0
            val boxX1 = box?.let { it.maxOf { p -> p.x } + pad } ?: w.toDouble()
            val x0 = (if (side == PageSide.LEFT) boxX0 else spineMin - pad).toInt().coerceIn(0, w - 1)
            val x1 = (if (side == PageSide.LEFT) spineMax + pad else boxX1).toInt().coerceIn(x0 + 1, w)
            val y0 = (box?.let { it.minOf { p -> p.y } - pad } ?: 0.0).toInt().coerceIn(0, h - 1)
            val y1 = (box?.let { it.maxOf { p -> p.y } + pad } ?: h.toDouble()).toInt().coerceIn(y0 + 1, h)
            val crop = mat.submat(y0, y1, x0, x1)
            val out = Bitmap.createBitmap(crop.cols(), crop.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(crop, out)
            crop.release()
            return out
        } finally {
            mat.release()
        }
    }

    /** Average colour of the photo's top and bottom strips, which are usually background. */
    private fun backgroundColor(rgba: Mat): Scalar {
        val strip = (rgba.rows() * 0.02).toInt().coerceAtLeast(1)
        val top = Core.mean(rgba.submat(0, strip, 0, rgba.cols()))
        val bottom = Core.mean(rgba.submat(rgba.rows() - strip, rgba.rows(), 0, rgba.cols()))
        return Scalar((top.`val`[0] + bottom.`val`[0]) / 2, (top.`val`[1] + bottom.`val`[1]) / 2, (top.`val`[2] + bottom.`val`[2]) / 2, 255.0)
    }

    /**
     * Brightens the gutter shadow on the spine side of a flattened book page to the paper level
     * of the rest of the page, then trims the dark fold itself off the inner edge.
     */
    fun correctGutter(page: Bitmap, side: PageSide): Bitmap {
        if (side == PageSide.NONE) return page.copy(Bitmap.Config.ARGB_8888, false)
        val innerRight = side == PageSide.LEFT
        val full = Mat()
        Utils.bitmapToMat(page, full)
        val gray = Mat()
        try {
            // Paper level per column, measured on a small copy.
            val scale = min(1.0, 600.0 / full.cols())
            Imgproc.cvtColor(full, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, gray, Size(full.cols() * scale, full.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val gw = gray.cols()
            val gh = gray.rows()
            val data = ByteArray(gw * gh).also { gray.get(0, 0, it) }
            val levels = DoubleArray(gw) { x ->
                val hist = IntArray(256)
                for (yy in 0 until gh) hist[data[yy * gw + x].toInt() and 0xFF]++
                var acc = 0
                var v = 0
                val target = gh * 90 / 100
                while (v < 255 && acc + hist[v] <= target) { acc += hist[v]; v++ }
                v.toDouble()
            }
            val gains = Gutter.gains(levels, innerRight)

            // Apply to the full-resolution zone next to the spine.
            val fw = full.cols()
            val zoneW = (fw * Gutter.ZONE).toInt().coerceAtLeast(1)
            val zx0 = if (innerRight) fw - zoneW else 0
            val zone = full.submat(0, full.rows(), zx0, zx0 + zoneW)
            val gainRow = Mat(1, zoneW, CvType.CV_32FC4)
            val rowData = FloatArray(zoneW * 4)
            for (i in 0 until zoneW) {
                val src = ((zx0 + i) * scale).toInt().coerceIn(0, gw - 1)
                val g = gains[src].toFloat()
                rowData[i * 4] = g; rowData[i * 4 + 1] = g; rowData[i * 4 + 2] = g; rowData[i * 4 + 3] = 1f
            }
            gainRow.put(0, 0, rowData)
            val gainMat = Mat()
            Core.repeat(gainRow, full.rows(), 1, gainMat)
            val zf = Mat()
            zone.convertTo(zf, CvType.CV_32FC4)
            Core.multiply(zf, gainMat, zf)
            zf.convertTo(zone, CvType.CV_8UC4)
            gainRow.release(); gainMat.release(); zf.release(); zone.release()

            // Trim the fold line itself.
            val trim = (fw * 0.015).toInt()
            val kept = if (innerRight) full.submat(0, full.rows(), 0, fw - trim) else full.submat(0, full.rows(), trim, fw)
            return toBitmap(kept).also { kept.release() }
        } finally {
            full.release()
            gray.release()
        }
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
