package io.github.ogshrug.flatdocscanner.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves page images to the phone's gallery, in Pictures/FlatDocScanner. On Android 10+ this
 * needs no permission; on Android 7-9 the caller must hold WRITE_EXTERNAL_STORAGE.
 */
object GalleryExporter {

    const val ALBUM = "FlatDocScanner"

    /** Returns how many images were saved. */
    fun save(context: Context, files: List<Pair<String, File>>): Int {
        var saved = 0
        for ((name, file) in files) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                    dir.mkdirs()
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(dir, name).path)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            runCatching {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
                saved++
            }.onFailure { resolver.delete(uri, null, null) }
        }
        return saved
    }

    fun needsPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
}
