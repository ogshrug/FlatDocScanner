package com.example.docscanner.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.docscanner.data.PageEntity
import com.example.docscanner.util.Bitmaps
import java.io.File
import java.io.FileOutputStream

/**
 * Writes pages to a PDF with Android's built-in [PdfDocument] (no AGPL dependency).
 * Each page is A4 wide with the page image's own aspect ratio.
 */
object PdfExporter {

    private const val A4_WIDTH_PT = 595
    /** ~150 dpi on A4; PdfDocument stores images losslessly, so this keeps files a sane size. */
    private const val MAX_IMAGE_DIM = 1754

    fun export(pages: List<PageEntity>, out: File) {
        require(pages.isNotEmpty()) { "no pages to export" }
        val pdf = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            pages.forEachIndexed { index, page ->
                val bitmap = Bitmaps.decode(File(page.processedPath), MAX_IMAGE_DIM)
                    ?: error("could not read page ${index + 1}")
                val heightPt = (A4_WIDTH_PT.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, heightPt, index + 1).create()
                val pdfPage = pdf.startPage(info)
                pdfPage.canvas.drawColor(Color.WHITE)
                pdfPage.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, A4_WIDTH_PT.toFloat(), heightPt.toFloat()), paint)
                pdf.finishPage(pdfPage)
                bitmap.recycle()
            }
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }
}
