package io.github.ogshrug.flatdocscanner.export

import io.github.ogshrug.flatdocscanner.data.PageEntity
import java.io.File

/** Prepares page images for sharing, named after the document and page number. */
object ImageExporter {

    /** Copies each page's JPEG into [dir] as "<title> - page <n>.jpg"; [numbers] are the page numbers. */
    fun copyForSharing(pages: List<PageEntity>, numbers: List<Int>, dir: File, title: String): List<File> {
        require(pages.size == numbers.size)
        dir.mkdirs()
        return pages.mapIndexed { i, page ->
            val out = File(dir, "$title - page ${numbers[i]}.jpg")
            File(page.processedPath).copyTo(out, overwrite = true)
        }
    }
}
