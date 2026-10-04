package com.example.docscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.docscanner.R
import com.example.docscanner.scan.Quad

/**
 * Draws the live page outline over the camera preview. The preview uses fitCenter, so the
 * frame is mapped into the largest centred rectangle with the frame's aspect ratio.
 * While the page is held still the outline fills in to show auto-capture progress.
 */
class PageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val readyColor = ContextCompat.getColor(context, R.color.accent)

    private var quad: Quad? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var progress = 0f
    private var ready = false

    /** [quad] is in 0..1 fractions of a [frameWidth]×[frameHeight] upright frame. */
    fun show(quad: Quad?, frameWidth: Int, frameHeight: Int, ready: Boolean, progress: Float) {
        this.quad = quad
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        this.ready = ready
        this.progress = progress
        invalidate()
    }

    fun clear() = show(null, 0, 0, false, 0f)

    override fun onDraw(canvas: Canvas) {
        val q = quad ?: return
        if (frameWidth == 0 || frameHeight == 0) return
        val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val w = frameWidth * scale
        val h = frameHeight * scale
        val left = (width - w) / 2
        val top = (height - h) / 2

        path.rewind()
        q.points.forEachIndexed { i, p ->
            val x = left + p.x.toFloat() * w
            val y = top + p.y.toFloat() * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        val color = if (ready) readyColor else Color.WHITE
        fill.color = Color.argb((40 + 80 * progress).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        stroke.color = color
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }
}
