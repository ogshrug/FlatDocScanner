package io.github.ogshrug.flatdocscanner.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * Removes fingers holding the page down. Skin is judged against this page's own paper colour
 * (redder and darker than the paper), not fixed skin ranges, so cream or yellowed paper is
 * never mistaken for a finger. Only blobs touching the page edge are removed, and they are
 * filled in by inpainting.
 */
object FingerRemover {

    private const val WORK_MAX_DIM = 800.0

    fun remove(page: Bitmap): Bitmap {
        val full = Mat()
        Utils.bitmapToMat(page, full)
        val small = Mat()
        val scale = min(1.0, WORK_MAX_DIM / max(full.cols(), full.rows()))
        Imgproc.resize(full, small, Size(full.cols() * scale, full.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(small, small, Imgproc.COLOR_RGBA2RGB)
        try {
            val mask = fingerMask(small) ?: return page.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val filled = Mat()
                Photo.inpaint(small, mask, filled, 5.0, Photo.INPAINT_TELEA)
                // Paste the inpainted area back into the full-resolution page.
                val bigFilled = Mat()
                val bigMask = Mat()
                Imgproc.resize(filled, bigFilled, full.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Imgproc.resize(mask, bigMask, full.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
                Imgproc.cvtColor(bigFilled, bigFilled, Imgproc.COLOR_RGB2RGBA)
                bigFilled.copyTo(full, bigMask)
                filled.release(); bigFilled.release(); bigMask.release()
                val out = Bitmap.createBitmap(full.cols(), full.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(full, out)
                return out
            } finally {
                mask.release()
            }
        } finally {
            full.release()
            small.release()
        }
    }

    /** 8-bit mask of finger pixels on an RGB page, or null when there are none. */
    private fun fingerMask(rgb: Mat): Mat? {
        val ycc = Mat()
        Imgproc.GaussianBlur(rgb, ycc, Size(0.0, 0.0), 2.5)
        Imgproc.cvtColor(ycc, ycc, Imgproc.COLOR_RGB2YCrCb)
        val ch = ArrayList<Mat>()
        Core.split(ycc, ch)
        ycc.release()
        val y = ch[0]
        val cr = ch[1]
        ch[2].release()
        try {
            // Paper = the brightest 40% of the page.
            val paperY = percentile(y, null, 60)
            val paperMask = Mat()
            Imgproc.threshold(y, paperMask, paperY.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            val pY = percentile(y, paperMask, 50)
            val pCr = percentile(cr, paperMask, 50)
            paperMask.release()

            // Redder than paper, darker than paper, not black.
            val redder = Mat()
            val darker = Mat()
            val notBlack = Mat()
            Imgproc.threshold(cr, redder, (pCr + RED_MARGIN).toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(y, darker, (pY - DARK_MARGIN).toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.threshold(y, notBlack, 40.0, 255.0, Imgproc.THRESH_BINARY)
            val cand = Mat()
            Core.bitwise_and(redder, darker, cand)
            Core.bitwise_and(cand, notBlack, cand)
            redder.release(); darker.release(); notBlack.release()
            Imgproc.morphologyEx(cand, cand, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
            Imgproc.morphologyEx(cand, cand, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

            // Keep blobs that touch the page edge and are finger-sized.
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val n = Imgproc.connectedComponentsWithStats(cand, labels, stats, centroids)
            val w = cand.cols()
            val h = cand.rows()
            val band = (0.04 * min(w, h)).toInt()
            val out = Mat.zeros(cand.size(), CvType.CV_8U)
            var found = false
            val one = Mat()
            for (i in 1 until n) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val top = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                val touches = x <= band || top <= band || x + bw >= w - band || top + bh >= h - band
                if (touches && area > 0.001 * w * h && area < 0.1 * w * h) {
                    Core.compare(labels, Scalar(i.toDouble()), one, Core.CMP_EQ)
                    Core.bitwise_or(out, one, out)
                    found = true
                }
            }
            one.release(); labels.release(); stats.release(); centroids.release(); cand.release()
            if (!found) {
                out.release()
                return null
            }
            Imgproc.dilate(out, out, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(13.0, 13.0)))
            return out
        } finally {
            y.release()
            cr.release()
        }
    }

    /** The [pct]th percentile of an 8-bit single-channel Mat, optionally within [mask]. */
    private fun percentile(m: Mat, mask: Mat?, pct: Int): Int {
        val data = ByteArray((m.total()).toInt())
        m.get(0, 0, data)
        val maskData = mask?.let { ByteArray(it.total().toInt()).also { d -> it.get(0, 0, d) } }
        val hist = IntArray(256)
        var count = 0
        for (i in data.indices) {
            if (maskData != null && maskData[i].toInt() == 0) continue
            hist[data[i].toInt() and 0xFF]++
            count++
        }
        if (count == 0) return 0
        val target = count * pct / 100
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc > target) return v
        }
        return 255
    }

    private const val RED_MARGIN = 10
    private const val DARK_MARGIN = 12
}
