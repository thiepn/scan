package com.thiepn.scan.data

import androidx.room.support.getSupportWrapper
import androidx.sqlite.db.SimpleSQLiteQuery

data class IndexedPageHit(
    val pageId: String,
    val snippet: String,
    val rank: Double
)

data class DocumentPageSearchHit(
    val pageId: String,
    val pageNumber: Int,
    val snippet: String,
    val rank: Double,
    val matchingWords: List<OcrWordBox>
)

class OcrSearchIndex(
    private val database: ScanDatabase
) {
    fun rebuildAll() {
        val db = database.getSupportWrapper()
        ensureSchema()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM ocr_pages_fts")
            db.execSQL(
                """
                INSERT INTO ocr_pages_fts(documentId, pageId, content)
                SELECT documentId, id, ocrText
                FROM pages
                WHERE deleted = 0 AND TRIM(ocrText) <> ''
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertPage(
        documentId: String,
        pageId: String,
        content: String,
        deleted: Boolean = false
    ) {
        val db = database.getSupportWrapper()
        ensureSchema()
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM ocr_pages_fts WHERE pageId = ?",
                arrayOf(pageId)
            )
            if (!deleted && content.isNotBlank()) {
                db.execSQL(
                    "INSERT INTO ocr_pages_fts(documentId, pageId, content) VALUES (?, ?, ?)",
                    arrayOf(documentId, pageId, content)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deletePage(pageId: String) {
        val db = database.getSupportWrapper()
        ensureSchema()
        db.execSQL(
            "DELETE FROM ocr_pages_fts WHERE pageId = ?",
            arrayOf(pageId)
        )
    }

    fun deleteDocument(documentId: String) {
        val db = database.getSupportWrapper()
        ensureSchema()
        db.execSQL(
            "DELETE FROM ocr_pages_fts WHERE documentId = ?",
            arrayOf(documentId)
        )
    }

    fun searchDocumentIds(
        filter: LibraryFilter,
        rawQuery: String
    ): List<String> {
        val input = rawQuery.trim()
        if (input.isBlank()) return emptyList()
        val db = database.getSupportWrapper()
        ensureSchema()

        val ftsQuery = OcrSearchTerms.buildFtsQuery(input)
        val titleLike = "%$input%"
        val filterClause = when (filter) {
            LibraryFilter.ACTIVE -> "d.archived = 0 AND d.trashedAt IS NULL"
            LibraryFilter.FAVORITES ->
                "d.archived = 0 AND d.favorite = 1 AND d.trashedAt IS NULL"
            LibraryFilter.ARCHIVED -> "d.archived = 1 AND d.trashedAt IS NULL"
            LibraryFilter.TRASH -> "d.trashedAt IS NOT NULL"
        }

        val query = if (ftsQuery != null) {
            SimpleSQLiteQuery(
                """
                SELECT id
                FROM (
                    SELECT
                        d.id AS id,
                        -1000.0 AS rank,
                        d.favorite AS favorite,
                        d.updatedAt AS updatedAt
                    FROM documents d
                    WHERE $filterClause
                      AND LOWER(d.title) LIKE LOWER(?)

                    UNION ALL

                    SELECT
                        d.id AS id,
                        bm25(ocr_pages_fts) AS rank,
                        d.favorite AS favorite,
                        d.updatedAt AS updatedAt
                    FROM ocr_pages_fts
                    JOIN documents d ON d.id = ocr_pages_fts.documentId
                    WHERE $filterClause
                      AND ocr_pages_fts MATCH ?
                )
                ORDER BY rank ASC, favorite DESC, updatedAt DESC
                """.trimIndent(),
                arrayOf(titleLike, ftsQuery)
            )
        } else {
            SimpleSQLiteQuery(
                """
                SELECT d.id
                FROM documents d
                WHERE $filterClause
                  AND LOWER(d.title) LIKE LOWER(?)
                ORDER BY d.favorite DESC, d.updatedAt DESC
                """.trimIndent(),
                arrayOf(titleLike)
            )
        }

        return db.query(query).use { cursor ->
            val seen = linkedSetOf<String>()
            val idColumn = cursor.getColumnIndexOrThrow("id")
            while (cursor.moveToNext()) {
                seen += cursor.getString(idColumn)
            }
            seen.toList()
        }
    }

    fun searchPages(
        documentId: String,
        rawQuery: String
    ): List<IndexedPageHit> {
        val ftsQuery = OcrSearchTerms.buildFtsQuery(rawQuery) ?: return emptyList()
        val db = database.getSupportWrapper()
        ensureSchema()
        val query = SimpleSQLiteQuery(
            """
            SELECT
                ocr_pages_fts.pageId AS pageId,
                snippet(ocr_pages_fts, 2, '‹', '›', ' … ', 20) AS snippet,
                bm25(ocr_pages_fts) AS rank
            FROM ocr_pages_fts
            JOIN pages p ON p.id = ocr_pages_fts.pageId
            WHERE ocr_pages_fts.documentId = ?
              AND p.deleted = 0
              AND ocr_pages_fts MATCH ?
            ORDER BY rank ASC, p.sortKey ASC, p.position ASC
            """.trimIndent(),
            arrayOf(documentId, ftsQuery)
        )

        return db.query(query).use { cursor ->
            buildList {
                val pageColumn = cursor.getColumnIndexOrThrow("pageId")
                val snippetColumn = cursor.getColumnIndexOrThrow("snippet")
                val rankColumn = cursor.getColumnIndexOrThrow("rank")
                while (cursor.moveToNext()) {
                    add(
                        IndexedPageHit(
                            pageId = cursor.getString(pageColumn),
                            snippet = cursor.getString(snippetColumn) ?: "",
                            rank = cursor.getDouble(rankColumn)
                        )
                    )
                }
            }
        }
    }

    private fun ensureSchema() {
        database.getSupportWrapper().execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS ocr_pages_fts
            USING fts5(
                documentId UNINDEXED,
                pageId UNINDEXED,
                content,
                tokenize='unicode61 remove_diacritics 2'
            )
            """.trimIndent()
        )
    }
}
