package io.github.ogshrug.flatdocscanner.scan

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * The Ferrite build: models run on Ferrite (third_party/ferrite), a small C inference
 * runtime with NEON kernels on 64-bit ARM, instead of ONNX Runtime. Its model files are the
 * same networks rewritten by Ferrite's tools/prepare_model.py (constants folded, BatchNorm
 * merged, weights kept in float16 wherever that is exact).
 */
object ModelEngine {

    const val NAME = "Ferrite"

    /**
     * Ferrite has one worker pool for the whole process. It is sized from the largest thread
     * count asked for, capped at four so the camera's analysis doesn't take every core.
     */
    @Volatile private var poolThreads = 0

    fun open(context: Context, asset: String, threads: Int): ModelRunner {
        synchronized(this) {
            val want = threads.coerceIn(1, 4)
            if (want > poolThreads) {
                FerriteNative.setThreads(want)
                poolThreads = want
            }
        }
        // The asset is memory-mapped from the APK and parsed in place; Ferrite copies the
        // weights it needs, so the mapping can go once loading returns.
        val handle = FerriteNative.load(ModelAssets.map(context, asset))
        return FerriteRunner(handle)
    }

    private class FerriteRunner(private val handle: Long) : ModelRunner {
        override fun run(input: FloatBuffer, inShape: IntArray, output: FloatArray, outShape: IntArray) {
            when {
                input.isDirect -> FerriteNative.runDirect(handle, input, inShape, output, outShape)
                input.hasArray() && input.arrayOffset() == 0 ->
                    FerriteNative.runArray(handle, input.array(), inShape, output, outShape)
                else -> throw IOException("Ferrite needs a direct or array-backed input buffer")
            }
        }
    }
}

/** The JNI side is app/src/ferrite/cpp/ferrite_jni.c. */
internal object FerriteNative {
    init {
        System.loadLibrary("ferrite_jni")
    }

    @JvmStatic external fun setThreads(n: Int)
    @JvmStatic external fun load(model: ByteBuffer): Long
    @JvmStatic external fun runDirect(handle: Long, input: FloatBuffer, inShape: IntArray, output: FloatArray, outShape: IntArray)
    @JvmStatic external fun runArray(handle: Long, input: FloatArray, inShape: IntArray, output: FloatArray, outShape: IntArray)
    @JvmStatic external fun free(handle: Long)
}
