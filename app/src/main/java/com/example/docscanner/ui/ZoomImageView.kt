package com.example.docscanner.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.docscanner.R
import kotlin.math.abs

/**
 * Shows a page fitted to the screen, with pinch and double-tap zoom and panning.
 *
 * Inside a ViewPager2: at normal size, horizontal swipes go to the pager (next/previous page).
 * While zoomed in, swipes pan the page; once the page is panned to its edge in the swipe
 * direction, the swipe is handed back to the pager.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSingleTap: (() -> Unit)? = null

    /** Search matches to mark, as rectangles in 0..1 fractions of the page. */
    var highlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.accent), 110)
    }
    private val mark = RectF()

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()

    /** Scale that fits the page in the view; [zoom] is applied on top of it. */
    private var fitScale = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var animator: ValueAnimator? = null

    val isZoomed get() = zoom > 1.01f

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        resetZoom()
    }

    fun resetZoom() {
        animator?.cancel()
        zoom = 1f
        layoutFit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = resetZoom()

    private fun layoutFit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        fitScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        clampTranslation(center = true)
    }

    private fun contentWidth() = (bitmap?.width ?: 0) * fitScale * zoom
    private fun contentHeight() = (bitmap?.height ?: 0) * fitScale * zoom

    /** Keeps the page on screen: centred when smaller than the view, edge-to-edge otherwise. */
    private fun clampTranslation(center: Boolean = false) {
        val cw = contentWidth()
        val ch = contentHeight()
        tx = if (cw <= width || center && zoom <= 1f) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height || center && zoom <= 1f) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
    }

    /** Zooms to [newZoom] keeping the page point under ([fx], [fy]) in place. */
    private fun zoomAround(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ratio = z / zoom
        tx = fx - (fx - tx) * ratio
        ty = fy - (fy - ty) * ratio
        zoom = z
        clampTranslation()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        drawMatrix.setScale(fitScale * zoom, fitScale * zoom)
        drawMatrix.postTranslate(tx, ty)
        canvas.drawBitmap(b, drawMatrix, paint)
        val w = contentWidth()
        val h = contentHeight()
        for (r in highlights) {
            mark.set(tx + r.left * w, ty + r.top * h, tx + r.right * w, ty + r.bottom * h)
            canvas.drawRoundRect(mark, 6f, 6f, highlightPaint)
        }
    }

    /** Whether the page can still pan in the direction of a horizontal drag of [dx] pixels. */
    private fun canPan(dx: Float): Boolean {
        if (!isZoomed || contentWidth() <= width) return false
        return if (dx > 0) tx < -0.5f else tx > width - contentWidth() + 0.5f
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (isZoomed) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed) return false
            tx -= dx
            ty -= dy
            clampTranslation()
            invalidate()
            return true
        }
    })

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomAround(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    private var lastX = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                // Keep the gesture while zoomed; at normal size the pager may take a swipe.
                parent?.requestDisallowInterceptTouchEvent(isZoomed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                lastX = event.x
                if (event.pointerCount == 1 && abs(dx) > 0.5f) {
                    // Hand the swipe to the pager once the page can't pan further that way.
                    parent?.requestDisallowInterceptTouchEvent(canPan(dx) || scaleDetector.isInProgress)
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
