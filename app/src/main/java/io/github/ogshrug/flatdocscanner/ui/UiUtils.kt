package io.github.ogshrug.flatdocscanner.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import io.github.ogshrug.flatdocscanner.util.Bitmaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Edge-to-edge with light status and navigation bar icons. The app is always dark, so the
 * bars must not follow the system's light theme (which would draw dark icons on our background).
 */
fun ComponentActivity.enableDarkEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
}

/** Keeps a floating button above the navigation bar by adding the bar's height to its margin. */
fun View.liftAboveNavigationBar() {
    val base = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = base + bottom }
        insets
    }
}

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

/** Asks for a short text (folder name, rename) in a dialog; [onDone] gets the trimmed, non-empty text. */
fun androidx.appcompat.app.AppCompatActivity.promptText(
    title: Int,
    initial: String,
    positive: Int,
    onDone: (String) -> Unit,
) {
    val pad = (20 * resources.displayMetrics.density).toInt()
    val input = android.widget.EditText(this).apply {
        setText(initial)
        setSelectAllOnFocus(true)
        isSingleLine = true
    }
    val container = android.widget.FrameLayout(this).apply {
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(container)
        .setNegativeButton(io.github.ogshrug.flatdocscanner.R.string.action_cancel, null)
        .setPositiveButton(positive) { _, _ ->
            input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone)
        }
        .show()
    input.requestFocus()
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
