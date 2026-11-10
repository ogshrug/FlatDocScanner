package io.github.ogshrug.flatdocscanner.scan

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads model files for ONNX Runtime without copying them through the Java heap.
 *
 * The .onnx assets are stored uncompressed in the APK (see noCompress in the build file), so
 * they can be memory-mapped straight from it. The 31 MB flattening model would otherwise be
 * read into a 31 MB byte array just to be parsed once.
 */
internal object ModelAssets {

    /**
     * Opens a model with [threads] worker threads. The convolutions run on XNNPACK, which was
     * about a tenth faster than ONNX Runtime's own kernels for both models on a mid-range
     * phone (Helio G96); if this build of the runtime doesn't include it, the plain CPU path
     * is used.
     */
    fun session(context: Context, name: String, threads: Int): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val model = map(context, name)
        runCatching {
            val options = OrtSession.SessionOptions().apply {
                // XNNPACK has its own thread pool; the runtime's is left with the few
                // operators XNNPACK doesn't take.
                setIntraOpNumThreads(1)
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
            return env.createSession(model, options)
        }
        model.rewind()
        return env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
    }

    fun map(context: Context, name: String): ByteBuffer =
        runCatching {
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) as ByteBuffer
                }
            }
        }.getOrElse {
            // The asset was compressed after all (a repackaged APK): read it the slow way.
            val bytes = context.assets.open(name).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                rewind()
            }
        }
}
