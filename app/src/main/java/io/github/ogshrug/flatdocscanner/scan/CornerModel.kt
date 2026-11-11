package io.github.ogshrug.flatdocscanner.scan

import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Learned page-corner detector: DocAligner LCNet-100 (Apache-2.0, DocsaidLab), run with
 * ONNX Runtime or Ferrite (see ModelEngine). The model sees the photo squashed to 256×256 and outputs one 128×128 heatmap
 * per corner (TL, TR, BR, BL). Each corner is the weighted centre of the blob around that
 * heatmap's peak; one weak corner is completed from the other three.
 */
object CornerModel {

    private const val MODEL_ASSET = "docaligner_lcnet100.onnx"
    private const val IN = 256

    /** A corner whose heatmap never reaches this is "not seen". */
    private const val MIN_PEAK = 0.12f
    /** Completing a missing corner needs the other three to be at least this confident. */
    private const val STRONG_PEAK = 0.3f

    private lateinit var appContext: Context
    private val model: ModelRunner by lazy { ModelEngine.open(appContext, MODEL_ASSET, threads = 2) }

    // Working memory, reused for every frame: the live preview calls detect() many times a
    // second, and allocating half a megabyte each time keeps the garbage collector busy.
    // detect() is synchronized, so the camera thread and a photo being saved take turns.
    private val bgr = Mat()
    private val pixels = ByteArray(IN * IN * 3)
    private val input: FloatBuffer =
        ByteBuffer.allocateDirect(3 * IN * IN * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val inputShape = intArrayOf(1, 3, IN, IN)
    private val outputShape = intArrayOf(1, 4, HM_I, HM_I)
    private val heat = FloatArray(4 * HM_I * HM_I)
    private val maskBytes = ByteArray(HM_I * HM_I)
    private val labelValues = IntArray(HM_I * HM_I)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Loads the model now (call off the main thread), so the camera's first frame isn't held up. */
    fun warmUp() {
        runCatching { model }
    }

    /** Finds the page in an RGBA image; returns its corners in that image's pixels, or null. */
    @Synchronized
    fun detect(rgba: Mat): Quad? {
        val w = rgba.cols()
        val h = rgba.rows()
        run(rgba)
        val peaks = FloatArray(4)
        val corners = (0 until 4).map { c ->
            val (pt, peak) = corner(c * HM_I * HM_I)
            peaks[c] = peak
            pt?.let { Pt(it.x / HM * w, it.y / HM * h) }
        }
        val missing = corners.count { it == null }
        if (missing > 1) return null
        if (missing == 1 && peaks.sorted()[1] < STRONG_PEAK) return null
        val quad = Quad.completing(corners) ?: return null
        val ordered = Quad.fromUnordered(quad.points)
        if (!ordered.isConvex() || !ordered.anglesWithin() || ordered.area() < 0.03 * w * h) return null
        return ordered
    }

    /**
     * Runs the model and leaves its output in [heat]: four 128x128 heatmaps, one after the
     * other. Model input is BGR in 0..1, CHW; the output is [1, 4, 128, 128].
     */
    private fun run(rgba: Mat) {
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.resize(bgr, bgr, Size(IN.toDouble(), IN.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        bgr.get(0, 0, pixels)
        val plane = IN * IN
        // Written straight into native memory, which either engine reads without another copy.
        for (i in 0 until plane) {
            input.put(i, (pixels[i * 3].toInt() and 0xFF) / 255f)
            input.put(plane + i, (pixels[i * 3 + 1].toInt() and 0xFF) / 255f)
            input.put(2 * plane + i, (pixels[i * 3 + 2].toInt() and 0xFF) / 255f)
        }
        input.rewind()
        model.run(input, inputShape, heat, outputShape)
    }

    /**
     * Weighted centre of the largest blob above half the peak of the heatmap starting at
     * [offset] in [heat], in heatmap pixels (pixel centres at +0.5), and the peak value.
     */
    private fun corner(offset: Int): Pair<Pt?, Float> {
        val size = HM_I * HM_I
        var peak = 0f
        for (i in 0 until size) if (heat[offset + i] > peak) peak = heat[offset + i]
        if (peak < MIN_PEAK) return null to peak
        val threshold = maxOf(MIN_PEAK, 0.5f * peak)

        val mask = Mat(HM_I, HM_I, CvType.CV_8U)
        val bytes = maskBytes
        for (i in 0 until size) bytes[i] = if (heat[offset + i] >= threshold) 1 else 0
        mask.put(0, 0, bytes)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            if (n < 2) return null to peak
            var best = 1
            for (i in 2 until n) {
                if (stats.get(i, Imgproc.CC_STAT_AREA)[0] > stats.get(best, Imgproc.CC_STAT_AREA)[0]) best = i
            }
            val lab = labelValues
            labels.get(0, 0, lab)
            var sw = 0.0
            var sx = 0.0
            var sy = 0.0
            for (y in 0 until HM_I) for (x in 0 until HM_I) {
                if (lab[y * HM_I + x] == best) {
                    val wgt = heat[offset + y * HM_I + x].toDouble()
                    sw += wgt; sx += x * wgt; sy += y * wgt
                }
            }
            return Pt(sx / sw + 0.5, sy / sw + 0.5) to peak
        } finally {
            mask.release(); labels.release(); stats.release(); centroids.release()
        }
    }

    private const val HM_I = 128
    private const val HM = 128.0
}
