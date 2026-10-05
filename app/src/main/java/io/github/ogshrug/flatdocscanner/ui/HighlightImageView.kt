package io.github.ogshrug.flatdocscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.ogshrug.flatdocscanner.R

/**
 * A page image that can mark search matches, given as rectangles in 0..1 fractions of the
 * page. With [aspect] set (height / width), it sizes itself to the page's proportions even
 * before the image has loaded, so a scrolling list doesn't jump.
 */
class HighlightImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    var highlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Height / width; 0 = measure normally. */
    var aspect = 0f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.accent), 110)
    }
    private val content = RectF()
    private val mark = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspect <= 0f) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = drawable ?: return
        if (highlights.isEmpty()) return
        content.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(content)
        content.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        val pad = 3 * resources.displayMetrics.density
        for (h in highlights) {
            mark.set(
                content.left + h.left * content.width() - pad, content.top + h.top * content.height() - pad,
                content.left + h.right * content.width() + pad, content.top + h.bottom * content.height() + pad,
            )
            canvas.drawRoundRect(mark, pad, pad, paint)
        }
    }
}
