package com.thiepn.scan.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index("trashedAt"),
        Index("folderId"),
        Index("documentType"),
        Index("needsReview"),
        Index("scanMode")
    ]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pdfPath: String?,
    val pageCount: Int,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val trashedAt: Long? = null,
    val processing: Boolean = false,
    val ocrText: String = "",
    @ColumnInfo(defaultValue = "'LATIN'")
    val ocrScript: String = OcrScript.LATIN.name,
    val folderId: String? = null,
    @ColumnInfo(defaultValue = "'UNSPECIFIED'")
    val documentType: String = DocumentType.UNSPECIFIED.name,
    val suggestedType: String? = null,
    @ColumnInfo(defaultValue = "0")
    val needsReview: Boolean = false,
    @ColumnInfo(defaultValue = "'DOCUMENT'")
    val scanMode: String = ScanMode.DOCUMENT.name
)

@Entity(
    tableName = "folders",
    indices = [Index("parentId"), Index(value = ["parentId", "normalizedName"])]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val createdAt: Long
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId"), Index("tagId")]
)
data class DocumentTagCrossRef(
    val documentId: String,
    val tagId: String
)

@Entity(
    tableName = "document_fields",
    primaryKeys = ["documentId", "fieldKey"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class DocumentFieldEntity(
    val documentId: String,
    val fieldKey: String,
    val label: String,
    val value: String,
    val confidence: Float,
    val source: String = "OCR"
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("documentId"),
        Index(value = ["documentId", "position"], unique = true),
        Index(value = ["documentId", "deleted", "sortKey"])
    ]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val position: Int,
    @ColumnInfo(defaultValue = "0")
    val sortKey: Long = (position + 1L) * 1000L,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val rotationDegrees: Int = 0,
    val cropQuad: String? = null,
    val visualRecipe: String? = null,
    val imagePath: String,
    val width: Int,
    val height: Int,
    val ocrText: String = "",
    val ocrLayout: String? = null,
    val ocrFingerprint: String? = null,
    val ocrScript: String? = null,
    val sourceSpreadPageId: String? = null,
    val bookSide: String? = null,
    val bookSplitConfidence: Float? = null,
    @ColumnInfo(defaultValue = "0")
    val bookDewarpStrength: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    val preservedBookSource: Boolean = false
)

enum class LibraryFilter { ACTIVE, FAVORITES, ARCHIVED, TRASH }

enum class ScanMode(val label: String) {
    DOCUMENT("Document"),
    RECEIPT("Receipt"),
    ID_CARD("ID Card"),
    BUSINESS_CARD("Business Card"),
    BOOK("Book"),
    WHITEBOARD("Whiteboard"),
    FORM("Form"),
    PHOTO("Photo"),
    NOTES("Notes"),
    CERTIFICATE("Certificate");

    companion object {
        fun fromStored(value: String?): ScanMode =
            entries.firstOrNull { it.name == value } ?: DOCUMENT
    }
}

enum class BookPageSide {
    LEFT,
    RIGHT
}

enum class DocumentType(val label: String) {
    UNSPECIFIED("Unspecified"),
    RECEIPT("Receipt"),
    INVOICE("Invoice"),
    ID("ID"),
    FORM("Form"),
    NOTES("Notes"),
    LETTER("Letter"),
    BUSINESS_CARD("Business card"),
    BOOK("Book"),
    WHITEBOARD("Whiteboard"),
    CERTIFICATE("Certificate");

    companion object {
        fun fromStored(value: String?): DocumentType =
            entries.firstOrNull { it.name == value } ?: UNSPECIFIED
    }
}

enum class SmartCollection(val label: String) {
    ALL("All"),
    RECENT("Recent"),
    UNFILED("Unfiled"),
    NEEDS_REVIEW("Needs review")
}

enum class LibrarySort(val label: String) {
    RELEVANCE("Relevance"),
    UPDATED_DESC("Recently modified"),
    CREATED_DESC("Recently created"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    PAGE_COUNT_DESC("Most pages"),
    PAGE_COUNT_ASC("Fewest pages")
}
