package com.example.docscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.docscanner.R
import androidx.core.graphics.withClip
import com.example.docscanner.scan.Pt
import com.example.docscanner.scan.Quad

/** Shows an image with a draggable four-corner outline and a magnifier while dragging. */
class CornerEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val handleRadius = 12 * density
    private val touchRadius = 40 * density
    private val loupeRadius = 56 * density
    private val loupeZoom = 2f

    private var bitmap: Bitmap? = null
    var quad: Quad? = null
        private set
    var onQuadChanged: ((Quad) -> Unit)? = null

    private val imageToView = Matrix()
    private val imageRect = RectF()
    private var activeCorner = -1
    private var touchX = 0f
    private var touchY = 0f

    // Reused on every frame to avoid allocating while drawing.
    private val corners = Array(4) { FloatArray(2) }
    private val outline = Path()
    private val shade = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val loupeClip = Path()
    private val loupeMatrix = Matrix()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }
    private val validColor = ContextCompat.getColor(context, R.color.accent)
    private val invalidColor = ContextCompat.getColor(context, R.color.error)

    fun setImage(bitmap: Bitmap, quad: Quad) {
        this.bitmap = bitmap
        this.quad = quad
        updateMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateMatrix()

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = handleRadius * 2
        val scale = minOf((width - 2 * pad) / bmp.width, (height - 2 * pad) / bmp.height)
        val dx = (width - bmp.width * scale) / 2
        val dy = (height - bmp.height * scale) / 2
        imageToView.setScale(scale, scale)
        imageToView.postTranslate(dx, dy)
        imageRect.set(dx, dy, dx + bmp.width * scale, dy + bmp.height * scale)
    }

    private fun toView(p: Pt): FloatArray =
        floatArrayOf(p.x.toFloat(), p.y.toFloat()).also { imageToView.mapPoints(it) }

    private fun toImage(x: Float, y: Float): Pt {
        val inv = Matrix().also { imageToView.invert(it) }
        val pts = floatArrayOf(x, y).also { inv.mapPoints(it) }
        val bmp = bitmap!!
        return Pt(pts[0].toDouble().coerceIn(0.0, bmp.width.toDouble()), pts[1].toDouble().coerceIn(0.0, bmp.height.toDouble()))
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val q = quad ?: return
        canvas.drawBitmap(bmp, imageToView, bitmapPaint)

        q.points.forEachIndexed { i, p ->
            corners[i][0] = p.x.toFloat()
            corners[i][1] = p.y.toFloat()
            imageToView.mapPoints(corners[i])
        }
        outline.rewind()
        outline.moveTo(corners[0][0], corners[0][1])
        for (i in 1..3) outline.lineTo(corners[i][0], corners[i][1])
        outline.close()
        shade.rewind()
        shade.addRect(imageRect, Path.Direction.CW)
        shade.addPath(outline)
        canvas.drawPath(shade, shadePaint)

        val color = if (q.isConvex()) validColor else invalidColor
        linePaint.color = color
        handleStroke.color = color
        canvas.drawPath(outline, linePaint)
        for (c in corners) {
            canvas.drawCircle(c[0], c[1], handleRadius, handleFill)
            canvas.drawCircle(c[0], c[1], handleRadius, handleStroke)
        }

        if (activeCorner >= 0) drawLoupe(canvas, bmp, corners[activeCorner])
    }

    /** Zoomed view of the area under the finger, drawn in the opposite top corner. */
    private fun drawLoupe(canvas: Canvas, bmp: Bitmap, at: FloatArray) {
        val margin = 16 * density
        val cx = if (touchX < width / 2) width - margin - loupeRadius else margin + loupeRadius
        val cy = margin + loupeRadius
        loupeClip.rewind()
        loupeClip.addCircle(cx, cy, loupeRadius, Path.Direction.CW)
        canvas.withClip(loupeClip) {
            drawColor(Color.BLACK)
            loupeMatrix.set(imageToView)
            loupeMatrix.postTranslate(-at[0], -at[1])
            loupeMatrix.postScale(loupeZoom, loupeZoom)
            loupeMatrix.postTranslate(cx, cy)
            drawBitmap(bmp, loupeMatrix, bitmapPaint)
        }
        canvas.drawCircle(cx, cy, loupeRadius, loupeBorder)
        val arm = 10 * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshair)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshair)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val q = quad ?: return false
        touchX = event.x
        touchY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = q.points.indices
                    .map { it to toView(q.points[it]) }
                    .map { (i, p) -> i to Math.hypot((p[0] - event.x).toDouble(), (p[1] - event.y).toDouble()) }
                    .filter { it.second <= touchRadius }
                    .minByOrNull { it.second }?.first ?: -1
                if (activeCorner < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (activeCorner >= 0) {
                quad = q.withPoint(activeCorner, toImage(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (activeCorner >= 0) {
                activeCorner = -1
                quad?.let { onQuadChanged?.invoke(it) }
                invalidate()
            }
        }
        return true
    }
}
