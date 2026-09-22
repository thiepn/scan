package com.thiepn.scan.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents", indices = [Index("trashedAt")])
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
    val ocrScript: String = OcrScript.LATIN.name
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
    val ocrScript: String? = null
)

enum class LibraryFilter { ACTIVE, FAVORITES, ARCHIVED, TRASH }
