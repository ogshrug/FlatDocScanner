package com.example.docscanner.data

import android.content.Context
import android.graphics.Bitmap
import com.example.docscanner.scan.PageSpec
import com.example.docscanner.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Owns the database and the page image files under `files/documents/<id>/`. */
class ScanRepository(context: Context, private val dao: ScanDao) {

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
        docId
    }

    /** Re-processes an existing page with new settings. Writes a new file so thumbnails refresh. */
    suspend fun updatePage(page: PageEntity, spec: PageSpec, processed: Bitmap) =
        withContext(Dispatchers.IO) {
            val newFile = File(File(page.processedPath).parentFile, "page_${UUID.randomUUID()}.jpg")
            Bitmaps.writeJpeg(processed, newFile)
            dao.updatePage(page.copy(processedPath = newFile.path).withSpec(spec))
            File(page.processedPath).delete()
            dao.touchDocument(page.documentId, System.currentTimeMillis())
        }

    suspend fun reorder(documentId: Long, pageIdsInOrder: List<Long>) {
        dao.reorder(pageIdsInOrder)
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    suspend fun renameDocument(id: Long, title: String) =
        dao.renameDocument(id, title, System.currentTimeMillis())

    /** Deletes a page; deletes the whole document when it was the last page. */
    suspend fun deletePage(page: PageEntity) = withContext(Dispatchers.IO) {
        dao.deletePage(page.id)
        File(page.originalPath).delete()
        File(page.processedPath).delete()
        val remaining = dao.getPages(page.documentId)
        if (remaining.isEmpty()) {
            deleteDocument(page.documentId)
        } else {
            dao.reorder(remaining.map { it.id })
            dao.touchDocument(page.documentId, System.currentTimeMillis())
        }
    }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteDocument(id)
        File(documentsDir, id.toString()).deleteRecursively()
    }

    private fun PageEntity.withSpec(spec: PageSpec) = copy(
        corners = spec.quad?.serialize() ?: "",
        filter = spec.filter.name,
        mode = spec.mode.name,
        side = spec.side.name,
        spine = spec.spine?.serialize(),
        removeFingers = spec.removeFingers,
    )

    private fun defaultTitle(now: Long) =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date(now))
}
