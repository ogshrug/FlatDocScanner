package io.github.ogshrug.flatdocscanner.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/** The ONNX Runtime build: models run on ONNX Runtime, with XNNPACK where it helps. */
object ModelEngine {

    const val NAME = "ONNX Runtime"

    /**
     * Opens [asset] with [threads] worker threads. Convolutions run on XNNPACK, which was
     * about a tenth faster than ONNX Runtime's own kernels for both models on a mid-range
     * phone (Helio G96); if this build of the runtime doesn't include it, the plain CPU path
     * is used.
     */
    fun open(context: Context, asset: String, threads: Int): ModelRunner {
        val env = OrtEnvironment.getEnvironment()
        val model = ModelAssets.map(context, asset)
        val session = runCatching {
            val options = OrtSession.SessionOptions().apply {
                // XNNPACK has its own thread pool; the runtime's is left with the few
                // operators XNNPACK doesn't take.
                setIntraOpNumThreads(1)
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
            env.createSession(model, options)
        }.getOrElse {
            model.rewind()
            env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
        }
        return OrtRunner(env, session)
    }

    private class OrtRunner(private val env: OrtEnvironment, private val session: OrtSession) : ModelRunner {
        private val inputName = session.inputNames.first()

        override fun run(input: FloatBuffer, inShape: IntArray, output: FloatArray, outShape: IntArray) {
            input.rewind()
            val shape = LongArray(inShape.size) { inShape[it].toLong() }
            OnnxTensor.createTensor(env, input, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    (result[0] as OnnxTensor).floatBuffer.get(output)
                }
            }
        }
    }
}
