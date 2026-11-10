package io.github.ogshrug.flatdocscanner.scan

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.ogshrug.flatdocscanner.BuildConfig
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** What the live analyser saw in one camera frame. The quad is in 0..1 fractions of the upright frame. */
data class LiveFrame(
    val quad: Quad?,
    val frameWidth: Int,
    val frameHeight: Int,
    val sharpness: Double,
    val signature: FloatArray?,
    val timeMillis: Long,
)

/**
 * CameraX analyser: finds the page in each low-resolution RGBA preview frame with the learned
 * corner model, and measures its sharpness and a small content signature for [AutoCapture].
 * The analysis use case must output RGBA_8888.
 */
class FrameAnalyzer(private val onFrame: (LiveFrame) -> Unit) : ImageAnalysis.Analyzer {

    private var rowBuffer = ByteArray(0)
    private val raw = Mat()
    private val upright = Mat()
    private val small = Mat()
    private val gray = Mat()
    private val lap = Mat()
    private val lapMean = MatOfDouble()
    private val lapStd = MatOfDouble()

    override fun analyze(image: ImageProxy) {
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            val rgba = readUprightRgba(image)
            val read = SystemClock.elapsedRealtimeNanos()
            val quad = PageDetector.detectLive(rgba)
            val detected = SystemClock.elapsedRealtimeNanos()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val w = gray.cols()
            val h = gray.rows()
            onFrame(
                LiveFrame(
                    quad = quad?.normalized(w, h),
                    frameWidth = w,
                    frameHeight = h,
                    sharpness = sharpness(gray, quad),
                    signature = quad?.let { signature(gray, it) },
                    timeMillis = SystemClock.elapsedRealtime(),
                ),
            )
            if (BuildConfig.DEBUG) logTiming(started, read, detected)
        } finally {
            image.close()
        }
    }

    private var timedFrames = 0
    private var readNanos = 0L
    private var detectNanos = 0L
    private var restNanos = 0L
    private var windowStart = 0L

    /** Debug builds only: average time per frame for each stage, once every 30 frames. */
    private fun logTiming(started: Long, read: Long, detected: Long) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (timedFrames == 0) windowStart = started
        readNanos += read - started
        detectNanos += detected - read
        restNanos += now - detected
        if (++timedFrames < 30) return
        Log.i(
            "FrameAnalyzer",
            "per frame: read %.1f ms, detect %.1f ms, rest %.1f ms; %.1f frames/s".format(
                readNanos / 30e6, detectNanos / 30e6, restNanos / 30e6, 30e9 / (now - windowStart),
            ),
        )
        timedFrames = 0; readNanos = 0; detectNanos = 0; restNanos = 0
    }

    private fun readUprightRgba(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        // Camera frames arrive in native memory, so OpenCV can read them in place (rows may be
        // padded to rowStride). Turning the frame upright is then the only copy made of it.
        val wrapped = if (buffer.isDirect) Mat(h, w, CvType.CV_8UC4, buffer, rowStride.toLong()) else null
        val source = wrapped ?: raw.also {
            val rowBytes = w * 4
            if (rowBuffer.size != rowBytes * h) rowBuffer = ByteArray(rowBytes * h)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(rowBuffer, row * rowBytes, rowBytes)
            }
            it.create(h, w, CvType.CV_8UC4)
            it.put(0, 0, rowBuffer)
        }
        try {
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(source, upright, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(source, upright, Core.ROTATE_180)
                270 -> Core.rotate(source, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> source.copyTo(upright)
            }
        } finally {
            // Only the header: the pixels belong to the camera frame, which the caller closes.
            wrapped?.release()
        }

        val scale = PageDetector.DETECT_MAX_DIM.toDouble() / max(upright.cols(), upright.rows())
        if (scale >= 1.0) return upright
        Imgproc.resize(upright, small, Size(upright.cols() * scale, upright.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        return small
    }

    /** Variance of the Laplacian inside the page (or the whole frame): low means blurry. */
    private fun sharpness(gray: Mat, quad: Quad?): Double {
        val roi = if (quad != null) {
            val xs = quad.points.map { it.x }
            val ys = quad.points.map { it.y }
            // Stay inside the page so its outer edges don't count as detail.
            val insetX = (xs.max() - xs.min()) * 0.15
            val insetY = (ys.max() - ys.min()) * 0.15
            val x0 = (xs.min() + insetX).toInt().coerceIn(0, gray.cols() - 1)
            val y0 = (ys.min() + insetY).toInt().coerceIn(0, gray.rows() - 1)
            val x1 = (xs.max() - insetX).toInt().coerceIn(x0 + 1, gray.cols())
            val y1 = (ys.max() - insetY).toInt().coerceIn(y0 + 1, gray.rows())
            gray.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        } else gray
        try {
            // 16-bit holds every possible value exactly, at a quarter of the memory of doubles.
            Imgproc.Laplacian(roi, lap, CvType.CV_16S)
            Core.meanStdDev(lap, lapMean, lapStd)
            val s = lapStd.get(0, 0)[0]
            return s * s
        } finally {
            if (roi !== gray) roi.release()
        }
    }

    /**
     * A 24×32 thumbnail of the flattened page, normalised for brightness and contrast.
     * Warps to an intermediate size first, then area-averages down, so the signature
     * reflects the layout of the page rather than aliasing noise.
     */
    private fun signature(gray: Mat, quad: Quad): FloatArray {
        val from = MatOfPoint2f(*quad.points.map { Point(it.x, it.y) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(MID_W.toDouble(), 0.0),
            Point(MID_W.toDouble(), MID_H.toDouble()), Point(0.0, MID_H.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val mid = Mat()
        val tiny = Mat()
        try {
            Imgproc.warpPerspective(gray, mid, m, Size(MID_W.toDouble(), MID_H.toDouble()))
            Imgproc.resize(mid, tiny, Size(SIG_W.toDouble(), SIG_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val bytes = ByteArray(SIG_W * SIG_H)
            tiny.get(0, 0, bytes)
            return AutoCapture.normalizeSignature(FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF).toFloat() })
        } finally {
            from.release(); to.release(); m.release(); mid.release(); tiny.release()
        }
    }

    private companion object {
        const val MID_W = 96
        const val MID_H = 128
        const val SIG_W = 24
        const val SIG_H = 32
    }
}
