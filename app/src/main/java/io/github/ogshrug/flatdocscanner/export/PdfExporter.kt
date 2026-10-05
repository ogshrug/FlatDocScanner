package io.github.ogshrug.flatdocscanner.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import io.github.ogshrug.flatdocscanner.data.PageEntity
import io.github.ogshrug.flatdocscanner.ocr.PageText
import io.github.ogshrug.flatdocscanner.ocr.PageTextCodec
import io.github.ogshrug.flatdocscanner.scan.ImageProcessor
import io.github.ogshrug.flatdocscanner.scan.PageFilter
import io.github.ogshrug.flatdocscanner.util.Bitmaps
import java.io.File
import java.io.FileOutputStream

/** Choices on the Create PDF screen. */
data class PdfOptions(
    val pageSize: PageSize = PageSize.FIT,
    val quality: Quality = Quality.HIGH,
    val color: ColorMode = ColorMode.AS_SCANNED,
    val reverse: Boolean = false,
) {
    /** Paper size in points (1/72 inch); FIT is A4 wide with the page's own proportions. */
    enum class PageSize(val widthPt: Int, val heightPt: Int) { FIT(595, 0), A4(595, 842), LETTER(612, 792) }

    /** Long side of the embedded images. PdfDocument stores them losslessly, so this sets the file size. */
    enum class Quality(val maxDim: Int) { MAXIMUM(3000), HIGH(1754), SMALL(1100) }

    enum class ColorMode { AS_SCANNED, GRAYSCALE, BLACK_AND_WHITE }
}

/**
 * Writes pages to a PDF with Android's built-in [PdfDocument] (no AGPL dependency). Pages whose
 * text was recognised get an invisible text layer over the image, so the PDF can be searched
 * and text copied.
 */
object PdfExporter {

    fun export(pages: List<PageEntity>, out: File, options: PdfOptions = PdfOptions()) {
        require(pages.isNotEmpty()) { "no pages to export" }
        val pdf = PdfDocument()
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (options.color == PdfOptions.ColorMode.GRAYSCALE) {
            imagePaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Fully transparent drawing is skipped by the PDF writer, so use the faintest visible alpha.
            color = Color.argb(1, 0, 0, 0)
        }
        val ordered = if (options.reverse) pages.reversed() else pages
        try {
            ordered.forEachIndexed { index, page ->
                var bitmap = Bitmaps.decode(File(page.processedPath), options.quality.maxDim)
                    ?: error("could not read page ${index + 1}")
                if (options.color == PdfOptions.ColorMode.BLACK_AND_WHITE) {
                    bitmap = ImageProcessor.applyFilter(bitmap, PageFilter.BW).also { bitmap.recycle() }
                }
                val size = options.pageSize
                val pageW = size.widthPt
                val pageH = if (size == PdfOptions.PageSize.FIT) {
                    (pageW.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                } else size.heightPt
                // The image fills the page width (FIT) or is centred inside the paper size.
                val scale = minOf(pageW.toFloat() / bitmap.width, pageH.toFloat() / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val box = RectF((pageW - w) / 2, (pageH - h) / 2, (pageW + w) / 2, (pageH + h) / 2)

                val info = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, null, box, imagePaint)
                PageTextCodec.decode(page.ocr)?.let { drawTextLayer(canvas, it, box, textPaint) }
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
    private fun drawTextLayer(canvas: Canvas, text: PageText, box: RectF, paint: Paint) {
        for (block in text.blocks) for (line in block.lines) {
            val s = line.text.trim()
            if (s.isEmpty()) continue
            val boxW = ((line.right - line.left) * box.width()).toFloat()
            val boxH = ((line.bottom - line.top) * box.height()).toFloat()
            if (boxW <= 0f || boxH <= 0f) continue
            paint.textScaleX = 1f
            paint.textSize = boxH * 0.85f
            val measured = paint.measureText(s)
            if (measured > 0f) paint.textScaleX = boxW / measured
            canvas.drawText(
                s, box.left + (line.left * box.width()).toFloat(),
                box.top + (line.bottom * box.height()).toFloat() - boxH * 0.18f, paint,
            )
        }
    }
}
