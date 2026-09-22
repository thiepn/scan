package com.thiepn.scan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE archived = 0 ORDER BY favorite DESC, updatedAt DESC")
    fun observeActive(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 0 AND favorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE archived = 0 AND (title LIKE '%' || :query || '%' OR ocrText LIKE '%' || :query || '%') ORDER BY favorite DESC, updatedAt DESC")
    fun search(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeDocument(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    fun observePages(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    suspend fun getPages(documentId: String): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: PageEntity)

    @Query("UPDATE pages SET ocrText = :text WHERE id = :pageId")
    suspend fun updatePageOcr(pageId: String, text: String)

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE documents SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long)

    @Query("UPDATE documents SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("UPDATE documents SET pageCount = :pageCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageCount(id: String, pageCount: Int, updatedAt: Long)

    @Query("UPDATE documents SET ocrText = :text, processing = :processing, pageCount = :pageCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun finishProcessing(id: String, text: String, processing: Boolean, pageCount: Int, updatedAt: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}
