package com.thiepn.scan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE archived = 0 AND trashedAt IS NULL ORDER BY favorite DESC, updatedAt DESC")
    fun observeActive(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 0 AND favorite = 1 AND trashedAt IS NULL ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 1 AND trashedAt IS NULL ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 0 AND trashedAt IS NULL AND (title LIKE '%' || :query || '%' OR ocrText LIKE '%' || :query || '%') ORDER BY favorite DESC, updatedAt DESC")
    fun searchActive(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 0 AND favorite = 1 AND trashedAt IS NULL AND (title LIKE '%' || :query || '%' OR ocrText LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun searchFavorites(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 1 AND trashedAt IS NULL AND (title LIKE '%' || :query || '%' OR ocrText LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun searchArchived(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashedAt IS NOT NULL AND (title LIKE '%' || :query || '%' OR ocrText LIKE '%' || :query || '%') ORDER BY trashedAt DESC")
    fun searchTrash(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE processing = 1 AND trashedAt IS NULL ORDER BY createdAt")
    suspend fun getProcessingDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeDocument(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND deleted = 0 ORDER BY sortKey, position")
    fun observePages(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND deleted = 0 ORDER BY sortKey, position")
    suspend fun getPages(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND deleted = 1 ORDER BY sortKey, position")
    fun observeDeletedPages(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND deleted = 1 ORDER BY sortKey, position")
    suspend fun getDeletedPages(documentId: String): List<PageEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages WHERE documentId = :documentId")
    suspend fun getMaxPagePosition(documentId: String): Int

    @Query("SELECT COALESCE(MAX(sortKey), 0) FROM pages WHERE documentId = :documentId")
    suspend fun getMaxPageSortKey(documentId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: PageEntity)

    @Query("UPDATE pages SET ocrText = :text WHERE id = :pageId")
    suspend fun updatePageOcr(pageId: String, text: String)

    @Query("UPDATE pages SET sortKey = :sortKey WHERE id = :pageId")
    suspend fun updatePageSortKey(pageId: String, sortKey: Long)

    @Query("UPDATE pages SET deleted = :deleted WHERE id = :pageId")
    suspend fun setPageDeleted(pageId: String, deleted: Boolean)

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE documents SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long)

    @Query("UPDATE documents SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("UPDATE documents SET trashedAt = :trashedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTrashed(id: String, trashedAt: Long?, updatedAt: Long)

    @Query("UPDATE documents SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchDocument(id: String, updatedAt: Long)

    @Query("UPDATE documents SET processing = :processing, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setProcessing(id: String, processing: Boolean, updatedAt: Long)

    @Query("UPDATE documents SET pageCount = :pageCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageCount(id: String, pageCount: Int, updatedAt: Long)

    @Query("UPDATE documents SET ocrText = :text, processing = :processing, pageCount = :pageCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun finishProcessing(id: String, text: String, processing: Boolean, pageCount: Int, updatedAt: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Transaction
    suspend fun replacePageOrder(documentId: String, orderedPageIds: List<String>) {
        val current = getPages(documentId)
        require(current.size == orderedPageIds.size) { "Page order is stale" }
        require(current.map { it.id }.toSet() == orderedPageIds.toSet()) { "Page order is stale" }

        orderedPageIds.forEachIndexed { index, pageId ->
            updatePageSortKey(pageId, (index + 1L) * 1000L)
        }
    }
}
