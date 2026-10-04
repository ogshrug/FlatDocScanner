package com.example.docscanner.scan

import com.example.docscanner.data.ScanRepository
import com.example.docscanner.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Turns a captured photo into a saved page without user input (used by auto-capture). */
object ScanPipeline {

    /** Long side of the image used for detection, cropping and the saved page. */
    const val WORKING_MAX_DIM = 3000

    val DEFAULT_FILTER = PageFilter.COLOR

    /**
     * Detects the page in the full-resolution photo, falling back to [liveQuad] (the outline
     * seen in the preview, as 0..1 fractions) and then to the whole image. Returns the document id.
     */
    suspend fun savePage(
        repository: ScanRepository,
        capture: File,
        documentId: Long?,
        liveQuad: Quad?,
    ): Long {
        val (quad, page) = withContext(Dispatchers.Default) {
            val bitmap = Bitmaps.decode(capture, WORKING_MAX_DIM) ?: error("could not decode capture")
            try {
                val w = bitmap.width
                val h = bitmap.height
                val corners = PageDetector.detect(bitmap)
                    ?: liveQuad?.denormalized(w, h)?.clampedTo(w, h)?.takeIf { it.isConvex() }
                    ?: Quad.inset(w, h, 0.0)
                val warped = ImageProcessor.warp(bitmap, corners)
                val filtered = ImageProcessor.applyFilter(warped, DEFAULT_FILTER)
                warped.recycle()
                corners.normalized(w, h) to filtered
            } finally {
                bitmap.recycle()
            }
        }
        try {
            return repository.addPage(documentId, capture, quad, DEFAULT_FILTER, page)
        } finally {
            page.recycle()
        }
    }
}
