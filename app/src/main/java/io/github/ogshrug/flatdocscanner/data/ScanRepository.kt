package io.github.ogshrug.flatdocscanner.data

import android.content.Context
import android.graphics.Bitmap
import io.github.ogshrug.flatdocscanner.AppSettings
import io.github.ogshrug.flatdocscanner.export.GalleryExporter
import io.github.ogshrug.flatdocscanner.ocr.PageText
import io.github.ogshrug.flatdocscanner.ocr.PageTextCodec
import io.github.ogshrug.flatdocscanner.ocr.Script
import io.github.ogshrug.flatdocscanner.ocr.TextRecognizer
import io.github.ogshrug.flatdocscanner.scan.PageSpec
import io.github.ogshrug.flatdocscanner.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Owns the database and the page image files under `files/documents/<id>/`. */
class ScanRepository(context: Context, private val dao: ScanDao) {

    private val appContext = context.applicationContext

    private val documentsDir = File(context.filesDir, "documents")

    fun observeDocuments() = dao.observeDocuments()
    fun observeDocument(id: Long) = dao.observeDocument(id)
    fun observePages(documentId: Long) = dao.observePages(documentId)

    suspend fun getDocument(id: Long) = dao.getDocument(id)
    suspend fun getPages(documentId: Long) = dao.getPages(documentId)
    suspend fun getPage(id: Long) = dao.getPage(id)

    /**
     * Saves a new page. Creates the document first when [documentId] is null, so an
     * abandoned scan never leaves an empty document behind. Returns the document id.
     */
    suspend fun addPage(
        documentId: Long?,
        capture: File,
        spec: PageSpec,
        processed: Bitmap,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val docId = documentId ?: dao.insertDocument(
            DocumentEntity(title = defaultTitle(now), createdAt = now, updatedAt = now),
        )
        val dir = File(documentsDir, docId.toString()).apply { mkdirs() }
        val original = File(dir, "orig_${UUID.randomUUID()}.jpg")
        if (!capture.renameTo(original)) {
            capture.copyTo(original, overwrite = true)
            capture.delete()
        }
        val processedFile = File(dir, "page_${UUID.randomUUID()}.jpg")
        Bitmaps.writeJpeg(processed, processedFile)
        dao.insertPage(
            PageEntity(
                documentId = docId,
                position = dao.nextPosition(docId),
                originalPath = original.path,
                processedPath = processedFile.path,
                corners = "",
                filter = "",
            ).withSpec(spec),
        )
        dao.touchDocument(docId, now)
        if (AppSettings(appContext).saveToGallery) copyToGallery(processedFile)
        docId
    }

    /** Re-processes an existing page with new settings. Writes a new file so thumbnails refresh. */
    suspend fun updatePage(page: PageEntity, spec: PageSpec, processed: Bitmap) =
        withContext(Dispatchers.IO) {
            val newFile = File(File(page.processedPath).parentFile, "page_${UUID.randomUUID()}.jpg")
            Bitmaps.writeJpeg(processed, newFile)
            // The page image changed, so any recognised text no longer matches it.
            dao.updatePage(page.copy(processedPath = newFile.path, ocr = null).withSpec(spec))
            File(page.processedPath).delete()
            dao.touchDocument(page.documentId, System.currentTimeMillis())
        }

    /** Recognises the text on one page (the user asked for it) and stores it. */
    suspend fun recognizeText(page: PageEntity, script: Script): PageText {
        val text = withContext(Dispatchers.Default) {
            val bmp = Bitmaps.decode(File(page.processedPath), OCR_MAX_DIM) ?: error("could not read page")
            try {
                TextRecognizer.recognize(bmp, script)
            } finally {
                bmp.recycle()
            }
        }
        dao.setOcr(page.id, PageTextCodec.encode(text))
        return text
    }

    suspend fun removeText(pageId: Long) = dao.setOcr(pageId, null)

