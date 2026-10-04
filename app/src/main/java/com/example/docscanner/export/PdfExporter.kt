package com.example.docscanner.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.docscanner.data.PageEntity
import com.example.docscanner.ocr.PageText
import com.example.docscanner.ocr.PageTextCodec
import com.example.docscanner.util.Bitmaps
import java.io.File
import java.io.FileOutputStream

/**
 * Writes pages to a PDF with Android's built-in [PdfDocument] (no AGPL dependency).
 * Each page is A4 wide with the page image's own aspect ratio. Pages whose text was recognised
 * get an invisible text layer over the image, so the PDF can be searched and text copied.
 */
object PdfExporter {

    private const val A4_WIDTH_PT = 595
    /** ~150 dpi on A4; PdfDocument stores images losslessly, so this keeps files a sane size. */
    private const val MAX_IMAGE_DIM = 1754

    fun export(pages: List<PageEntity>, out: File) {
        require(pages.isNotEmpty()) { "no pages to export" }
        val pdf = PdfDocument()
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Fully transparent drawing is skipped by the PDF writer, so use the faintest visible alpha.
            color = Color.argb(1, 0, 0, 0)
        }
        try {
            pages.forEachIndexed { index, page ->
                val bitmap = Bitmaps.decode(File(page.processedPath), MAX_IMAGE_DIM)
                    ?: error("could not read page ${index + 1}")
                val heightPt = (A4_WIDTH_PT.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, heightPt, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, A4_WIDTH_PT.toFloat(), heightPt.toFloat()), imagePaint)
                PageTextCodec.decode(page.ocr)?.let { drawTextLayer(canvas, it, A4_WIDTH_PT.toFloat(), heightPt.toFloat(), textPaint) }
                pdf.finishPage(pdfPage)
                bitmap.recycle()
            }
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /** Each recognised line, sized and stretched to cover its box on the page image. */
    private fun drawTextLayer(canvas: Canvas, text: PageText, w: Float, h: Float, paint: Paint) {
        for (block in text.blocks) for (line in block.lines) {
            val s = line.text.trim()
            if (s.isEmpty()) continue
            val boxW = ((line.right - line.left) * w).toFloat()
            val boxH = ((line.bottom - line.top) * h).toFloat()
            if (boxW <= 0f || boxH <= 0f) continue
            paint.textScaleX = 1f
            paint.textSize = boxH * 0.85f
            val measured = paint.measureText(s)
            if (measured > 0f) paint.textScaleX = boxW / measured
            canvas.drawText(s, (line.left * w).toFloat(), (line.bottom * h).toFloat() - boxH * 0.18f, paint)
        }
    }
}
