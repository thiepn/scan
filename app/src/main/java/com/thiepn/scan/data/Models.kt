package com.thiepn.scan.data

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
    val ocrText: String = ""
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
    val sortKey: Long = (position + 1L) * 1000L,
    val deleted: Boolean = false,
    val imagePath: String,
    val width: Int,
    val height: Int,
    val ocrText: String = ""
)

enum class LibraryFilter { ACTIVE, FAVORITES, ARCHIVED, TRASH }