    /**
     * Turns a page a quarter clockwise. The finished image is rotated directly (no re-processing)
     * and the rotation is stored, so re-cropping later keeps it. Recognised text is cleared
     * because its positions no longer match.
     */
    suspend fun rotatePage(page: PageEntity, degrees: Int = 90) = withContext(Dispatchers.IO) {
        val src = File(page.processedPath)
        val bmp = android.graphics.BitmapFactory.decodeFile(src.path) ?: error("could not read page")
        val turned = io.github.ogshrug.flatdocscanner.scan.ImageProcessor.rotate(bmp, degrees)
        val newFile = File(src.parentFile, "page_${UUID.randomUUID()}.jpg")
        Bitmaps.writeJpeg(turned, newFile)
        bmp.recycle()
        turned.recycle()
        dao.updatePage(page.copy(processedPath = newFile.path, rotation = (page.rotation + degrees + 360) % 360, ocr = null))
        src.delete()
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    suspend fun removeAllText(documentId: Long) = dao.clearOcr(documentId)

    suspend fun reorder(documentId: Long, pageIdsInOrder: List<Long>) {
        dao.reorder(pageIdsInOrder)
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    suspend fun renameDocument(id: Long, title: String) =
        dao.renameDocument(id, title, System.currentTimeMillis())

    /** "Save scans to gallery" setting: a copy of each new page. Skipped where permission is missing. */
    private fun copyToGallery(page: File) {
        val allowed = !GalleryExporter.needsPermission() ||
            appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { GalleryExporter.save(appContext, listOf("scan_${System.currentTimeMillis()}.jpg" to page)) }
    }

    // --- folders -----------------------------------------------------------------------

    fun observeTrashedDocuments() = dao.observeTrashedDocuments()
    fun observeTrashedPages() = dao.observeTrashedPages()
    suspend fun getDocuments() = dao.getDocuments()

    /** An empty folder to scan or import into. */
    suspend fun createDocument(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun setCover(documentId: Long, pageId: Long) = dao.setCover(documentId, pageId)

    /** Reverses the page order, e.g. for a book scanned back to front. */
    suspend fun reversePages(documentId: Long) = reorder(documentId, dao.getPages(documentId).map { it.id }.reversed())

    /** Moves pages to the end of another folder, keeping their order. */
    suspend fun movePages(pages: List<PageEntity>, targetDocumentId: Long) {
        val now = System.currentTimeMillis()
        var pos = dao.nextPosition(targetDocumentId)
        pages.forEach { dao.movePage(it.id, targetDocumentId, pos++) }
        pages.map { it.documentId }.distinct().forEach { source ->
            dao.reorder(dao.getPages(source).map { it.id })
            dao.touchDocument(source, now)
        }
        dao.touchDocument(targetDocumentId, now)
    }

    // --- trash -------------------------------------------------------------------------

    /** Moves a page to the Trash. The folder stays, even when empty. */
    suspend fun deletePage(page: PageEntity) {
        dao.setPageDeleted(page.id, System.currentTimeMillis())
        dao.reorder(dao.getPages(page.documentId).map { it.id })
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    /** Moves several pages to the Trash at once; each folder is renumbered once, not once per page. */
    suspend fun deletePages(pages: List<PageEntity>) {
        val now = System.currentTimeMillis()
        pages.forEach { dao.setPageDeleted(it.id, now) }
        pages.map { it.documentId }.distinct().forEach { doc ->
            dao.reorder(dao.getPages(doc).map { it.id })
            dao.touchDocument(doc, now)
        }
    }

    /**
     * Undoes [deletePages] right after it: the pages come back where they were. [orderBefore] is
     * the folder's page ids as they stood before the deletion; pages added since go after them.
     */
    suspend fun undoDeletePages(documentId: Long, pages: List<PageEntity>, orderBefore: List<Long>) {
        pages.forEach { dao.setPageDeleted(it.id, null) }
        val present = dao.getPages(documentId).map { it.id }
        val known = orderBefore.toSet()
        val presentSet = present.toSet()
        dao.reorder(orderBefore.filter { it in presentSet } + present.filter { it !in known })
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    /** Moves a folder (with its pages) to the Trash. */
    suspend fun deleteDocument(id: Long) = dao.setDocumentDeleted(id, System.currentTimeMillis())

    /** Restores a page to the end of its folder. */
    suspend fun restorePage(page: PageEntity) {
        dao.movePage(page.id, page.documentId, dao.nextPosition(page.documentId))
        dao.setPageDeleted(page.id, null)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    suspend fun restoreDocument(id: Long) = dao.setDocumentDeleted(id, null)

    suspend fun purgePage(page: PageEntity) = withContext(Dispatchers.IO) {
        dao.deletePage(page.id)
        File(page.originalPath).delete()
        File(page.processedPath).delete()
    }

    suspend fun purgeDocument(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteDocument(id)
        File(documentsDir, id.toString()).deleteRecursively()
    }

    suspend fun emptyTrash() {
        dao.allTrashedPages().forEach { purgePage(it) }
        dao.allTrashedDocuments().forEach { purgeDocument(it) }
    }

    /** Deletes for good whatever has been in the Trash longer than [TRASH_DAYS]. */
    suspend fun purgeOldTrash() {
        val before = System.currentTimeMillis() - TRASH_DAYS * 24L * 60 * 60 * 1000
        dao.pagesTrashedBefore(before).forEach { purgePage(it) }
        dao.documentsTrashedBefore(before).forEach { purgeDocument(it) }
    }

    private fun PageEntity.withSpec(spec: PageSpec) = copy(
        corners = spec.quad?.serialize() ?: "",
        filter = spec.filter.name,
        mode = spec.mode.name,
        side = spec.side.name,
        spine = spec.spine?.serialize(),
        removeFingers = spec.removeFingers,
        rotation = spec.rotation,
    )

    companion object {
        /** ML Kit is accurate well below the full page size, and faster. */
        private const val OCR_MAX_DIM = 2400

        /** Days an item stays in the Trash before it is deleted for good. */
        const val TRASH_DAYS = 30
    }

    fun defaultTitle(now: Long = System.currentTimeMillis()) =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date(now))
}
