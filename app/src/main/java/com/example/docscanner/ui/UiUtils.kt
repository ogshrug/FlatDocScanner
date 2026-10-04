package com.example.docscanner.ui

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.docscanner.util.Bitmaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pads this view by the system bar insets (edge-to-edge is enforced from targetSdk 35). */
fun View.padForSystemBars(top: Boolean = false, bottom: Boolean = false) {
    val start = paddingLeft
    val startTop = paddingTop
    val end = paddingRight
    val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(
            left = start + bars.left,
            right = end + bars.right,
            top = startTop + if (top) bars.top else 0,
            bottom = startBottom + if (bottom) bars.bottom else 0,
        )
        insets
    }
}

/** Small decoded-thumbnail cache for list and grid items. Keys are file paths, which change on every re-save. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(view: ImageView, path: String?, maxDim: Int, scope: CoroutineScope) {
        view.tag = path
        if (path == null) {
            view.setImageDrawable(null)
            return
        }
        cache.get(path)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Bitmaps.decode(File(path), maxDim) } ?: return@launch
            cache.put(path, bmp)
            if (view.tag == path) view.setImageBitmap(bmp)
        }
    }
}
