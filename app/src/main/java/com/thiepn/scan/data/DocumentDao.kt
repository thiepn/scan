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

    @Query("SELECT * FROM folders ORDER BY parentId, name COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM document_tags")
    fun observeDocumentTags(): Flow<List<DocumentTagCrossRef>>

    @Query("SELECT * FROM document_fields WHERE documentId = :documentId ORDER BY fieldKey")
    fun observeDocumentFields(documentId: String): Flow<List<DocumentFieldEntity>>

    @Query(
        "SELECT * FROM capture_sessions WHERE documentId = :documentId " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    fun observeLatestCaptureSession(documentId: String): Flow<CaptureSessionEntity?>

    @Query(
        "SELECT * FROM capture_sessions WHERE documentId = :documentId " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun getLatestCaptureSession(documentId: String): CaptureSessionEntity?

    @Query("SELECT * FROM capture_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getCaptureSession(sessionId: String): CaptureSessionEntity?

    @Query(
        "SELECT * FROM capture_sessions WHERE status IN (:statuses) " +
            "ORDER BY updatedAt, startedAt"
    )
    suspend fun getCaptureSessionsByStatus(
        statuses: List<String>
    ): List<CaptureSessionEntity>

    @Query("SELECT COUNT(*) FROM capture_sessions WHERE status = 'CAPTURING'")
    suspend fun getActiveCaptureSessionCount(): Int

    @Query(
        "SELECT * FROM page_processing WHERE sessionId = :sessionId " +
            "ORDER BY queuedAt, pageId"
    )
    suspend fun getSessionProcessingJobs(sessionId: String): List<PageProcessingEntity>

    @Query(
        "SELECT * FROM page_processing WHERE sessionId = :sessionId " +
            "AND status IN (:statuses) ORDER BY queuedAt, pageId"
    )
    suspend fun getSessionProcessingJobsByStatus(
        sessionId: String,
        statuses: List<String>
    ): List<PageProcessingEntity>

    @Query(
        "SELECT q.* FROM page_processing q " +
            "JOIN pages p ON p.id = q.pageId " +
            "WHERE q.documentId = :documentId " +
            "AND q.status IN ('PROCESSING', 'COMPLETE') " +
            "AND q.fingerprintHash IS NOT NULL " +
            "AND (p.deleted = 0 OR p.preservedBookSource = 1) " +
            "ORDER BY q.queuedAt, q.pageId"
    )
    suspend fun getReferenceFingerprints(
        documentId: String
    ): List<PageProcessingEntity>

    @Query(
        "SELECT COUNT(*) FROM page_processing WHERE documentId = :documentId " +
            "AND status IN ('QUEUED', 'FINGERPRINTING', 'PROCESSING')"
    )
    suspend fun getPendingProcessingJobCount(documentId: String): Int

    @Query("SELECT * FROM document_fields WHERE documentId = :documentId ORDER BY fieldKey")
    suspend fun getDocumentFields(documentId: String): List<DocumentFieldEntity>

    @Query("SELECT * FROM folders ORDER BY parentId, name COLLATE NOCASE")
    suspend fun getFolders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolder(id: String): FolderEntity?

    @Query(
        "SELECT * FROM folders WHERE normalizedName = :normalizedName " +
            "AND ((parentId IS NULL AND :parentId IS NULL) OR parentId = :parentId) LIMIT 1"
    )
    suspend fun findFolder(normalizedName: String, parentId: String?): FolderEntity?

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    suspend fun getTags(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findTag(normalizedName: String): TagEntity?

    @Query("SELECT * FROM document_tags WHERE documentId = :documentId")
    suspend fun getDocumentTags(documentId: String): List<DocumentTagCrossRef>

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

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND deleted = 0 ORDER BY sortKey, position LIMIT 1")
    fun observeCoverPage(documentId: String): Flow<PageEntity?>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND deleted = 0 ORDER BY sortKey, position")
    suspend fun getPages(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getPage(pageId: String): PageEntity?

    @Query(
        "SELECT * FROM pages WHERE documentId = :documentId AND deleted = 1 " +
            "AND preservedBookSource = 0 ORDER BY sortKey, position"
    )
    fun observeDeletedPages(documentId: String): Flow<List<PageEntity>>

    @Query(
        "SELECT * FROM pages WHERE documentId = :documentId AND deleted = 1 " +
            "AND preservedBookSource = 0 ORDER BY sortKey, position"
    )
    suspend fun getDeletedPages(documentId: String): List<PageEntity>

    @Query(
        "SELECT * FROM pages WHERE documentId = :documentId AND preservedBookSource = 1 " +
            "ORDER BY sortKey, position"
    )
    suspend fun getPreservedBookSources(documentId: String): List<PageEntity>

    @Query(
        "SELECT * FROM pages WHERE sourceSpreadPageId = :sourcePageId " +
            "ORDER BY bookSide, sortKey, position"
    )
    suspend fun getBookDerivedPages(sourcePageId: String): List<PageEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages WHERE documentId = :documentId")
    suspend fun getMaxPagePosition(documentId: String): Int

    @Query("SELECT COALESCE(MAX(sortKey), 0) FROM pages WHERE documentId = :documentId")
    suspend fun getMaxPageSortKey(documentId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocumentTag(link: DocumentTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocumentTags(links: List<DocumentTagCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentFields(fields: List<DocumentFieldEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCaptureSession(session: CaptureSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageProcessing(job: PageProcessingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageProcessingJobs(jobs: List<PageProcessingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: PageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("UPDATE pages SET ocrText = :text WHERE id = :pageId")
    suspend fun updatePageOcr(pageId: String, text: String)

    @Query(
        "UPDATE pages SET ocrText = :text, ocrLayout = :layout, " +
            "ocrFingerprint = :fingerprint, ocrScript = :script WHERE id = :pageId"
    )
    suspend fun updatePageOcrV2(
        pageId: String,
        text: String,
        layout: String?,
        fingerprint: String?,
        script: String?
    )

    @Query(
        "UPDATE pages SET ocrText = :text, ocrLayout = :layout, " +
            "ocrBaseLayout = :baseLayout, textEditRecipe = :recipe " +
            "WHERE id = :pageId"
    )
    suspend fun updatePageTextEdits(
        pageId: String,
        text: String,
        layout: String?,
        baseLayout: String?,
        recipe: String?
    )

    @Query(
        "UPDATE pages SET ocrText = :text, ocrLayout = :layout, " +
            "ocrBaseLayout = :baseLayout, textEditRecipe = :textRecipe, " +
            "markupRecipe = :markupRecipe, ocrFingerprint = :fingerprint, " +
            "ocrScript = :script WHERE id = :pageId"
    )
    suspend fun updatePageSemanticEdits(
        pageId: String,
        text: String,
        layout: String?,
        baseLayout: String?,
        textRecipe: String?,
        markupRecipe: String?,
        fingerprint: String?,
        script: String?
    )

    @Query("UPDATE pages SET markupRecipe = :recipe WHERE id = :pageId")
    suspend fun setPageMarkupRecipe(pageId: String, recipe: String?)

    @Query(
        "UPDATE pages SET ocrText = '', ocrLayout = NULL, " +
            "ocrBaseLayout = NULL, textEditRecipe = NULL, " +
            "ocrFingerprint = NULL, ocrScript = NULL WHERE id = :pageId"
    )
    suspend fun clearPageOcr(pageId: String)

    @Query("UPDATE pages SET sortKey = :sortKey WHERE id = :pageId")
    suspend fun updatePageSortKey(pageId: String, sortKey: Long)

    @Query("UPDATE pages SET deleted = :deleted WHERE id = :pageId")
    suspend fun setPageDeleted(pageId: String, deleted: Boolean)

    @Query(
        "UPDATE pages SET deleted = :deleted, preservedBookSource = :preserved " +
            "WHERE id = :pageId"
    )
    suspend fun setBookSourceState(
        pageId: String,
        deleted: Boolean,
        preserved: Boolean
    )

    @Query("UPDATE pages SET deleted = :deleted WHERE id IN (:pageIds)")
    suspend fun setPagesDeleted(pageIds: List<String>, deleted: Boolean)

    @Query("UPDATE pages SET rotationDegrees = :rotationDegrees WHERE id = :pageId")
    suspend fun setPageRotation(pageId: String, rotationDegrees: Int)

    @Query("UPDATE pages SET cropQuad = :cropQuad WHERE id = :pageId")
    suspend fun setPageCropQuad(pageId: String, cropQuad: String?)

    @Query("UPDATE pages SET visualRecipe = :visualRecipe WHERE id = :pageId")
    suspend fun setPageVisualRecipe(pageId: String, visualRecipe: String?)

    @Query("UPDATE pages SET cleanupRecipe = :cleanupRecipe WHERE id = :pageId")
    suspend fun setPageCleanupRecipe(pageId: String, cleanupRecipe: String?)

    @Query(
        "UPDATE pages SET bookSplitConfidence = :confidence, " +
            "bookDewarpStrength = :dewarpStrength, bookReviewResolved = 0 " +
            "WHERE id = :pageId"
    )
    suspend fun setBookAnalysis(
        pageId: String,
        confidence: Float?,
        dewarpStrength: Float
    )

    @Query(
        "UPDATE pages SET bookReviewResolved = :resolved WHERE id = :pageId"
    )
    suspend fun setBookReviewResolved(pageId: String, resolved: Boolean)

    @Query(
        "UPDATE pages SET bookSplitConfidence = NULL, bookDewarpStrength = 0, " +
            "bookReviewResolved = 0 WHERE id = :pageId"
    )
    suspend fun clearBookAnalysis(pageId: String)

    @Query(
        "UPDATE capture_sessions SET status = :status, updatedAt = :updatedAt, " +
            "completedAt = :completedAt, pausedReason = :pausedReason WHERE id = :sessionId"
    )
    suspend fun updateCaptureSessionState(
        sessionId: String,
        status: String,
        updatedAt: Long,
        completedAt: Long?,
        pausedReason: String?
    )

    @Query(
        "UPDATE capture_sessions SET status = 'INTERRUPTED', updatedAt = :updatedAt, " +
            "pausedReason = 'Capture session interrupted; queued pages were preserved.' " +
            "WHERE status = 'CAPTURING'"
    )
    suspend fun markCapturingSessionsInterrupted(updatedAt: Long)

    @Query(
        "UPDATE page_processing SET status = 'QUEUED', updatedAt = :updatedAt, " +
            "lastError = NULL WHERE status IN ('FINGERPRINTING', 'PROCESSING')"
    )
    suspend fun resetInterruptedProcessingJobs(updatedAt: Long)

    @Query(
        "UPDATE page_processing SET status = :status, updatedAt = :updatedAt, " +
            "attemptCount = attemptCount + :attemptIncrement, lastError = :lastError " +
            "WHERE pageId = :pageId"
    )
    suspend fun updateProcessingJobState(
        pageId: String,
        status: String,
        updatedAt: Long,
        attemptIncrement: Int = 0,
        lastError: String? = null
    )

    @Query(
        "UPDATE page_processing SET status = 'QUEUED', updatedAt = :updatedAt, " +
            "lastError = NULL WHERE sessionId = :sessionId AND status = 'FAILED'"
    )
    suspend fun retryFailedProcessingJobs(
        sessionId: String,
        updatedAt: Long
    )

    @Query(
        "UPDATE page_processing SET status = :status, fingerprintHash = :fingerprintHash, " +
            "meanLuma = :meanLuma, edgeEnergy = :edgeEnergy, aspectRatio = :aspectRatio, " +
            "qualityScore = :qualityScore, duplicateOfPageId = :duplicateOfPageId, " +
            "updatedAt = :updatedAt, lastError = :lastError WHERE pageId = :pageId"
    )
    suspend fun updateProcessingFingerprint(
        pageId: String,
        status: String,
        fingerprintHash: String?,
        meanLuma: Float?,
        edgeEnergy: Float?,
        aspectRatio: Float?,
        qualityScore: Float?,
        duplicateOfPageId: String?,
        updatedAt: Long,
        lastError: String? = null
    )

    @Query(
        """
        UPDATE capture_sessions SET
            capturedCount = (
                SELECT COUNT(*) FROM page_processing
                WHERE sessionId = :sessionId
            ),
            processedCount = (
                SELECT COUNT(*) FROM page_processing
                WHERE sessionId = :sessionId
                  AND status IN ('COMPLETE', 'DUPLICATE', 'FAILED')
            ),
            duplicateCount = (
                SELECT COUNT(*) FROM page_processing
                WHERE sessionId = :sessionId AND status = 'DUPLICATE'
            ),
            lowQualityCount = (
                SELECT COUNT(*) FROM page_processing
                WHERE sessionId = :sessionId
                  AND qualityScore IS NOT NULL
                  AND qualityScore < 0.35
                  AND status <> 'DUPLICATE'
            ),
            failedCount = (
                SELECT COUNT(*) FROM page_processing
                WHERE sessionId = :sessionId AND status = 'FAILED'
            ),
            updatedAt = :updatedAt
        WHERE id = :sessionId
        """
    )
    suspend fun refreshCaptureSessionCounters(
        sessionId: String,
        updatedAt: Long
    )

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query(
        "UPDATE documents SET folderId = :folderId, updatedAt = :updatedAt " +
            "WHERE id IN (:documentIds)"
    )
    suspend fun setDocumentFolder(
        documentIds: List<String>,
        folderId: String?,
        updatedAt: Long
    )

    @Query(
        "UPDATE documents SET documentType = :documentType, suggestedType = NULL, " +
            "needsReview = 0, updatedAt = :updatedAt WHERE id IN (:documentIds)"
    )
    suspend fun setDocumentType(
        documentIds: List<String>,
        documentType: String,
        updatedAt: Long
    )

    @Query(
        "UPDATE documents SET suggestedType = :suggestedType, needsReview = :needsReview " +
            "WHERE id = :id"
    )
    suspend fun setSuggestedDocumentType(
        id: String,
        suggestedType: String?,
        needsReview: Boolean
    )

    @Query(
        "UPDATE documents SET needsReview = :needsReview, " +
            "suggestedType = CASE WHEN :needsReview = 0 THEN NULL ELSE suggestedType END, " +
            "updatedAt = :updatedAt WHERE id IN (:documentIds)"
    )
    suspend fun setDocumentsNeedsReview(
        documentIds: List<String>,
        needsReview: Boolean,
        updatedAt: Long
    )

    @Query(
        "UPDATE documents SET favorite = :favorite, updatedAt = :updatedAt " +
            "WHERE id IN (:documentIds)"
    )
    suspend fun setDocumentsFavorite(
        documentIds: List<String>,
        favorite: Boolean,
        updatedAt: Long
    )

    @Query(
        "UPDATE documents SET archived = :archived, updatedAt = :updatedAt " +
            "WHERE id IN (:documentIds)"
    )
    suspend fun setDocumentsArchived(
        documentIds: List<String>,
        archived: Boolean,
        updatedAt: Long
    )

    @Query("UPDATE folders SET name = :name, normalizedName = :normalizedName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameFolder(
        id: String,
        name: String,
        normalizedName: String,
        updatedAt: Long
    )

    @Query("UPDATE folders SET parentId = :parentId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFolderParent(id: String, parentId: String?, updatedAt: Long)

    @Query("UPDATE folders SET parentId = :newParentId WHERE parentId = :folderId")
    suspend fun promoteChildFolders(folderId: String, newParentId: String?)

    @Query("UPDATE documents SET folderId = :newFolderId WHERE folderId = :folderId")
    suspend fun moveDocumentsFromFolder(folderId: String, newFolderId: String?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderRecord(id: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)

    @Query("DELETE FROM document_fields WHERE documentId = :documentId")
    suspend fun deleteDocumentFields(documentId: String)

    @Query("DELETE FROM document_tags WHERE documentId = :documentId AND tagId = :tagId")
    suspend fun deleteDocumentTag(documentId: String, tagId: String)

    @Query("DELETE FROM document_tags WHERE documentId IN (:documentIds)")
    suspend fun deleteDocumentTagsForDocuments(documentIds: List<String>)

    @Query("UPDATE documents SET ocrScript = :script, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDocumentOcrScript(id: String, script: String, updatedAt: Long)

    @Query(
        "UPDATE documents SET scanMode = :scanMode, documentType = :documentType, " +
            "suggestedType = NULL, needsReview = :needsReview, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setDocumentScanMode(
        id: String,
        scanMode: String,
        documentType: String,
        needsReview: Boolean,
        updatedAt: Long
    )

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

    @Query("DELETE FROM capture_sessions WHERE id = :sessionId")
    suspend fun deleteCaptureSession(sessionId: String)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePageRecord(pageId: String)

    @Query("DELETE FROM pages WHERE id IN (:pageIds)")
    suspend fun deletePageRecords(pageIds: List<String>)

    @Transaction
    suspend fun markCapturedPageDuplicate(
        pageId: String,
        fingerprintHash: String,
        meanLuma: Float,
        edgeEnergy: Float,
        aspectRatio: Float,
        qualityScore: Float,
        duplicateOfPageId: String,
        updatedAt: Long
    ) {
        updateProcessingFingerprint(
            pageId = pageId,
            status = PageProcessingStatus.DUPLICATE.name,
            fingerprintHash = fingerprintHash,
            meanLuma = meanLuma,
            edgeEnergy = edgeEnergy,
            aspectRatio = aspectRatio,
            qualityScore = qualityScore,
            duplicateOfPageId = duplicateOfPageId,
            updatedAt = updatedAt,
            lastError = null
        )
        deletePageRecord(pageId)
    }

    @Transaction
    suspend fun insertCapturedPagesAndJobs(
        pages: List<PageEntity>,
        jobs: List<PageProcessingEntity>,
        orderedPageIds: List<String>
    ) {
        require(pages.isNotEmpty()) { "No captured pages to insert" }
        require(pages.size == jobs.size) { "Capture pages and jobs are out of sync" }
        val documentId = pages.first().documentId
        require(pages.all { it.documentId == documentId }) {
            "Captured pages belong to different documents"
        }
        require(jobs.all { it.documentId == documentId }) {
            "Capture jobs belong to a different document"
        }
        insertPages(pages)
        insertPageProcessingJobs(jobs)
        replacePageOrder(documentId, orderedPageIds)
    }

    @Transaction
    suspend fun replaceDocumentFields(
        documentId: String,
        fields: List<DocumentFieldEntity>
    ) {
        deleteDocumentFields(documentId)
        if (fields.isNotEmpty()) insertDocumentFields(fields)
    }

    @Transaction
    suspend fun deleteFolderAndPromoteContents(folderId: String) {
        val folder = getFolder(folderId) ?: return
        moveDocumentsFromFolder(folderId, folder.parentId)
        promoteChildFolders(folderId, folder.parentId)
        deleteFolderRecord(folderId)
    }

    @Transaction
    suspend fun replaceDocumentTags(
        documentIds: List<String>,
        tagIds: List<String>
    ) {
        if (documentIds.isEmpty()) return
        deleteDocumentTagsForDocuments(documentIds)
        val links = buildList {
            documentIds.distinct().forEach { documentId ->
                tagIds.distinct().forEach { tagId ->
                    add(DocumentTagCrossRef(documentId, tagId))
                }
            }
        }
        if (links.isNotEmpty()) insertDocumentTags(links)
    }

    @Transaction
    suspend fun addDocumentTags(
        documentIds: List<String>,
        tagIds: List<String>
    ) {
        val links = buildList {
            documentIds.distinct().forEach { documentId ->
                tagIds.distinct().forEach { tagId ->
                    add(DocumentTagCrossRef(documentId, tagId))
                }
            }
        }
        if (links.isNotEmpty()) insertDocumentTags(links)
    }

    @Transaction
    suspend fun insertPageWithOrder(page: PageEntity, orderedPageIds: List<String>) {
        insertPage(page)
        replacePageOrder(page.documentId, orderedPageIds)
    }

    @Transaction
    suspend fun insertPagesWithOrder(pages: List<PageEntity>, orderedPageIds: List<String>) {
        require(pages.isNotEmpty()) { "No pages to insert" }
        val documentId = pages.first().documentId
        require(pages.all { it.documentId == documentId }) { "Pages belong to different documents" }
        insertPages(pages)
        replacePageOrder(documentId, orderedPageIds)
    }

    @Transaction
    suspend fun replaceActivePageWithBookPages(
        sourcePageId: String,
        derivedPages: List<PageEntity>,
        orderedPageIds: List<String>
    ) {
        require(derivedPages.size == 2) { "Book spread must produce two pages" }
        val source = getPage(sourcePageId) ?: error("Book source page not found")
        require(!source.deleted) { "Book source page is already inactive" }
        require(derivedPages.all { it.documentId == source.documentId }) {
            "Derived pages belong to a different document"
        }
        insertPages(derivedPages)
        setBookSourceState(sourcePageId, deleted = true, preserved = true)
        replacePageOrder(source.documentId, orderedPageIds)
    }

    @Transaction
    suspend fun restoreBookSource(
        sourcePageId: String,
        derivedPageIds: List<String>,
        orderedPageIds: List<String>
    ) {
        val source = getPage(sourcePageId) ?: error("Book source page not found")
        require(source.preservedBookSource) { "Page is not a preserved book source" }
        if (derivedPageIds.isNotEmpty()) {
            deletePageRecords(derivedPageIds)
        }
        setBookSourceState(sourcePageId, deleted = false, preserved = false)
        replacePageOrder(source.documentId, orderedPageIds)
    }

    @Transaction
    suspend fun replacePageRecord(
        oldPageId: String,
        newPage: PageEntity,
        orderedPageIds: List<String>
    ) {
        insertPage(newPage)
        deletePageRecord(oldPageId)
        replacePageOrder(newPage.documentId, orderedPageIds)
    }

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
