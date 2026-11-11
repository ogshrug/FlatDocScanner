package io.github.ogshrug.flatdocscanner.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.util.Bitmaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A colour of the theme this context is showing, e.g. `androidx.appcompat.R.attr.colorPrimary`. */
fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

/**
 * Edge-to-edge, with status and navigation bar icons that suit the theme: light icons over a
 * dark background and dark over a light one, whatever the system's own setting.
 */
fun ComponentActivity.enableAppEdgeToEdge() {
    val dark = ColorUtils.calculateLuminance(themeColor(android.R.attr.colorBackground)) < 0.5
    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** How long an Undo bar stays up: long enough to read it and reach the button. */
const val UNDO_MILLIS = 6000

/**
 * An Undo offer from a screen that closes as part of the deletion (a folder deleting itself,
 * the viewer losing its last page). The screen underneath picks it up when it comes back.
 */
object PendingUndo {
    class Offer(val message: String, val undo: suspend () -> Unit)

    private var offer: Offer? = null
    private var leftAt = 0L

    fun leave(offer: Offer) {
        this.offer = offer
        leftAt = SystemClock.elapsedRealtime()
    }

    /** The waiting offer, if one was left in the last few seconds. */
    fun take(): Offer? {
        val waiting = offer
        offer = null
        return waiting?.takeIf { SystemClock.elapsedRealtime() - leftAt < UNDO_MILLIS }
    }
}

/** Shows an Undo bar for something that has already been moved to the Trash. */
fun AppCompatActivity.showUndo(root: View, anchor: View?, message: String, undo: suspend () -> Unit) {
    Snackbar.make(root, message, UNDO_MILLIS)
        .apply { if (anchor != null && anchor.isShown) anchorView = anchor }
        .setAction(R.string.action_undo) { lifecycleScope.launch { undo() } }
        .show()
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
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(positive) { _, _ ->
            input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone)
        }
        .show()
    input.requestFocus()
}

/**
 * Small decoded-thumbnail cache for list and grid items. Page files get a new path on every
 * re-save, so a path never goes stale; the size is part of the key because the same page is
 * shown small in the grid and large in the reader view.
 */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** A few decodes at a time: more only makes them all slower and starves the rest of the app. */
    private val decoder = Dispatchers.IO.limitedParallelism(4)

    /** What a view is still waiting for, so a recycled view can drop it. */
    private class Pending(val job: Job, var fade: ObjectAnimator? = null)

    fun load(view: ImageView, path: String?, maxDim: Int, scope: CoroutineScope) {
        // The view was scrolled away or given another image: forget the old request. Decodes
        // that haven't started yet are skipped, which keeps a fast fling smooth.
        (view.getTag(R.id.thumbnail_pending) as? Pending)?.let {
            it.job.cancel()
            it.fade?.cancel()
        }
        view.setTag(R.id.thumbnail_pending, null)
        view.imageAlpha = 255
        view.tag = path
        if (path == null) {
            view.setImageDrawable(null)
            return
        }
        val key = "$path@$maxDim"
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val job = scope.launch {
            val bmp = withContext(decoder) { Bitmaps.decode(File(path), maxDim) } ?: return@launch
            cache.put(key, bmp)
            if (view.tag != path) return@launch
            view.setImageBitmap(bmp)
            // Fade the picture in over its placeholder instead of popping.
            val fade = ObjectAnimator.ofInt(view, "imageAlpha", 0, 255).setDuration(140)
            (view.getTag(R.id.thumbnail_pending) as? Pending)?.fade = fade
            fade.start()
        }
        view.setTag(R.id.thumbnail_pending, Pending(job))
    }
}
