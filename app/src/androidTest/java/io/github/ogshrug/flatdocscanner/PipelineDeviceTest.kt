package io.github.ogshrug.flatdocscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ogshrug.flatdocscanner.data.PageEntity
import io.github.ogshrug.flatdocscanner.export.PdfExporter
import io.github.ogshrug.flatdocscanner.ocr.PageTextCodec
import io.github.ogshrug.flatdocscanner.ocr.Script
import io.github.ogshrug.flatdocscanner.ocr.TextRecognizer
import io.github.ogshrug.flatdocscanner.scan.Dewarper
import io.github.ogshrug.flatdocscanner.scan.FingerRemover
import io.github.ogshrug.flatdocscanner.scan.ImageProcessor
import io.github.ogshrug.flatdocscanner.scan.PageDetector
import io.github.ogshrug.flatdocscanner.scan.PageFilter
import io.github.ogshrug.flatdocscanner.scan.PageMode
import io.github.ogshrug.flatdocscanner.scan.PageSpec
import io.github.ogshrug.flatdocscanner.scan.Pt
import io.github.ogshrug.flatdocscanner.scan.Quad
import io.github.ogshrug.flatdocscanner.scan.ScanPipeline
import io.github.ogshrug.flatdocscanner.util.Bitmaps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.nio.ByteBuffer

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

    /** The live detector reuses its working memory between frames; a second call must agree with the first. */
    @Test
    fun detectsTheSamePageTwice() {
        val photo = photo()
        val first = PageDetector.detect(photo)
        val second = PageDetector.detect(photo)
        assertNotNull("no page found", first)
        assertEquals(first, second)
    }

    /** The crop screen re-runs the finishing step on one geometry bitmap, so it must survive every look. */
    @Test
    fun finishingLeavesItsInputAlone() {
        val geometry = ImageProcessor.warp(photo(), truth)
        val before = geometry.getPixel(geometry.width / 2, geometry.height / 2)
        val looks = listOf(
            PageSpec(null, PageMode.FLAT, filter = PageFilter.ORIGINAL, removeFingers = false),
            PageSpec(null, PageMode.FLAT, filter = PageFilter.COLOR, removeFingers = false),
            PageSpec(null, PageMode.FLAT, filter = PageFilter.BW, removeFingers = true),
            PageSpec(null, PageMode.FLAT, filter = PageFilter.ORIGINAL, removeFingers = false, rotation = 90),
            PageSpec(null, PageMode.FLAT, filter = PageFilter.GRAY, removeFingers = true, rotation = 270),
        )
        for (spec in looks) {
            val out = ScanPipeline.finish(geometry, spec)
            assertNotSame("finish returned its input for $spec", geometry, out)
            assertFalse("finish recycled its input for $spec", geometry.isRecycled)
            assertEquals(before, geometry.getPixel(geometry.width / 2, geometry.height / 2))
            val turned = spec.rotation % 180 != 0
            assertEquals(if (turned) geometry.height else geometry.width, out.width)
            assertEquals(if (turned) geometry.width else geometry.height, out.height)
            out.recycle()
        }
    }

    /**
     * Camera frames are read in place from native memory whose rows are padded; the padding
     * must not leak into the picture.
     */
    @Test
    fun readsPaddedFrameRowsInPlace() {
        val w = 6
        val h = 4
        val stride = w * 4 + 12
        val frame = ByteBuffer.allocateDirect(stride * h)
        for (i in 0 until stride * h) frame.put(i, 0x7F)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
            frame.put(y * stride + x * 4 + c, (y * 40 + x * 5 + c).toByte())
        }
        val wrapped = Mat(h, w, CvType.CV_8UC4, frame, stride.toLong())
        val upright = Mat()
        Core.rotate(wrapped, upright, Core.ROTATE_90_CLOCKWISE)
        wrapped.release()
        assertEquals(w, upright.rows())
        assertEquals(h, upright.cols())
        val px = ByteArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            // Turned a quarter clockwise, (x, y) lands at row x, column h - 1 - y.
            upright.get(x, h - 1 - y, px)
            for (c in 0 until 4) assertEquals("pixel $x,$y channel $c", (y * 40 + x * 5 + c).toByte(), px[c])
        }
        upright.release()
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
