package com.example.docscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Query(
        """
        SELECT d.id, d.title, d.updated_at,
               (SELECT COUNT(*) FROM pages p WHERE p.document_id = d.id) AS page_count,
               (SELECT p.processed_path FROM pages p WHERE p.document_id = d.id
                ORDER BY p.position LIMIT 1) AS cover_path
        FROM documents d
        ORDER BY d.updated_at DESC
        """,
    )
    fun observeDocuments(): Flow<List<DocumentSummary>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Query("SELECT * FROM pages WHERE document_id = :documentId ORDER BY position")
    fun observePages(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE document_id = :documentId ORDER BY position")
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

    @Query("UPDATE pages SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Transaction
    suspend fun reorder(pageIdsInOrder: List<Long>) {
        pageIdsInOrder.forEachIndexed { index, id -> setPosition(id, index) }
    }

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)
}
