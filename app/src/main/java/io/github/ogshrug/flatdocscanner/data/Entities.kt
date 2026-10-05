package io.github.ogshrug.flatdocscanner.data

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
    /** Page shown on the folder card; null = the first page. */
    @ColumnInfo(name = "cover_page_id") val coverPageId: Long? = null,
    /** Set while the document is in the Trash. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
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
    /** Corners as 0..1 fractions of the original image, see [io.github.ogshrug.flatdocscanner.scan.Quad.normalized]. */
    val corners: String,
    val filter: String,
    /** [io.github.ogshrug.flatdocscanner.scan.PageMode] name. */
    @ColumnInfo(defaultValue = "FLAT") val mode: String = "FLAT",
    /** [io.github.ogshrug.flatdocscanner.scan.PageSide] name; LEFT or RIGHT for a page cut from a book spread. */
    @ColumnInfo(defaultValue = "NONE") val side: String = "NONE",
    /** Book gutter, see [io.github.ogshrug.flatdocscanner.scan.Spine.serialize]. */
    val spine: String? = null,
    @ColumnInfo(name = "remove_fingers", defaultValue = "0") val removeFingers: Boolean = false,
    /** Recognised text, only if the user asked for it; see [io.github.ogshrug.flatdocscanner.ocr.PageTextCodec]. */
    val ocr: String? = null,
    /** Clockwise rotation of the finished page, see [io.github.ogshrug.flatdocscanner.scan.PageSpec.rotation]. */
    @ColumnInfo(defaultValue = "0") val rotation: Int = 0,
    /** Set while the page is in the Trash. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

data class DocumentSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
)

/** A page in the Trash, with the title of the document it came from. */
data class TrashedPage(
    @androidx.room.Embedded val page: PageEntity,
    @ColumnInfo(name = "document_title") val documentTitle: String,
)
