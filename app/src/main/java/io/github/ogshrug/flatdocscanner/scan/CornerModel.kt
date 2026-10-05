package io.github.ogshrug.flatdocscanner.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

/**
 * Learned page-corner detector: DocAligner LCNet-100 (Apache-2.0, DocsaidLab), 4.8 MB, run with
 * ONNX Runtime. The model sees the photo squashed to 256×256 and outputs one 128×128 heatmap
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
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Finds the page in an RGBA image; returns its corners in that image's pixels, or null. */
    fun detect(rgba: Mat): Quad? {
        val w = rgba.cols()
        val h = rgba.rows()
        val heatmaps = run(rgba)
        val peaks = FloatArray(4)
        val corners = (0 until 4).map { c ->
            val (pt, peak) = corner(heatmaps[c])
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

    /** Model input is BGR in 0..1, CHW; the output is [1, 4, 128, 128]. */
    private fun run(rgba: Mat): Array<Array<FloatArray>> {
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.resize(bgr, bgr, Size(IN.toDouble(), IN.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val px = ByteArray(IN * IN * 3)
        bgr.get(0, 0, px)
        bgr.release()
        val plane = IN * IN
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            chw[i] = (px[i * 3].toInt() and 0xFF) / 255f
            chw[plane + i] = (px[i * 3 + 1].toInt() and 0xFF) / 255f
            chw[2 * plane + i] = (px[i * 3 + 2].toInt() and 0xFF) / 255f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, IN.toLong(), IN.toLong())).use { input ->
            session.run(mapOf("img" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<Array<Array<FloatArray>>>)[0]
            }
        }
    }

    /**
     * Weighted centre of the largest blob above half this heatmap's peak, in heatmap pixels
     * (pixel centres at +0.5), and the peak value.
     */
    private fun corner(hm: Array<FloatArray>): Pair<Pt?, Float> {
        var peak = 0f
        for (row in hm) for (v in row) if (v > peak) peak = v
        if (peak < MIN_PEAK) return null to peak
        val threshold = maxOf(MIN_PEAK, 0.5f * peak)

        val mask = Mat(HM_I, HM_I, CvType.CV_8U)
        val bytes = ByteArray(HM_I * HM_I)
        for (y in 0 until HM_I) for (x in 0 until HM_I) if (hm[y][x] >= threshold) bytes[y * HM_I + x] = 1
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
            val lab = IntArray(HM_I * HM_I)
            labels.get(0, 0, lab)
            var sw = 0.0
            var sx = 0.0
            var sy = 0.0
            for (y in 0 until HM_I) for (x in 0 until HM_I) {
                if (lab[y * HM_I + x] == best) {
                    val wgt = hm[y][x].toDouble()
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
