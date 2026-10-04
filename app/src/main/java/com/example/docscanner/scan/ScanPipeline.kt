package com.example.docscanner.scan

import android.graphics.Bitmap
import com.example.docscanner.data.PageEntity
import com.example.docscanner.data.ScanRepository
import com.example.docscanner.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photo → finished page. Rendering is split in two so the crop screen can redo only the
 * cheap part when the filter or finger setting changes:
 *  1. [geometry]: flat perspective crop, curved-page flattening, or one half of a book spread
 *  2. [finish]: gutter shadow, finger removal, filter
 */
object ScanPipeline {

    /** Long side of the photo used for detection and cropping. */
    const val WORKING_MAX_DIM = 3000

    /** Long side of a curved-page result; the remap needs two float maps of this size. */
    private const val CURVED_MAX_DIM = 2400

    val DEFAULT_FILTER = PageFilter.COLOR

    fun geometry(photo: Bitmap, spec: PageSpec): Bitmap {
        val w = photo.width
        val h = photo.height
        return when {
            spec.side != PageSide.NONE -> {
                val half = ImageProcessor.halfPage(photo, spec.quad, spec.spine ?: Spine(0.5, 0.5), spec.side)
                Dewarper.flatten(half, CURVED_MAX_DIM).also { half.recycle() }
            }
            spec.mode == PageMode.CURVED -> {
                val crop = ImageProcessor.cropAround(photo, spec.quad)
                Dewarper.flatten(crop, CURVED_MAX_DIM).also { if (crop !== photo) crop.recycle() }
            }
            else -> ImageProcessor.warp(photo, spec.quad?.denormalized(w, h) ?: Quad.inset(w, h, 0.0))
        }
    }

    fun finish(geometry: Bitmap, spec: PageSpec): Bitmap {
        var page = ImageProcessor.correctGutter(geometry, spec.side)
        if (spec.removeFingers) {
            val clean = FingerRemover.remove(page)
            page.recycle()
            page = clean
        }
        val filtered = ImageProcessor.applyFilter(page, spec.filter).also { page.recycle() }
        return ImageProcessor.rotate(filtered, spec.rotation).also { if (it !== filtered) filtered.recycle() }
    }

    fun render(photo: Bitmap, spec: PageSpec): Bitmap {
        val g = geometry(photo, spec)
        return finish(g, spec).also { g.recycle() }
    }

    /**
     * A single page is cropped flat from its detected corners. With no outline at all (the page
     * fills the frame, or nothing was found) the curved-page model flattens the whole photo.
     */
    fun specForSinglePage(photo: Bitmap, liveQuad: Quad?): PageSpec {
        val w = photo.width
        val h = photo.height
        val quad = PageDetector.detect(photo)?.normalized(w, h) ?: liveQuad?.takeIf { it.isConvex() }
        return if (quad != null) PageSpec(quad, PageMode.FLAT) else PageSpec(null, PageMode.CURVED)
    }

    /** The two pages of an open book: the spread's outline and its gutter. */
    fun specsForSpread(photo: Bitmap, liveQuad: Quad?): List<PageSpec> {
        val w = photo.width
        val h = photo.height
        val spread = PageDetector.detect(photo)?.normalized(w, h) ?: liveQuad
        val spine = SpineDetector.detect(photo, spread) ?: spread?.let { Spine.middleOf(it) } ?: Spine(0.5, 0.5)
        return listOf(PageSide.LEFT, PageSide.RIGHT).map {
            PageSpec(spread, PageMode.CURVED, it, spine)
        }
    }

    /** Saves one auto-captured page. Returns the document id. */
    suspend fun savePage(repository: ScanRepository, capture: File, documentId: Long?, liveQuad: Quad?): Long {
        val (spec, page) = withContext(Dispatchers.Default) {
            val photo = Bitmaps.decode(capture, WORKING_MAX_DIM) ?: error("could not decode capture")
            try {
                val spec = specForSinglePage(photo, liveQuad)
                spec to render(photo, spec)
            } finally {
                photo.recycle()
            }
        }
        try {
            return repository.addPage(documentId, capture, spec, page)
        } finally {
            page.recycle()
        }
    }

    /** Saves both pages of a book spread photo, left then right. Returns the document id. */
    suspend fun saveSpread(
        repository: ScanRepository,
        capture: File,
        documentId: Long?,
        liveQuad: Quad?,
        leftPageFirst: Boolean = true,
    ): Long {
        val rendered = withContext(Dispatchers.Default) {
            val photo = Bitmaps.decode(capture, WORKING_MAX_DIM) ?: error("could not decode capture")
            try {
                // Right-to-left books (Arabic, Hebrew, Japanese...) read the right page first.
                specsForSpread(photo, liveQuad).let { if (leftPageFirst) it else it.reversed() }.map { it to render(photo, it) }
            } finally {
                photo.recycle()
            }
        }
        // Each page keeps its own copy of the photo, so either can be re-edited or deleted alone.
        val second = File(capture.parentFile, "${capture.nameWithoutExtension}_r.jpg")
        withContext(Dispatchers.IO) { capture.copyTo(second, overwrite = true) }
        var docId = documentId
        rendered.forEachIndexed { i, (spec, page) ->
            try {
                docId = repository.addPage(docId, if (i == 0) capture else second, spec, page)
            } finally {
                page.recycle()
            }
        }
        return docId!!
    }

    fun specOf(page: PageEntity) = PageSpec(
        quad = Quad.deserialize(page.corners),
        mode = runCatching { PageMode.valueOf(page.mode) }.getOrDefault(PageMode.FLAT),
        side = runCatching { PageSide.valueOf(page.side) }.getOrDefault(PageSide.NONE),
        spine = Spine.deserialize(page.spine),
        filter = runCatching { PageFilter.valueOf(page.filter) }.getOrDefault(DEFAULT_FILTER),
        removeFingers = page.removeFingers,
        rotation = page.rotation,
    )
}
