package io.github.ogshrug.flatdocscanner.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Curved-page flattening with UVDoc (MIT, Verhoeven et al., SIGGRAPH Asia 2023), run on-device
 * with ONNX Runtime. The model sees the photo at 488×712 and predicts, for a 31×45 grid over
 * the flat page, where each point lies in the photo. That grid is upsampled and used to remap
 * the full-resolution photo, which matches the reference PyTorch implementation exactly.
 * The model is exported by tools/export_uvdoc_onnx.py.
 */
object Dewarper {

    private const val MODEL_ASSET = "uvdoc.onnx"
    private const val IN_W = 488
    private const val IN_H = 712

    private lateinit var appContext: Context
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        env.createSession(ModelAssets.map(appContext, MODEL_ASSET), options)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Loads the model now (call off the main thread), so the first curved page or book spread
     * doesn't also pay for parsing 31 MB of weights.
     */
    fun warmUp() {
        runCatching { session }
    }

    /** Flattens the page in [photo]. The output keeps the page's own proportions, long side [maxDim]. */
    fun flatten(photo: Bitmap, maxDim: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        try {
            val (gx, gy) = predictGrid(rgba)
            try {
                return remap(rgba, gx, gy, maxDim)
            } finally {
                gx.release(); gy.release()
            }
        } finally {
            rgba.release()
        }
    }

    /** Runs the model; returns the grid's x and y coordinates as two 45×31 float Mats in -1..1. */
    private fun predictGrid(rgba: Mat): Pair<Mat, Mat> {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.resize(rgb, rgb, Size(IN_W.toDouble(), IN_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val pixels = ByteArray(IN_W * IN_H * 3)
        rgb.get(0, 0, pixels)
        rgb.release()

        // HWC bytes → CHW floats in 0..1
        val plane = IN_W * IN_H
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            chw[i] = (pixels[i * 3].toInt() and 0xFF) / 255f
            chw[plane + i] = (pixels[i * 3 + 1].toInt() and 0xFF) / 255f
            chw[2 * plane + i] = (pixels[i * 3 + 2].toInt() and 0xFF) / 255f
        }

        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong()))
        input.use {
            session.run(mapOf("image" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val grid = (result[0].value as Array<Array<Array<FloatArray>>>)[0]
                return toMat(grid[0]) to toMat(grid[1])
            }
        }
    }

    private fun toMat(rows: Array<FloatArray>): Mat {
        val m = Mat(rows.size, rows[0].size, CvType.CV_32F)
        rows.forEachIndexed { r, row -> m.put(r, 0, row) }
        return m
    }

    private fun remap(rgba: Mat, gx: Mat, gy: Mat, maxDim: Int): Bitmap {
        val w = rgba.cols()
        val h = rgba.rows()
        val (outW, outH) = pageSize(gx, gy, w, h, maxDim)
        val mapX = toPixelMap(gx, outW, outH, w)
        val mapY = toPixelMap(gy, outW, outH, h)
        val out = Mat()
        try {
            Imgproc.remap(rgba, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp)
            return bmp
        } finally {
            mapX.release(); mapY.release(); out.release()
        }
    }

    /**
     * Upsamples one grid coordinate to the output size with align_corners=True bilinear
     * interpolation (as F.interpolate does), then converts -1..1 to source pixels.
     */
    private fun toPixelMap(g: Mat, outW: Int, outH: Int, srcSize: Int): Mat {
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, (g.cols() - 1.0) / max(1, outW - 1), 0.0, 0.0)
        m.put(1, 0, 0.0, (g.rows() - 1.0) / max(1, outH - 1), 0.0)
        val up = Mat()
        Imgproc.warpAffine(
            g, up, m, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE,
        )
        m.release()
        // (g + 1) / 2 * (size - 1)
        up.convertTo(up, CvType.CV_32F, (srcSize - 1) / 2.0, (srcSize - 1) / 2.0)
        return up
    }

    /**
     * The flat page's proportions, from the average length of the grid's rows (width) and
     * columns (height) measured in the photo, scaled so the long side is [maxDim].
     */
    private fun pageSize(gx: Mat, gy: Mat, w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val rows = gx.rows()
        val cols = gx.cols()
        val xs = FloatArray(rows * cols).also { gx.get(0, 0, it) }
        val ys = FloatArray(rows * cols).also { gy.get(0, 0, it) }
        fun px(i: Int) = (xs[i] + 1) / 2.0 * (w - 1)
        fun py(i: Int) = (ys[i] + 1) / 2.0 * (h - 1)
        var widthSum = 0.0
        for (r in 0 until rows) for (c in 1 until cols) {
            val a = r * cols + c - 1
            val b = r * cols + c
            widthSum += hypot(px(b) - px(a), py(b) - py(a))
        }
        var heightSum = 0.0
        for (c in 0 until cols) for (r in 1 until rows) {
            val a = (r - 1) * cols + c
            val b = r * cols + c
            heightSum += hypot(px(b) - px(a), py(b) - py(a))
        }
        val pageW = widthSum / rows
        val pageH = heightSum / cols
        val s = maxDim / max(pageW, pageH).coerceAtLeast(1.0)
        return (pageW * s).roundToInt().coerceAtLeast(1) to (pageH * s).roundToInt().coerceAtLeast(1)
    }
}
