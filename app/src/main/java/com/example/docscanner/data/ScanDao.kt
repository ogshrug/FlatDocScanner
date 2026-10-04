package com.example.docscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Deleted documents and pages stay in the database with a deleted_at time (the Trash) until
 * they are restored or purged; every normal query leaves them out.
 */
@Dao
interface ScanDao {

    @Query(
        """
        SELECT d.id, d.title, d.created_at, d.updated_at, d.deleted_at,
               (SELECT COUNT(*) FROM pages p WHERE p.document_id = d.id AND p.deleted_at IS NULL) AS page_count,
               COALESCE(
                   (SELECT p.processed_path FROM pages p WHERE p.id = d.cover_page_id AND p.deleted_at IS NULL),
                   (SELECT p.processed_path FROM pages p WHERE p.document_id = d.id AND p.deleted_at IS NULL
                    ORDER BY p.position LIMIT 1)
               ) AS cover_path
        FROM documents d
        WHERE d.deleted_at IS NULL
        ORDER BY d.updated_at DESC
        """,
    )
    fun observeDocuments(): Flow<List<DocumentSummary>>

    @Query(
        """
        SELECT d.id, d.title, d.created_at, d.updated_at, d.deleted_at,
               (SELECT COUNT(*) FROM pages p WHERE p.document_id = d.id AND p.deleted_at IS NULL) AS page_count,
               (SELECT p.processed_path FROM pages p WHERE p.document_id = d.id AND p.deleted_at IS NULL
                ORDER BY p.position LIMIT 1) AS cover_path
        FROM documents d
        WHERE d.deleted_at IS NOT NULL
        ORDER BY d.deleted_at DESC
        """,
    )
    fun observeTrashedDocuments(): Flow<List<DocumentSummary>>

    @Query(
        """
        SELECT p.*, d.title AS document_title FROM pages p JOIN documents d ON d.id = p.document_id
        WHERE p.deleted_at IS NOT NULL AND d.deleted_at IS NULL
        ORDER BY p.deleted_at DESC
        """,
    )
    fun observeTrashedPages(): Flow<List<TrashedPage>>

    @Query("SELECT * FROM documents WHERE id = :id AND deleted_at IS NULL")
    fun observeDocument(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun getDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM pages WHERE document_id = :documentId AND deleted_at IS NULL ORDER BY position")
    fun observePages(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE document_id = :documentId AND deleted_at IS NULL ORDER BY position")
    suspend fun getPages(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: Long): PageEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM pages WHERE document_id = :documentId")
    suspend fun nextPosition(documentId: Long): Int

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Insert
    suspend fun insertPage(page: PageEntity): Long

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("UPDATE documents SET title = :title, updated_at = :now WHERE id = :id")
    suspend fun renameDocument(id: Long, title: String, now: Long)

    @Query("UPDATE documents SET updated_at = :now WHERE id = :id")
    suspend fun touchDocument(id: Long, now: Long)

    @Query("UPDATE documents SET cover_page_id = :pageId WHERE id = :id")
    suspend fun setCover(id: Long, pageId: Long?)

    @Query("UPDATE pages SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Transaction
    suspend fun reorder(pageIdsInOrder: List<Long>) {
        pageIdsInOrder.forEachIndexed { index, id -> setPosition(id, index) }
    }

    @Query("UPDATE pages SET document_id = :documentId, position = :position WHERE id = :id")
    suspend fun movePage(id: Long, documentId: Long, position: Int)

    @Query("UPDATE pages SET ocr = :ocr WHERE id = :id")
    suspend fun setOcr(id: Long, ocr: String?)

    @Query("UPDATE pages SET ocr = NULL WHERE document_id = :documentId")
    suspend fun clearOcr(documentId: Long)

    // --- trash ---------------------------------------------------------------------------

    @Query("UPDATE pages SET deleted_at = :at WHERE id = :id")
    suspend fun setPageDeleted(id: Long, at: Long?)

    @Query("UPDATE documents SET deleted_at = :at WHERE id = :id")
    suspend fun setDocumentDeleted(id: Long, at: Long?)

    @Query("SELECT * FROM pages WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun pagesTrashedBefore(before: Long): List<PageEntity>

    @Query("SELECT id FROM documents WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun documentsTrashedBefore(before: Long): List<Long>

    @Query("SELECT * FROM pages WHERE deleted_at IS NOT NULL")
    suspend fun allTrashedPages(): List<PageEntity>

    @Query("SELECT id FROM documents WHERE deleted_at IS NOT NULL")
    suspend fun allTrashedDocuments(): List<Long>

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)
}
