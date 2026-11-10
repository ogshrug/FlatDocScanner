package io.github.ogshrug.flatdocscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.scan.Quad
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws the live page outline over the camera preview. The preview uses fitCenter, so the
 * frame is mapped into the largest centred rectangle with the frame's aspect ratio.
 *
 * The detector reports a new outline 10 to 30 times a second and each one differs by a pixel
 * or two, so drawing them as they arrive makes the outline shiver. Instead the drawn outline
 * glides towards the latest one on every display frame, fades in when a page is found and
 * fades out when it is lost. Auto-capture itself still works on the raw outlines.
 *
 * While the page is held still the tint deepens to show auto-capture progress.
 */
class PageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Path()
    private val brackets = Path()
    private val readyColor = ContextCompat.getColor(context, R.color.accent)
    private val bracketLength = 26 * density

    /** Latest outline from the detector and the one being drawn, as x0, y0, … x3, y3 in 0..1. */
    private val target = FloatArray(8)
    private val shown = FloatArray(8)
    private var hasTarget = false
    private var hasShown = false

    private var frameWidth = 0
    private var frameHeight = 0
    private var targetProgress = 0f
    private var shownProgress = 0f
    private var ready = false
    private var opacity = 0f
    private var lastDrawNanos = 0L

    /** [quad] is in 0..1 fractions of a [frameWidth]×[frameHeight] upright frame. */
    fun show(quad: Quad?, frameWidth: Int, frameHeight: Int, ready: Boolean, progress: Float) {
        if (frameWidth > 0 && frameHeight > 0) {
            this.frameWidth = frameWidth
            this.frameHeight = frameHeight
        }
        this.ready = ready
        targetProgress = progress
        if (quad != null) {
            var jump = 0f
            quad.points.forEachIndexed { i, p ->
                target[i * 2] = p.x.toFloat()
                target[i * 2 + 1] = p.y.toFloat()
                jump = maxOf(jump, abs(target[i * 2] - shown[i * 2]), abs(target[i * 2 + 1] - shown[i * 2 + 1]))
            }
            // A new page, or the outline jumped to somewhere else: start there, don't sweep across.
            if (!hasShown || opacity == 0f || jump > SNAP_DISTANCE) {
                target.copyInto(shown)
                hasShown = true
            }
            hasTarget = true
        } else {
            hasTarget = false
        }
        postInvalidateOnAnimation()
    }

    fun clear() = show(null, 0, 0, false, 0f)

    override fun onDraw(canvas: Canvas) {
        if (!hasShown || frameWidth == 0 || frameHeight == 0) return

        val now = System.nanoTime()
        val dt = if (lastDrawNanos == 0L) 1f / 60 else ((now - lastDrawNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastDrawNanos = now

        var settling = false
        if (hasTarget) {
            val k = 1f - exp(-dt / GLIDE_SECONDS)
            for (i in 0 until 8) {
                val d = target[i] - shown[i]
                if (abs(d) > 0.0004f) {
                    shown[i] += d * k
                    settling = true
                } else {
                    shown[i] = target[i]
                }
            }
            opacity = min(1f, opacity + dt / FADE_IN_SECONDS)
            if (opacity < 1f) settling = true
        } else {
            opacity -= dt / FADE_OUT_SECONDS
            if (opacity <= 0f) {
                opacity = 0f
                hasShown = false
                shownProgress = 0f
                lastDrawNanos = 0L
                return
            }
            settling = true
        }
        val dp = targetProgress - shownProgress
        if (abs(dp) > 0.004f) {
            shownProgress += dp * (1f - exp(-dt / GLIDE_SECONDS))
            settling = true
        } else {
            shownProgress = targetProgress
        }

        val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val w = frameWidth * scale
        val h = frameHeight * scale
        val left = (width - w) / 2
        val top = (height - h) / 2
        fun x(i: Int) = left + shown[i * 2] * w
        fun y(i: Int) = top + shown[i * 2 + 1] * h

        outline.rewind()
        brackets.rewind()
        for (i in 0 until 4) {
            if (i == 0) outline.moveTo(x(i), y(i)) else outline.lineTo(x(i), y(i))
            // An L at each corner, its arms running along the two sides that meet there.
            val prev = (i + 3) % 4
            val next = (i + 1) % 4
            arm(i, prev, ::x, ::y, start = true)
            brackets.lineTo(x(i), y(i))
            arm(i, next, ::x, ::y, start = false)
        }
        outline.close()

        // The page is tinted as soon as it is found; the tint deepens as auto-capture counts down.
        val color = if (ready) readyColor else Color.WHITE
        fill.color = ColorUtils.setAlphaComponent(readyColor, ((36 + 84 * shownProgress) * opacity).toInt())
        edge.color = ColorUtils.setAlphaComponent(color, (150 * opacity).toInt())
        bracket.color = ColorUtils.setAlphaComponent(color, (255 * opacity).toInt())
        canvas.drawPath(outline, fill)
        canvas.drawPath(outline, edge)
        canvas.drawPath(brackets, bracket)

        if (settling) postInvalidateOnAnimation() else lastDrawNanos = 0L
    }

    /** One arm of a corner bracket: from (or to) a point a short way along the side towards [other]. */
    private inline fun arm(corner: Int, other: Int, x: (Int) -> Float, y: (Int) -> Float, start: Boolean) {
        val dx = x(other) - x(corner)
        val dy = y(other) - y(corner)
        val side = hypot(dx, dy)
        val t = if (side < 1f) 0f else min(bracketLength, side * 0.3f) / side
        val px = x(corner) + dx * t
        val py = y(corner) + dy * t
        if (start) brackets.moveTo(px, py) else brackets.lineTo(px, py)
    }

    private companion object {
        /** Time for the drawn outline to cover most of the distance to a new one. */
        const val GLIDE_SECONDS = 0.07f
        const val FADE_IN_SECONDS = 0.12f
        const val FADE_OUT_SECONDS = 0.2f
        /** A corner moving further than this (fraction of the frame) between frames is a new page. */
        const val SNAP_DISTANCE = 0.2f
    }
}
