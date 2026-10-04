package com.example.docscanner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("document_id", "position")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "document_id") val documentId: Long,
    val position: Int,
    /** The photo as captured, kept so corners and filter can be changed later. */
    @ColumnInfo(name = "original_path") val originalPath: String,
    /** The cropped, flattened and filtered page that is shown and exported. */
    @ColumnInfo(name = "processed_path") val processedPath: String,
    /** Corners as 0..1 fractions of the original image, see [com.example.docscanner.scan.Quad.normalized]. */
    val corners: String,
    val filter: String,
    /** [com.example.docscanner.scan.PageMode] name. */
    @ColumnInfo(defaultValue = "FLAT") val mode: String = "FLAT",
    /** [com.example.docscanner.scan.PageSide] name; LEFT or RIGHT for a page cut from a book spread. */
    @ColumnInfo(defaultValue = "NONE") val side: String = "NONE",
    /** Book gutter, see [com.example.docscanner.scan.Spine.serialize]. */
    val spine: String? = null,
    @ColumnInfo(name = "remove_fingers", defaultValue = "0") val removeFingers: Boolean = false,
    /** Recognised text, only if the user asked for it; see [com.example.docscanner.ocr.PageTextCodec]. */
    val ocr: String? = null,
)

data class DocumentSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
)
