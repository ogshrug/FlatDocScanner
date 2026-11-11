package io.github.ogshrug.flatdocscanner.scan

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads model files without copying them through the Java heap.
 *
 * The .onnx assets are stored uncompressed in the APK (see noCompress in the build file), so
 * they can be memory-mapped straight from it. The 31 MB flattening model would otherwise be
 * read into a 31 MB byte array just to be parsed once.
 */
internal object ModelAssets {

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
