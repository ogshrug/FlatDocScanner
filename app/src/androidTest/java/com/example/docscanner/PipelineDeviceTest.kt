package com.example.docscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.docscanner.data.PageEntity
import com.example.docscanner.export.PdfExporter
import com.example.docscanner.ocr.PageTextCodec
import com.example.docscanner.ocr.Script
import com.example.docscanner.ocr.TextRecognizer
import com.example.docscanner.scan.Dewarper
import com.example.docscanner.scan.FingerRemover
import com.example.docscanner.scan.ImageProcessor
import com.example.docscanner.scan.PageDetector
import com.example.docscanner.scan.PageFilter
import com.example.docscanner.scan.Pt
import com.example.docscanner.scan.Quad
import com.example.docscanner.util.Bitmaps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the image pipeline on a real Android device or emulator: OpenCV, both ONNX models,
 * ML Kit and the PDF writer. The input is a synthetic photo of a printed page at an angle
 * on a dark table, so the true corners and words are known.
 */
@RunWith(AndroidJUnit4::class)
class PipelineDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The page's true corners in the 1200×1600 photo. */
    private val truth = Quad(Pt(260.0, 230.0), Pt(980.0, 300.0), Pt(1020.0, 1350.0), Pt(180.0, 1300.0))

    private val words = listOf("FLAT", "DOCUMENT", "SCANNER", "TEST")

    private fun photo(): Bitmap {
        val page = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.rgb(245, 243, 236))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 30, 30)
                textSize = 64f
                typeface = Typeface.DEFAULT_BOLD
            }
            words.forEachIndexed { i, w -> drawText(w, 90f, 200f + i * 150f, paint) }
            paint.textSize = 34f
            paint.typeface = Typeface.DEFAULT
            for (i in 0 until 8) drawText("The quick brown fox jumps over the lazy dog $i", 90f, 820f + i * 40f, paint)
        }
        val photo = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(70, 52, 40)) // dark wooden table
            val m = Matrix()
            m.setPolyToPoly(
                floatArrayOf(0f, 0f, 850f, 0f, 850f, 1100f, 0f, 1100f), 0,
                truth.points.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray(), 0, 4,
            )
            drawBitmap(page, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return photo
    }

    @Test
    fun detectsThePageCorners() {
        val quad = PageDetector.detect(photo())
        assertNotNull("no page found", quad)
        val worst = quad!!.points.zip(truth.points).maxOf { (a, b) -> a.distanceTo(b) }
        // Within 3% of the photo's long side.
        assertTrue("corners off by $worst px: $quad", worst < 48)
    }

    @Test
    fun flattensRecognisesAndExports() = runBlocking {
        val photo = photo()
        val quad = PageDetector.detect(photo) ?: truth
        val flat = ImageProcessor.warp(photo, quad)
        val clean = FingerRemover.remove(flat)
        val filtered = ImageProcessor.applyFilter(clean, PageFilter.COLOR)
        assertTrue(filtered.width > 400 && filtered.height > 500)

        // The curved-page model must load and produce a page of sensible proportions.
        val curved = Dewarper.flatten(photo, 1200)
        assertTrue("dewarp size ${curved.width}x${curved.height}", curved.width in 300..1200 && curved.height in 300..1200)

        val text = TextRecognizer.recognize(filtered, Script.LATIN)
        val found = text.plain().uppercase()
        words.forEach { assertTrue("'$it' not recognised in: $found", found.contains(it)) }

        // Save the page and export a PDF with a text layer.
        val dir = File(context.cacheDir, "device-test").apply { mkdirs() }
        val pageFile = File(dir, "page.jpg").also { Bitmaps.writeJpeg(filtered, it) }
        val page = PageEntity(
            documentId = 1, position = 0, originalPath = pageFile.path, processedPath = pageFile.path,
            corners = quad.serialize(), filter = PageFilter.COLOR.name, ocr = PageTextCodec.encode(text),
        )
        val pdf = File(dir, "test.pdf")
        PdfExporter.export(listOf(page), pdf)
        val bytes = pdf.readBytes()
        assertTrue("PDF too small: ${bytes.size}", bytes.size > 10_000)
        assertTrue("PDF has no font, so no text layer", String(bytes, Charsets.ISO_8859_1).contains("/Font"))
    }
}
