package com.thiepn.scan.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

class ScanRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val files: FileStore,
    private val ocr: OcrEngine,
    private val rasterizer: PdfPageRasterizer,
    private val pdfEngine: PdfEngine,
    private val searchIndex: OcrSearchIndex,
    private val appScope: CoroutineScope
) {
    private val highSpeedPolicy = HighSpeedProcessingPolicy(context)
    private val processingQueueMutex = Mutex()

    fun observeDocuments(filter: LibraryFilter, query: String): Flow<List<DocumentEntity>> {
        val normalized = query.trim()
        if (normalized.isNotBlank()) {
            return when (filter) {
                LibraryFilter.ACTIVE -> dao.searchActive(normalized)
                LibraryFilter.FAVORITES -> dao.searchFavorites(normalized)
                LibraryFilter.ARCHIVED -> dao.searchArchived(normalized)
                LibraryFilter.TRASH -> dao.searchTrash(normalized)
            }
        }
        return when (filter) {
            LibraryFilter.ACTIVE -> dao.observeActive()
            LibraryFilter.FAVORITES -> dao.observeFavorites()
            LibraryFilter.ARCHIVED -> dao.observeArchived()
            LibraryFilter.TRASH -> dao.observeTrash()
        }
    }

    fun observeDocument(id: String): Flow<DocumentEntity?> = dao.observeDocument(id)
    fun observePages(id: String): Flow<List<PageEntity>> = dao.observePages(id)
    fun observeCoverPage(id: String): Flow<PageEntity?> = dao.observeCoverPage(id)
    fun observeDeletedPages(id: String): Flow<List<PageEntity>> = dao.observeDeletedPages(id)
    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()
    fun observeTags(): Flow<List<TagEntity>> = dao.observeTags()
    fun observeDocumentTags(): Flow<List<DocumentTagCrossRef>> = dao.observeDocumentTags()
    fun observeDocumentFields(documentId: String): Flow<List<DocumentFieldEntity>> =
        dao.observeDocumentFields(documentId)
    fun observeFormTemplates(): Flow<List<FormTemplateEntity>> = dao.observeFormTemplates()

    fun observeLatestCaptureSession(
        documentId: String
    ): Flow<CaptureSessionEntity?> =
        dao.observeLatestCaptureSession(documentId)

    fun resumePendingProcessing() {
        appScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            searchIndex.rebuildAll()
            dao.markCapturingSessionsInterrupted(now)
            dao.resetInterruptedProcessingJobs(now)

            val queuedDocumentIds = dao.getCaptureSessionsByStatus(
                listOf(
                    CaptureSessionStatus.PROCESSING.name,
                    CaptureSessionStatus.PAUSED.name,
                    CaptureSessionStatus.INTERRUPTED.name
                )
            ).map { it.documentId }.toSet()

            kickProcessingQueue()

            dao.getProcessingDocuments()
                .filterNot { it.id in queuedDocumentIds }
                .forEach { document ->
                    val pages = dao.getPages(document.id)
                    val pdf = document.pdfPath?.let(::File)?.takeIf { it.isFile }
                    val looksLikePdfImport = pdf != null && (
                        pages.isEmpty() ||
                            pages.all {
                                page ->
                                page.id == deterministicPageId(
                                    document.id,
                                    page.position
                                )
                            }
                        )

                    if (looksLikePdfImport) {
                        renderPdfAndRecognize(document.id, pdf)
                    } else if (
                        ScanMode.fromStored(document.scanMode) == ScanMode.BOOK
                    ) {
                        processBookDocument(document.id)
                    } else {
                        recognizeDocument(document.id)
                    }
                }
        }
    }

    suspend fun ingestHighSpeedCapture(
        pageUris: List<Uri>,
        scanMode: ScanMode
    ): HighSpeedCaptureResult = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "No pages were captured" }
        val profile = ScanModeProfiles.forMode(scanMode)
        require(profile.supportsHighSpeedCapture) {
            "${scanMode.label} mode does not support continuous capture"
        }

        val now = System.currentTimeMillis()
        val documentId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        dao.insertDocument(
            DocumentEntity(
                id = documentId,
                title = defaultTitle(now, scanMode),
                createdAt = now,
                updatedAt = now,
                pdfPath = null,
                pageCount = 0,
                processing = true,
                documentType = profile.defaultDocumentType.name,
                scanMode = scanMode.name
            )
        )
        dao.insertCaptureSession(
            CaptureSessionEntity(
                id = sessionId,
                documentId = documentId,
                scanMode = scanMode.name,
                startedAt = now,
                updatedAt = now
            )
        )

        try {
            val count = persistHighSpeedBatch(
                documentId = documentId,
                sessionId = sessionId,
                pageUris = pageUris
            )
            HighSpeedCaptureResult(documentId, sessionId, count)
        } catch (error: Throwable) {
            dao.deleteDocument(documentId)
            throw error
        }
    }

    suspend fun startHighSpeedCaptureForDocument(
        documentId: String,
        pageUris: List<Uri>
    ): HighSpeedCaptureResult = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "No pages were captured" }
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val mode = ScanMode.fromStored(document.scanMode)
        require(ScanModeProfiles.forMode(mode).supportsHighSpeedCapture) {
            "${mode.label} mode does not support continuous capture"
        }

        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        dao.insertCaptureSession(
            CaptureSessionEntity(
                id = sessionId,
                documentId = documentId,
                scanMode = mode.name,
                startedAt = now,
                updatedAt = now
            )
        )
        dao.setProcessing(documentId, true, now)

        try {
            val count = persistHighSpeedBatch(
                documentId = documentId,
                sessionId = sessionId,
                pageUris = pageUris
            )
            HighSpeedCaptureResult(documentId, sessionId, count)
        } catch (error: Throwable) {
            dao.deleteCaptureSession(sessionId)
            dao.setProcessing(
                documentId,
                false,
                System.currentTimeMillis()
            )
            throw error
        }
    }

    suspend fun appendHighSpeedCapture(
        sessionId: String,
        pageUris: List<Uri>
    ): HighSpeedCaptureResult = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "No pages were captured" }
        val session = dao.getCaptureSession(sessionId)
            ?: throw IllegalArgumentException("Capture session not found")
        require(session.status == CaptureSessionStatus.CAPTURING.name) {
            "Capture session is no longer accepting pages"
        }
        val count = persistHighSpeedBatch(
            documentId = session.documentId,
            sessionId = session.id,
            pageUris = pageUris
        )
        HighSpeedCaptureResult(session.documentId, session.id, count)
    }

    suspend fun finishHighSpeedCaptureSession(sessionId: String) =
        withContext(Dispatchers.IO) {
            val session = dao.getCaptureSession(sessionId)
                ?: return@withContext
            if (session.isTerminal) return@withContext

            val now = System.currentTimeMillis()
            dao.refreshCaptureSessionCounters(sessionId, now)
            val latest = dao.getCaptureSession(sessionId) ?: return@withContext
            if (latest.capturedCount == 0) {
                dao.updateCaptureSessionState(
                    sessionId = sessionId,
                    status = CaptureSessionStatus.COMPLETE.name,
                    updatedAt = now,
                    completedAt = now,
                    pausedReason = null
                )
                refreshDocumentSummary(latest.documentId)
                return@withContext
            }

            dao.updateCaptureSessionState(
                sessionId = sessionId,
                status = CaptureSessionStatus.PROCESSING.name,
                updatedAt = now,
                completedAt = null,
                pausedReason = null
            )
            dao.setProcessing(latest.documentId, true, now)
            kickProcessingQueue()
        }

    suspend fun retryHighSpeedCaptureFailures(sessionId: String) =
        withContext(Dispatchers.IO) {
            val session = dao.getCaptureSession(sessionId)
                ?: throw IllegalArgumentException("Capture session not found")
            val now = System.currentTimeMillis()
            dao.retryFailedProcessingJobs(sessionId, now)
            dao.updateCaptureSessionState(
                sessionId = sessionId,
                status = CaptureSessionStatus.PROCESSING.name,
                updatedAt = now,
                completedAt = null,
                pausedReason = null
            )
            dao.setProcessing(session.documentId, true, now)
            dao.refreshCaptureSessionCounters(sessionId, now)
            kickProcessingQueue()
        }

    private suspend fun persistHighSpeedBatch(
        documentId: String,
        sessionId: String,
        pageUris: List<Uri>
    ): Int {
        val document = requireEditableDocument(documentId)
        val mode = ScanMode.fromStored(document.scanMode)
        val profile = ScanModeProfiles.forMode(mode)
        val currentPages = orderedPages(dao.getPages(documentId))
        var nextPosition = dao.getMaxPagePosition(documentId) + 1
        var nextSortKey = dao.getMaxPageSortKey(documentId) + 1000L
        val copiedFiles = mutableListOf<File>()
        val pages = mutableListOf<PageEntity>()
        val jobs = mutableListOf<PageProcessingEntity>()
        val now = System.currentTimeMillis()

        try {
            pageUris.forEachIndexed { index, uri ->
                val pageId = UUID.randomUUID().toString()
                val file = files.copyUri(
                    uri,
                    files.pageFile(documentId, pageId)
                )
                copiedFiles += file
                val size = imageSize(file)
                pages += PageEntity(
                    id = pageId,
                    documentId = documentId,
                    position = nextPosition++,
                    sortKey = nextSortKey,
                    visualRecipe = PageVisualRecipeCodec.encode(
                        PageVisualRecipe.forPreset(profile.defaultPreset)
                    ),
                    imagePath = file.absolutePath,
                    width = size.first,
                    height = size.second
                )
                jobs += PageProcessingEntity(
                    pageId = pageId,
                    documentId = documentId,
                    sessionId = sessionId,
                    queuedAt = now + index,
                    updatedAt = now
                )
                nextSortKey += 1000L
            }

            val newOrder = currentPages.map { it.id } + pages.map { it.id }
            dao.insertCapturedPagesAndJobs(
                pages = pages,
                jobs = jobs,
                orderedPageIds = newOrder
            )
            dao.updatePageCount(
                documentId,
                newOrder.size,
                System.currentTimeMillis()
            )
            dao.refreshCaptureSessionCounters(
                sessionId,
                System.currentTimeMillis()
            )
            return pages.size
        } catch (error: Throwable) {
            copiedFiles.forEach { it.delete() }
            throw error
        }
    }

    suspend fun ingestScan(
        pageUris: List<Uri>,
        pdfUri: Uri?,
        scanMode: ScanMode = ScanMode.DOCUMENT
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pdf = pdfUri?.let { files.copyUri(it, files.pdfFile(id)) }
        val profile = ScanModeProfiles.forMode(scanMode)
        val title = defaultTitle(now, scanMode)

        dao.insertDocument(
            DocumentEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                pdfPath = pdf?.absolutePath,
                pageCount = pageUris.size,
                processing = true,
                documentType = profile.defaultDocumentType.name,
                scanMode = scanMode.name
            )
        )

        val createdPageIds = mutableListOf<String>()
        pageUris.forEachIndexed { index, uri ->
            val pageId = UUID.randomUUID().toString()
            createdPageIds += pageId
            val file = files.copyUri(uri, files.pageFile(id, pageId))
            val size = imageSize(file)
            dao.insertPage(
                PageEntity(
                    id = pageId,
                    documentId = id,
                    position = index,
                    sortKey = (index + 1L) * 1000L,
                    visualRecipe = PageVisualRecipeCodec.encode(
                        PageVisualRecipe.forPreset(profile.defaultPreset)
                    ),
                    imagePath = file.absolutePath,
                    width = size.first,
                    height = size.second
                )
            )
        }

        appScope.launch(Dispatchers.IO) {
            when {
                pageUris.isEmpty() && pdf != null -> renderPdfAndRecognize(id, pdf)
                scanMode == ScanMode.BOOK -> processBookPagesAndRecognize(id, createdPageIds)
                else -> recognizeDocument(id)
            }
        }
        id
    }

    suspend fun appendScan(documentId: String, pageUris: List<Uri>): Int =
        insertScan(documentId, pageUris, Int.MAX_VALUE)

    suspend fun insertScan(
        documentId: String,
        pageUris: List<Uri>,
        insertIndex: Int
    ): Int = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "No pages were captured" }
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }

        val currentPages = orderedPages(dao.getPages(documentId))
        val profile = ScanModeProfiles.forMode(ScanMode.fromStored(document.scanMode))
        val targetIndex = insertIndex.coerceIn(0, currentPages.size)
        val nextPosition = dao.getMaxPagePosition(documentId) + 1
        val nextSortKey = dao.getMaxPageSortKey(documentId) + 1000L
        val copiedFiles = mutableListOf<File>()
        val insertedPages = mutableListOf<PageEntity>()

        dao.setProcessing(documentId, true, System.currentTimeMillis())
        try {
            pageUris.forEachIndexed { index, uri ->
                val pageId = UUID.randomUUID().toString()
                val file = files.copyUri(uri, files.pageFile(documentId, pageId))
                copiedFiles += file
                val size = imageSize(file)
                insertedPages += PageEntity(
                    id = pageId,
                    documentId = documentId,
                    position = nextPosition + index,
                    sortKey = nextSortKey + index * 1000L,
                    visualRecipe = PageVisualRecipeCodec.encode(
                        PageVisualRecipe.forPreset(profile.defaultPreset)
                    ),
                    imagePath = file.absolutePath,
                    width = size.first,
                    height = size.second
                )
            }

            val newOrder = currentPages.map { it.id }.toMutableList().apply {
                addAll(targetIndex, insertedPages.map { it.id })
            }
            dao.insertPagesWithOrder(insertedPages, newOrder)
            appScope.launch(Dispatchers.IO) {
                if (ScanMode.fromStored(document.scanMode) == ScanMode.BOOK) {
                    processBookPagesAndRecognize(
                        documentId,
                        insertedPages.map { it.id }
                    )
                } else {
                    recognizePagesAndRefresh(
                        documentId,
                        insertedPages.map { it.id }
                    )
                }
            }
            insertedPages.size
        } catch (error: Throwable) {
            copiedFiles.forEach { it.delete() }
            dao.setProcessing(documentId, false, System.currentTimeMillis())
            throw error
        }
    }

    suspend fun replacePageFromUri(
        documentId: String,
        pageId: String,
        uri: Uri
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }

        val currentPages = orderedPages(dao.getPages(documentId))
        val profile = ScanModeProfiles.forMode(ScanMode.fromStored(document.scanMode))
        val sourceIndex = currentPages.indexOfFirst { it.id == pageId }
        require(sourceIndex >= 0) { "Page not found" }
        val source = currentPages[sourceIndex]

        val replacementId = UUID.randomUUID().toString()
        val replacementFile = files.copyUri(
            uri,
            files.pageFile(documentId, replacementId)
        )

        dao.setProcessing(documentId, true, System.currentTimeMillis())
        try {
            val size = imageSize(replacementFile)
            val replacement = source.copy(
                id = replacementId,
                rotationDegrees = 0,
                cropQuad = null,
                visualRecipe = PageVisualRecipeCodec.encode(
                    PageVisualRecipe.forPreset(profile.defaultPreset)
                ),
                cleanupRecipe = null,
                imagePath = replacementFile.absolutePath,
                width = size.first,
                height = size.second,
                ocrText = "",
                ocrLayout = null,
                ocrBaseLayout = null,
                textEditRecipe = null,
                markupRecipe = null,
                formFillRecipe = null,
                ocrFingerprint = null,
                ocrScript = null,
                sourceSpreadPageId = source.sourceSpreadPageId,
                bookSide = source.bookSide,
                bookSplitConfidence = if (source.sourceSpreadPageId == null) {
                    null
                } else {
                    source.bookSplitConfidence
                },
                bookDewarpStrength = 0f,
                preservedBookSource = false,
                bookReviewResolved = false
            )
            val newOrder = currentPages.map { it.id }.toMutableList().apply {
                this[sourceIndex] = replacementId
            }
            dao.replacePageRecord(
                oldPageId = source.id,
                newPage = replacement,
                orderedPageIds = newOrder
            )
            searchIndex.deletePage(source.id)
            File(source.imagePath).takeIf { it.absolutePath != replacementFile.absolutePath }?.delete()
            appScope.launch(Dispatchers.IO) {
                if (
                    ScanMode.fromStored(document.scanMode) == ScanMode.BOOK &&
                    source.sourceSpreadPageId == null
                ) {
                    processBookPagesAndRecognize(
                        documentId,
                        listOf(replacementId)
                    )
                } else {
                    recognizePageAndRefresh(documentId, replacementId)
                }
            }
        } catch (error: Throwable) {
            replacementFile.delete()
            dao.setProcessing(documentId, false, System.currentTimeMillis())
            throw error
        }
    }

    suspend fun rotatePage(documentId: String, pageId: String) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }

        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }
        requireNoCoordinateEdits(page, "rotating this page")
        val nextRotation = PageRotation.clockwise(page.rotationDegrees)
        dao.setPageRotation(pageId, nextRotation)
        invalidateBookAnalysisIfOriginal(document, page)
        dao.clearPageOcr(pageId)
        searchIndex.deletePage(pageId)
        refreshDocumentSummary(documentId, processing = true)
        appScope.launch(Dispatchers.IO) {
            if (
                ScanMode.fromStored(document.scanMode) == ScanMode.BOOK &&
                page.sourceSpreadPageId == null
            ) {
                processBookPagesAndRecognize(documentId, listOf(pageId))
            } else {
                recognizePageAndRefresh(documentId, pageId)
            }
        }
    }

    suspend fun rotatePages(documentId: String, pageIds: List<String>) =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            val requested = pageIds.distinct()
            require(requested.isNotEmpty()) { "Select at least one page" }

            val pages = orderedPages(dao.getPages(documentId))
            val selected = pages.filter { it.id in requested }
            require(selected.size == requested.size) { "One or more selected pages are unavailable" }
            selected.forEach { requireNoCoordinateEdits(it, "rotating selected pages") }

            selected.forEach { page ->
                dao.setPageRotation(page.id, PageRotation.clockwise(page.rotationDegrees))
                invalidateBookAnalysisIfOriginal(document, page)
                dao.clearPageOcr(page.id)
                searchIndex.deletePage(page.id)
            }
            refreshDocumentSummary(documentId, processing = true)
            appScope.launch(Dispatchers.IO) {
                val hasOriginalBookPage =
                    ScanMode.fromStored(document.scanMode) == ScanMode.BOOK &&
                        selected.any { it.sourceSpreadPageId == null }
                if (hasOriginalBookPage) {
                    processBookPagesAndRecognize(
                        documentId,
                        selected
                            .filter { it.sourceSpreadPageId == null }
                            .map { it.id }
                    )
                } else {
                    recognizePagesAndRefresh(documentId, selected.map { it.id })
                }
            }
        }

    suspend fun detectPageCrop(documentId: String, pageId: String): CropQuad? =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            val page = dao.getPage(pageId)
                ?: throw IllegalArgumentException("Page not found")
            require(page.documentId == documentId) { "Page does not belong to this document" }
            PageBoundaryDetector.detect(File(page.imagePath))
        }

    suspend fun updatePageCrop(
        documentId: String,
        pageId: String,
        cropQuad: CropQuad?
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId) { "Page does not belong to this document" }
        requireNoCoordinateEdits(page, "changing crop or perspective")

        val encoded = CropQuadCodec.encode(cropQuad)
        dao.setPageCropQuad(pageId, encoded)
        if (!page.cleanupRecipe.isNullOrBlank()) {
            dao.setPageCleanupRecipe(pageId, null)
        }
        invalidateBookAnalysisIfOriginal(document, page)
        dao.clearPageOcr(pageId)
        searchIndex.deletePage(pageId)
        refreshDocumentSummary(documentId, processing = true)

        appScope.launch(Dispatchers.IO) {
            if (
                ScanMode.fromStored(document.scanMode) == ScanMode.BOOK &&
                page.sourceSpreadPageId == null
            ) {
                processBookPagesAndRecognize(documentId, listOf(pageId))
            } else {
                recognizePageAndRefresh(documentId, pageId)
            }
        }
    }

    suspend fun updatePageVisualRecipe(
        documentId: String,
        pageId: String,
        recipe: PageVisualRecipe
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId) { "Page does not belong to this document" }

        dao.setPageVisualRecipe(
            pageId = pageId,
            visualRecipe = PageVisualRecipeCodec.encode(recipe)
        )
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    suspend fun detectCleanupSuggestions(
        documentId: String,
        pageId: String
    ): List<CleanupSuggestion> = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }

        CleanupSuggestionDetector.detect(
            file = File(page.imagePath),
            cropQuad = CropQuadCodec.decode(page.cropQuad)
        )
    }

    suspend fun updatePageCleanup(
        documentId: String,
        pageId: String,
        recipe: PageCleanupRecipe
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }
        requireNoCoordinateEdits(page, "changing smart cleanup")

        val encoded = PageCleanupRecipeCodec.encode(recipe)
        if (encoded == page.cleanupRecipe) return@withContext

        dao.setPageCleanupRecipe(pageId, encoded)
        invalidateBookAnalysisIfOriginal(document, page)
        dao.clearPageOcr(pageId)
        searchIndex.deletePage(pageId)
        refreshDocumentSummary(documentId, processing = true)

        appScope.launch(Dispatchers.IO) {
            if (
                ScanMode.fromStored(document.scanMode) == ScanMode.BOOK &&
                page.sourceSpreadPageId == null
            ) {
                processBookPagesAndRecognize(documentId, listOf(pageId))
            } else {
                recognizePageAndRefresh(documentId, pageId)
            }
        }
    }

    suspend fun updatePageTextEdits(
        documentId: String,
        pageId: String,
        recipe: PageTextEditRecipe
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        require(
            ScanModeProfiles.forMode(
                ScanMode.fromStored(document.scanMode)
            ).ocrEnabled
        ) { "OCR text editing is unavailable in this scan mode" }

        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }

        val baseEncoded = page.ocrBaseLayout ?: page.ocrLayout
        val base = OcrLayoutCodec.decode(baseEncoded)
            ?: throw IllegalStateException("Recognize this page before editing its text")
        val normalized = recipe.normalized()
        val markup = PageMarkupRecipeCodec.decode(page.markupRecipe)
        val form = PageFormRecipeCodec.decode(page.formFillRecipe)
        val edited = PageMarkupOcr.applyRedactions(
            FormFillOcr.apply(OcrTextEditEngine.apply(base, normalized), form),
            markup
        )
        val needsBase = !normalized.isEmpty() || markup.hasRedactions() || form.hasSearchableValues()

        dao.updatePageSemanticEdits(
            pageId = pageId,
            text = edited.text,
            layout = OcrLayoutCodec.encode(edited),
            baseLayout = if (needsBase) baseEncoded else null,
            textRecipe = PageTextEditRecipeCodec.encode(normalized),
            markupRecipe = page.markupRecipe,
            fingerprint = page.ocrFingerprint,
            script = page.ocrScript
        )
        if (edited.text.isBlank()) searchIndex.deletePage(pageId) else
            searchIndex.upsertPage(documentId, pageId, edited.text)
        refreshDocumentSummary(documentId)
    }

    suspend fun updatePageMarkup(
        documentId: String,
        pageId: String,
        recipe: PageMarkupRecipe
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId) ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }

        val normalized = recipe.normalized()
        val encodedMarkup = PageMarkupRecipeCodec.encode(normalized)
        val baseEncoded = page.ocrBaseLayout ?: page.ocrLayout
        val base = OcrLayoutCodec.decode(baseEncoded)
        val textRecipe = PageTextEditRecipeCodec.decode(page.textEditRecipe)
        val form = PageFormRecipeCodec.decode(page.formFillRecipe)

        if (base == null) {
            if (normalized.hasRedactions()) {
                dao.updatePageSemanticEdits(
                    pageId = pageId,
                    text = "",
                    layout = null,
                    baseLayout = null,
                    textRecipe = page.textEditRecipe,
                    markupRecipe = encodedMarkup,
                    fingerprint = page.ocrFingerprint,
                    script = page.ocrScript
                )
                searchIndex.deletePage(pageId)
                val ocrEnabled = ScanModeProfiles.forMode(
                    ScanMode.fromStored(document.scanMode)
                ).ocrEnabled
                if (ocrEnabled) {
                    refreshDocumentSummary(documentId, processing = true)
                    appScope.launch(Dispatchers.IO) {
                        recognizePageAndRefresh(documentId, pageId)
                    }
                } else {
                    refreshDocumentSummary(documentId)
                }
            } else {
                dao.setPageMarkupRecipe(pageId, encodedMarkup)
                dao.touchDocument(documentId, System.currentTimeMillis())
            }
            return@withContext
        }

        val edited = PageMarkupOcr.applyRedactions(
            FormFillOcr.apply(OcrTextEditEngine.apply(base, textRecipe), form),
            normalized
        )
        val needsBase = !textRecipe.isEmpty() || normalized.hasRedactions() || form.hasSearchableValues()
        dao.updatePageSemanticEdits(
            pageId = pageId,
            text = edited.text,
            layout = OcrLayoutCodec.encode(edited),
            baseLayout = if (needsBase) baseEncoded else null,
            textRecipe = page.textEditRecipe,
            markupRecipe = encodedMarkup,
            fingerprint = page.ocrFingerprint,
            script = page.ocrScript
        )
        if (edited.text.isBlank()) searchIndex.deletePage(pageId) else
            searchIndex.upsertPage(documentId, pageId, edited.text)
        refreshDocumentSummary(documentId)
    }

    suspend fun detectFormFields(
        documentId: String,
        pageId: String
    ): Int = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId) ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }
        val layout = OcrLayoutCodec.decode(page.ocrLayout ?: page.ocrBaseLayout)
            ?: throw IllegalStateException("Recognize this page before detecting form fields")
        val current = PageFormRecipeCodec.decode(page.formFillRecipe)
        val merged = FormFieldDetector.merge(current, FormFieldDetector.detect(layout))
        val additions = (merged.fields.size - current.fields.size).coerceAtLeast(0)
        dao.setPageFormFillRecipe(pageId, PageFormRecipeCodec.encode(merged))
        dao.touchDocument(documentId, System.currentTimeMillis())
        additions
    }

    suspend fun detectFormFields(documentId: String): Int = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        var additions = 0
        orderedPages(dao.getPages(documentId)).forEach { page ->
            val layout = OcrLayoutCodec.decode(page.ocrLayout ?: page.ocrBaseLayout)
                ?: return@forEach
            val current = PageFormRecipeCodec.decode(page.formFillRecipe)
            val merged = FormFieldDetector.merge(current, FormFieldDetector.detect(layout))
            additions += (merged.fields.size - current.fields.size).coerceAtLeast(0)
            dao.setPageFormFillRecipe(page.id, PageFormRecipeCodec.encode(merged))
        }
        dao.touchDocument(documentId, System.currentTimeMillis())
        additions
    }

    suspend fun updatePageForm(
        documentId: String,
        pageId: String,
        recipe: PageFormRecipe
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val page = dao.getPage(pageId) ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page does not belong to this document"
        }

        val normalized = recipe.normalized()
        val encoded = PageFormRecipeCodec.encode(normalized)
        dao.setPageFormFillRecipe(pageId, encoded)

        val baseEncoded = page.ocrBaseLayout ?: page.ocrLayout
        val base = OcrLayoutCodec.decode(baseEncoded)
        if (base == null) {
            dao.touchDocument(documentId, System.currentTimeMillis())
            return@withContext
        }

        val textRecipe = PageTextEditRecipeCodec.decode(page.textEditRecipe)
        val markup = PageMarkupRecipeCodec.decode(page.markupRecipe)
        val edited = PageMarkupOcr.applyRedactions(
            FormFillOcr.apply(OcrTextEditEngine.apply(base, textRecipe), normalized),
            markup
        )
        val needsBase = !textRecipe.isEmpty() || markup.hasRedactions() || normalized.hasSearchableValues()
        dao.updatePageSemanticEdits(
            pageId = pageId,
            text = edited.text,
            layout = OcrLayoutCodec.encode(edited),
            baseLayout = if (needsBase) baseEncoded else null,
            textRecipe = page.textEditRecipe,
            markupRecipe = page.markupRecipe,
            fingerprint = page.ocrFingerprint,
            script = page.ocrScript
        )
        if (edited.text.isBlank()) searchIndex.deletePage(pageId) else
            searchIndex.upsertPage(documentId, pageId, edited.text)
        refreshDocumentSummary(documentId)
    }

    suspend fun saveFormTemplate(documentId: String, name: String): String =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            val clean = name.trim()
            require(clean.isNotBlank()) { "Template name cannot be blank" }
            require(clean.length <= 80) { "Template name is too long" }
            val pages = orderedPages(dao.getPages(documentId))
            val recipes = pages.map { PageFormRecipeCodec.decode(it.formFillRecipe).blankValues() }
            require(recipes.any { !it.isEmpty() }) { "Detect or add form fields before saving a template" }
            val normalizedName = clean.lowercase().replace(Regex("\\s+"), " ").trim()
            val existing = dao.findFormTemplate(normalizedName)
            val now = System.currentTimeMillis()
            val id = existing?.id ?: UUID.randomUUID().toString()
            dao.insertFormTemplate(
                FormTemplateEntity(
                    id = id,
                    name = clean,
                    normalizedName = normalizedName,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    pageCount = pages.size,
                    layout = FormTemplateCodec.encode(FormTemplateBundle(pages = recipes))
                )
            )
            id
        }

    suspend fun applyFormTemplate(documentId: String, templateId: String) =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            val template = dao.getFormTemplate(templateId)
                ?: throw IllegalArgumentException("Form template not found")
            val bundle = FormTemplateCodec.decode(template.layout)
                ?: throw IllegalStateException("Form template is corrupted")
            val pages = orderedPages(dao.getPages(documentId))
            require(bundle.pages.size == pages.size) {
                "This template expects ${bundle.pages.size} pages, but the document has ${pages.size}"
            }
            pages.forEachIndexed { index, page ->
                dao.setPageFormFillRecipe(
                    page.id,
                    PageFormRecipeCodec.encode(bundle.pages[index].blankValues())
                )
            }
            val profile = ScanModeProfiles.forMode(ScanMode.fromStored(document.scanMode))
            if (profile.ocrEnabled) {
                refreshDocumentSummary(documentId, processing = true)
                appScope.launch(Dispatchers.IO) {
                    recognizePagesAndRefresh(documentId, pages.map { it.id })
                }
            } else {
                dao.touchDocument(documentId, System.currentTimeMillis())
            }
        }

    suspend fun deleteFormTemplate(templateId: String) = withContext(Dispatchers.IO) {
        dao.deleteFormTemplate(templateId)
    }

    suspend fun autoCleanupPages(
        documentId: String,
        pageIds: List<String>,
        minimumConfidence: Float = 0.84f
    ): Int = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val requested = pageIds.distinct()
        require(requested.isNotEmpty()) { "Select at least one page" }

        val pages = orderedPages(dao.getPages(documentId))
        val selected = pages.filter { it.id in requested }
        require(selected.size == requested.size) {
            "One or more selected pages are unavailable"
        }
        selected.forEach { requireNoCoordinateEdits(it, "auto-cleaning selected pages") }

        var applied = 0
        val changed = mutableListOf<PageEntity>()
        selected.forEach { page ->
            val suggestions = CleanupSuggestionDetector.detect(
                file = File(page.imagePath),
                cropQuad = CropQuadCodec.decode(page.cropQuad)
            ).filter { it.stroke.confidence >= minimumConfidence }

            if (suggestions.isEmpty()) return@forEach
            val current = PageCleanupRecipeCodec.decode(page.cleanupRecipe)
            val existingKeys = current.strokes.map(::cleanupStrokeKey).toMutableSet()
            val additions = suggestions.map { it.stroke }.filter { stroke ->
                existingKeys.add(cleanupStrokeKey(stroke))
            }
            if (additions.isEmpty()) return@forEach

            val recipe = current.copy(
                strokes = current.strokes + additions
            ).normalized()
            dao.setPageCleanupRecipe(
                page.id,
                PageCleanupRecipeCodec.encode(recipe)
            )
            invalidateBookAnalysisIfOriginal(document, page)
            dao.clearPageOcr(page.id)
            searchIndex.deletePage(page.id)
            applied += additions.size
            changed += page
        }

        if (changed.isNotEmpty()) {
            refreshDocumentSummary(documentId, processing = true)
            appScope.launch(Dispatchers.IO) {
                val originalBookIds = if (
                    ScanMode.fromStored(document.scanMode) == ScanMode.BOOK
                ) {
                    changed
                        .filter { it.sourceSpreadPageId == null }
                        .map { it.id }
                } else {
                    emptyList()
                }

                if (originalBookIds.isNotEmpty()) {
                    processBookPagesAndRecognize(
                        documentId,
                        originalBookIds
                    )
                } else {
                    recognizePagesAndRefresh(
                        documentId,
                        changed.map { it.id }
                    )
                }
            }
        }
        applied
    }

    private fun cleanupStrokeKey(stroke: CleanupStroke): String {
        val normalized = stroke.normalized()
        val first = normalized.points.firstOrNull() ?: return normalized.kind.name
        val last = normalized.points.lastOrNull() ?: first
        return listOf(
            normalized.kind.name,
            (first.x * 100f).roundToInt(),
            (first.y * 100f).roundToInt(),
            (last.x * 100f).roundToInt(),
            (last.y * 100f).roundToInt(),
            (normalized.radius * 100f).roundToInt()
        ).joinToString(":")
    }

    suspend fun duplicatePage(documentId: String, pageId: String) {
        duplicatePages(documentId, listOf(pageId))
    }

    suspend fun duplicatePages(documentId: String, pageIds: List<String>): Int =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }

            val requested = pageIds.distinct()
            require(requested.isNotEmpty()) { "Select at least one page" }
            val pages = orderedPages(dao.getPages(documentId))
            val selected = pages.filter { it.id in requested }
            require(selected.size == requested.size) { "One or more selected pages are unavailable" }

            var nextPosition = dao.getMaxPagePosition(documentId) + 1
            var nextSortKey = dao.getMaxPageSortKey(documentId) + 1000L
            val copiedFiles = mutableListOf<File>()
            val duplicates = mutableListOf<PageEntity>()
            val duplicateBySource = mutableMapOf<String, String>()

            try {
                selected.forEach { source ->
                    val duplicateId = UUID.randomUUID().toString()
                    val file = files.copyPageFile(
                        documentId = documentId,
                        source = File(source.imagePath),
                        newPageId = duplicateId
                    )
                    copiedFiles += file
                    duplicates += source.copy(
                        id = duplicateId,
                        position = nextPosition++,
                        sortKey = nextSortKey,
                        deleted = false,
                        imagePath = file.absolutePath,
                        sourceSpreadPageId = null,
                        bookSide = null,
                        bookSplitConfidence = null,
                        bookDewarpStrength = 0f,
                        preservedBookSource = false,
                        bookReviewResolved = false
                    )
                    nextSortKey += 1000L
                    duplicateBySource[source.id] = duplicateId
                }

                val newOrder = buildList {
                    pages.forEach { page ->
                        add(page.id)
                        duplicateBySource[page.id]?.let { add(it) }
                    }
                }
                dao.insertPagesWithOrder(duplicates, newOrder)
                duplicates.forEach { duplicate ->
                    searchIndex.upsertPage(
                        documentId = documentId,
                        pageId = duplicate.id,
                        content = duplicate.ocrText,
                        deleted = duplicate.deleted
                    )
                }
                if (ScanMode.fromStored(document.scanMode) == ScanMode.BOOK) {
                    refreshDocumentSummary(documentId, processing = true)
                    appScope.launch(Dispatchers.IO) {
                        processBookPagesAndRecognize(
                            documentId,
                            duplicates.map { it.id }
                        )
                    }
                } else {
                    refreshDocumentSummary(documentId)
                }
                duplicates.size
            } catch (error: Throwable) {
                copiedFiles.forEach { it.delete() }
                throw error
            }
        }

    suspend fun movePages(
        documentId: String,
        pageIds: List<String>,
        targetIndex: Int
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val requested = pageIds.distinct()
        require(requested.isNotEmpty()) { "Select at least one page" }

        val pages = orderedPages(dao.getPages(documentId))
        val selectedSet = requested.toSet()
        val moving = pages.filter { it.id in selectedSet }
        require(moving.size == requested.size) { "One or more selected pages are unavailable" }

        val remaining = pages.filterNot { it.id in selectedSet }.toMutableList()
        val destination = targetIndex.coerceIn(0, remaining.size)
        remaining.addAll(destination, moving)
        dao.replacePageOrder(documentId, remaining.map { it.id })
        refreshDocumentSummary(documentId)
    }

    suspend fun applyPresetToPages(
        documentId: String,
        pageIds: List<String>,
        preset: ScanPreset
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val requested = pageIds.distinct()
        require(requested.isNotEmpty()) { "Select at least one page" }
        val pages = orderedPages(dao.getPages(documentId))
        val selected = pages.filter { it.id in requested }
        require(selected.size == requested.size) { "One or more selected pages are unavailable" }

        val encoded = PageVisualRecipeCodec.encode(PageVisualRecipe.forPreset(preset))
        selected.forEach { dao.setPageVisualRecipe(it.id, encoded) }
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    suspend fun resetPageEdits(documentId: String, pageIds: List<String>) =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            val requested = pageIds.distinct()
            require(requested.isNotEmpty()) { "Select at least one page" }
            val pages = orderedPages(dao.getPages(documentId))
            val selected = pages.filter { it.id in requested }
            require(selected.size == requested.size) { "One or more selected pages are unavailable" }

            val requiresOcr = mutableListOf<String>()
            selected.forEach { page ->
                val semanticGeometryChanged =
                    PageRotation.normalize(page.rotationDegrees) != 0 ||
                        !CropQuadCodec.decode(page.cropQuad).isFullFrame() ||
                        !page.cleanupRecipe.isNullOrBlank()
                val markup = PageMarkupRecipeCodec.decode(page.markupRecipe)
                val form = PageFormRecipeCodec.decode(page.formFillRecipe)
                val hasTextEdits =
                    !page.textEditRecipe.isNullOrBlank() ||
                        !page.ocrBaseLayout.isNullOrBlank()
                val hasSemanticEdits =
                    hasTextEdits || markup.hasRedactions() || form.hasSearchableValues()

                when {
                    semanticGeometryChanged -> {
                        requiresOcr += page.id
                        invalidateBookAnalysisIfOriginal(document, page)
                        dao.clearPageOcr(page.id)
                        searchIndex.deletePage(page.id)
                    }

                    hasSemanticEdits -> {
                        val base = OcrLayoutCodec.decode(page.ocrBaseLayout)
                        if (base == null) {
                            requiresOcr += page.id
                            dao.clearPageOcr(page.id)
                            searchIndex.deletePage(page.id)
                        } else {
                            dao.updatePageSemanticEdits(
                                pageId = page.id,
                                text = base.text,
                                layout = OcrLayoutCodec.encode(base),
                                baseLayout = null,
                                textRecipe = null,
                                markupRecipe = null,
                                fingerprint = page.ocrFingerprint,
                                script = page.ocrScript
                            )
                            searchIndex.upsertPage(
                                documentId = documentId,
                                pageId = page.id,
                                content = base.text
                            )
                        }
                    }
                }

                dao.setPageRotation(page.id, 0)
                dao.setPageCropQuad(page.id, null)
                dao.setPageVisualRecipe(page.id, null)
                dao.setPageCleanupRecipe(page.id, null)
                dao.setPageMarkupRecipe(page.id, null)
                dao.setPageFormFillRecipe(page.id, null)
            }

            if (requiresOcr.isEmpty()) {
                refreshDocumentSummary(documentId)
            } else {
                refreshDocumentSummary(documentId, processing = true)
                appScope.launch(Dispatchers.IO) {
                    val originalBookIds = if (
                        ScanMode.fromStored(document.scanMode) == ScanMode.BOOK
                    ) {
                        selected
                            .filter {
                                it.id in requiresOcr &&
                                    it.sourceSpreadPageId == null
                            }
                            .map { it.id }
                    } else {
                        emptyList()
                    }

                    if (originalBookIds.isNotEmpty()) {
                        processBookPagesAndRecognize(
                            documentId,
                            originalBookIds
                        )
                    } else {
                        recognizePagesAndRefresh(documentId, requiresOcr)
                    }
                }
            }
        }

    suspend fun resetAllPageEdits(documentId: String) = withContext(Dispatchers.IO) {
        val ids = orderedPages(dao.getPages(documentId)).map { it.id }
        if (ids.isNotEmpty()) resetPageEdits(documentId, ids)
    }

    suspend fun importPdf(uri: Uri, displayName: String?): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pdf = files.copyUri(uri, files.pdfFile(id))
        val title = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: defaultTitle(now)
        dao.insertDocument(
            DocumentEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                pdfPath = pdf.absolutePath,
                pageCount = 0,
                processing = true
            )
        )
        appScope.launch(Dispatchers.IO) { renderPdfAndRecognize(id, pdf) }
        id
    }

    private suspend fun renderPdfAndRecognize(documentId: String, pdf: File) {
        runCatching {
            val rendered = rasterizer.render(pdf) { index ->
                val pageId = deterministicPageId(documentId, index)
                files.pageFile(documentId, pageId)
            }
            rendered.forEachIndexed { index, renderedPage ->
                val pageId = deterministicPageId(documentId, index)
                val existing = dao.getPage(pageId)
                dao.insertPage(
                    existing?.copy(
                        imagePath = renderedPage.file.absolutePath,
                        width = renderedPage.width,
                        height = renderedPage.height
                    ) ?: PageEntity(
                        id = pageId,
                        documentId = documentId,
                        position = index,
                        sortKey = (index + 1L) * 1000L,
                        imagePath = renderedPage.file.absolutePath,
                        width = renderedPage.width,
                        height = renderedPage.height
                    )
                )
                dao.updatePageCount(documentId, index + 1, System.currentTimeMillis())
            }
            recognizeDocument(documentId)
        }.onFailure {
            val pages = dao.getPages(documentId)
            dao.finishProcessing(
                documentId,
                pages.joinToString("\n\n") { it.ocrText },
                false,
                pages.size,
                System.currentTimeMillis()
            )
        }
    }

    private data class RecognizedPage(
        val result: OcrPageResult,
        val fingerprint: String
    )

    private suspend fun recognizeDocument(documentId: String) {
        val document = dao.getDocument(documentId) ?: return
        val mode = ScanMode.fromStored(document.scanMode)
        val profile = ScanModeProfiles.forMode(mode)
        val pages = dao.getPages(documentId)

        if (!profile.ocrEnabled) {
            pages.forEach { page ->
                dao.clearPageOcr(page.id)
                searchIndex.deletePage(page.id)
            }
            dao.finishProcessing(
                id = documentId,
                text = "",
                processing = false,
                pageCount = pages.size,
                updatedAt = System.currentTimeMillis()
            )
            refreshTypeSuggestion(documentId)
            refreshSpecializedFields(documentId)
            return
        }

        val script = OcrScript.fromStored(document.ocrScript)
        val recognized = mutableListOf<String>()

        pages.forEach { page ->
            val recognition = runCatching { recognizePage(page, script) }.getOrNull()
            if (recognition == null) {
                dao.clearPageOcr(page.id)
                searchIndex.deletePage(page.id)
                return@forEach
            }
            val persisted = persistRecognition(documentId, page.id, recognition)
            if (persisted.text.isNotBlank()) {
                recognized += persisted.text
            }
        }

        dao.finishProcessing(
            id = documentId,
            text = recognized.joinToString("\n\n"),
            processing = false,
            pageCount = pages.size,
            updatedAt = System.currentTimeMillis()
        )
        refreshTypeSuggestion(documentId)
        refreshSpecializedFields(documentId)
    }

    private suspend fun recognizePageAndRefresh(documentId: String, pageId: String) {
        recognizePagesAndRefresh(documentId, listOf(pageId))
    }

    private suspend fun recognizePagesAndRefresh(
        documentId: String,
        pageIds: List<String>
    ) {
        try {
            val document = dao.getDocument(documentId) ?: return
            val mode = ScanMode.fromStored(document.scanMode)
            val profile = ScanModeProfiles.forMode(mode)
            if (!profile.ocrEnabled) {
                pageIds.distinct().forEach { pageId ->
                    dao.clearPageOcr(pageId)
                    searchIndex.deletePage(pageId)
                }
                return
            }

            val script = OcrScript.fromStored(document.ocrScript)
            pageIds.distinct().forEach { pageId ->
                val page = dao.getPage(pageId) ?: return@forEach
                if (page.deleted || page.documentId != documentId) return@forEach

                val recognition = runCatching { recognizePage(page, script) }.getOrNull()
                if (recognition == null) {
                    dao.clearPageOcr(pageId)
                    searchIndex.deletePage(pageId)
                } else {
                    persistRecognition(documentId, pageId, recognition)
                }
            }
        } finally {
            refreshDocumentSummary(documentId)
        }
    }

    private suspend fun recognizePage(
        page: PageEntity,
        script: OcrScript
    ): RecognizedPage {
        val imageFile = File(page.imagePath)
        require(imageFile.isFile) { "Page image is unavailable" }
        val fingerprint = OcrFingerprint.create(
            file = imageFile,
            cropQuad = page.cropQuad,
            rotationDegrees = page.rotationDegrees,
            cleanupRecipe = page.cleanupRecipe,
            script = script
        )

        if (
            page.ocrFingerprint == fingerprint &&
            page.ocrScript == script.name
        ) {
            OcrLayoutCodec.decode(page.ocrBaseLayout ?: page.ocrLayout)?.let {
                return RecognizedPage(it, fingerprint)
            }
        }

        val cleanup = PageCleanupRecipeCodec.decode(page.cleanupRecipe)
        val quad = CropQuadCodec.decode(page.cropQuad)
        val result = if (
            cleanup.isEmpty() &&
            quad.isFullFrame() &&
            PageRotation.normalize(page.rotationDegrees) == 0
        ) {
            ocr.recognizeDetailed(imageFile, script)
        } else {
            val bitmap = renderSemanticPageBitmap(
                page = page,
                maxLongEdge = 2800
            )
            try {
                ocr.recognizeDetailed(bitmap, script)
            } finally {
                bitmap.recycle()
            }
        }

        return RecognizedPage(result, fingerprint)
    }

    private suspend fun persistRecognition(
        documentId: String,
        pageId: String,
        recognition: RecognizedPage
    ): OcrPageResult {
        val base = recognition.result
        val page = dao.getPage(pageId)
        val textRecipe = PageTextEditRecipeCodec.decode(page?.textEditRecipe)
        val markup = PageMarkupRecipeCodec.decode(page?.markupRecipe)
        val form = PageFormRecipeCodec.decode(page?.formFillRecipe)
        val result = PageMarkupOcr.applyRedactions(
            FormFillOcr.apply(OcrTextEditEngine.apply(base, textRecipe), form),
            markup
        )
        val needsBase = !textRecipe.isEmpty() || markup.hasRedactions() || form.hasSearchableValues()
        dao.updatePageSemanticEdits(
            pageId = pageId,
            text = result.text,
            layout = OcrLayoutCodec.encode(result),
            baseLayout = if (needsBase) OcrLayoutCodec.encode(base) else null,
            textRecipe = PageTextEditRecipeCodec.encode(textRecipe),
            markupRecipe = PageMarkupRecipeCodec.encode(markup),
            fingerprint = recognition.fingerprint,
            script = result.script.name
        )
        if (result.text.isBlank()) searchIndex.deletePage(pageId) else
            searchIndex.upsertPage(documentId, pageId, result.text)
        return result
    }

    suspend fun analyzeBookSpread(
        documentId: String,
        pageId: String
    ): BookSpreadAnalysis = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(ScanMode.fromStored(document.scanMode) == ScanMode.BOOK) {
            "Switch this document to Book mode first"
        }
        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(page.documentId == documentId && !page.deleted) {
            "Page is not active in this document"
        }
        require(page.sourceSpreadPageId == null) {
            "This page is already derived from a book spread"
        }

        val working = prepareBookWorkingSource(page)
        try {
            BookSpreadProcessor.analyze(working.first).also { analysis ->
                dao.setBookAnalysis(
                    pageId = page.id,
                    confidence = analysis.confidence,
                    dewarpStrength = analysis.dewarpStrength
                )
            }
        } finally {
            if (working.second) working.first.delete()
        }
    }

    suspend fun keepBookPageSingle(
        documentId: String,
        pageId: String
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        require(ScanMode.fromStored(document.scanMode) == ScanMode.BOOK) {
            "Switch this document to Book mode first"
        }
        val page = dao.getPage(pageId)
            ?: throw IllegalArgumentException("Page not found")
        require(
            page.documentId == documentId &&
                !page.deleted &&
                page.sourceSpreadPageId == null
        ) {
            "Only an active original book page can be kept as single"
        }

        dao.setBookReviewResolved(pageId, true)
        dao.touchDocument(documentId, System.currentTimeMillis())
        refreshSpecializedFields(documentId)
    }

    suspend fun splitBookPage(
        documentId: String,
        pageId: String,
        dewarp: Boolean = true,
        force: Boolean = true,
        gutterX: Float? = null
    ): BookSpreadAnalysis = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        require(ScanMode.fromStored(document.scanMode) == ScanMode.BOOK) {
            "Switch this document to Book mode first"
        }

        dao.setProcessing(documentId, true, System.currentTimeMillis())
        try {
            val page = dao.getPage(pageId)
                ?: throw IllegalArgumentException("Page not found")
            require(!page.deleted && page.sourceSpreadPageId == null) {
                "Only an active original spread can be split"
            }
            requireNoCoordinateEdits(page, "splitting this book page")

            val working = prepareBookWorkingSource(page)
            val analysis = try {
                BookSpreadProcessor.analyze(working.first)
            } finally {
                if (working.second) working.first.delete()
            }
            require(force || analysis.likelySpread) {
                "Spread confidence is too low; review the page before splitting"
            }

            val base = if (force && !analysis.likelySpread) {
                analysis.copy(
                    likelySpread = true,
                    autoSplitRecommended = false,
                    gutterX = if (analysis.confidence < 0.20f) 0.5f else analysis.gutterX,
                    dewarpStrength = if (dewarp && analysis.dewarpStrength <= 0f) {
                        0.025f
                    } else {
                        analysis.dewarpStrength
                    },
                    reason = "Manual spread split"
                )
            } else {
                analysis
            }
            val effective = base.copy(
                gutterX = gutterX?.coerceIn(0.32f, 0.68f) ?: base.gutterX
            )

            dao.setBookAnalysis(
                pageId = pageId,
                confidence = effective.confidence,
                dewarpStrength = if (dewarp) effective.dewarpStrength else 0f
            )
            splitBookPageInternal(
                documentId = documentId,
                sourcePageId = pageId,
                analysis = effective,
                dewarp = dewarp
            )
            recognizeDocument(documentId)
            effective
        } catch (error: Throwable) {
            dao.setProcessing(documentId, false, System.currentTimeMillis())
            throw error
        }
    }

    suspend fun autoProcessBookSpreads(documentId: String): Int =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            require(ScanMode.fromStored(document.scanMode) == ScanMode.BOOK) {
                "Switch this document to Book mode first"
            }
            val candidates = orderedPages(dao.getPages(documentId))
                .filter {
                    it.sourceSpreadPageId == null &&
                        !it.bookReviewResolved
                }
            val before = dao.getPreservedBookSources(documentId).size
            dao.setProcessing(documentId, true, System.currentTimeMillis())
            try {
                processBookPagesAndRecognize(
                    documentId = documentId,
                    pageIds = candidates.map { it.id }
                )
                val after = dao.getPreservedBookSources(documentId).size
                (after - before).coerceAtLeast(0)
            } catch (error: Throwable) {
                dao.setProcessing(documentId, false, System.currentTimeMillis())
                throw error
            }
        }

    suspend fun restoreBookSpread(
        documentId: String,
        sourcePageId: String
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val source = dao.getPage(sourcePageId)
            ?: throw IllegalArgumentException("Original spread not found")
        require(
            source.documentId == documentId &&
                source.deleted &&
                source.preservedBookSource
        ) {
            "Original spread is not available"
        }

        val derived = dao.getBookDerivedPages(sourcePageId)
        require(derived.isNotEmpty()) { "No derived book pages found" }
        derived.forEach {
            requireNoCoordinateEdits(it, "restoring the original book spread")
        }
        val active = orderedPages(dao.getPages(documentId))
        val derivedIds = derived.map { it.id }.toSet()
        val derivedIndex = active.indexOfFirst { it.id in derivedIds }
        val insertionIndex = if (derivedIndex >= 0) {
            derivedIndex
        } else {
            active.indexOfFirst { it.sortKey >= source.sortKey }
                .let { if (it >= 0) it else active.size }
        }
        val newOrder = active
            .filterNot { it.id in derivedIds }
            .map { it.id }
            .toMutableList()
            .apply { add(insertionIndex.coerceIn(0, size), sourcePageId) }

        dao.setProcessing(documentId, true, System.currentTimeMillis())
        try {
            dao.restoreBookSource(
                sourcePageId = sourcePageId,
                derivedPageIds = derived.map { it.id },
                orderedPageIds = newOrder
            )
            derived.forEach { page ->
                searchIndex.deletePage(page.id)
                File(page.imagePath).delete()
            }
            if (source.textEditRecipe.isNullOrBlank()) {
                dao.clearPageOcr(sourcePageId)
                searchIndex.deletePage(sourcePageId)
                recognizeDocument(documentId)
            } else {
                searchIndex.upsertPage(
                    documentId = documentId,
                    pageId = sourcePageId,
                    content = source.ocrText
                )
                refreshDocumentSummary(documentId)
            }
        } catch (error: Throwable) {
            dao.setProcessing(documentId, false, System.currentTimeMillis())
            throw error
        }
    }

    private suspend fun processBookDocument(documentId: String) {
        val pageIds = orderedPages(dao.getPages(documentId))
            .filter {
                it.sourceSpreadPageId == null &&
                    !it.bookReviewResolved
            }
            .map { it.id }
        processBookPagesAndRecognize(documentId, pageIds)
    }

    private suspend fun processBookPagesAndRecognize(
        documentId: String,
        pageIds: List<String>
    ) {
        val document = dao.getDocument(documentId) ?: return
        if (ScanMode.fromStored(document.scanMode) != ScanMode.BOOK) {
            recognizePagesAndRefresh(documentId, pageIds)
            return
        }

        pageIds.distinct().forEach { pageId ->
            val page = dao.getPage(pageId) ?: return@forEach
            if (
                page.deleted ||
                page.documentId != documentId ||
                page.sourceSpreadPageId != null ||
                page.bookReviewResolved ||
                !page.textEditRecipe.isNullOrBlank() ||
                !page.markupRecipe.isNullOrBlank() ||
                !page.formFillRecipe.isNullOrBlank()
            ) {
                return@forEach
            }

            val working = prepareBookWorkingSource(page)
            val analysis = try {
                BookSpreadProcessor.analyze(working.first)
            } finally {
                if (working.second) working.first.delete()
            }
            dao.setBookAnalysis(
                pageId = page.id,
                confidence = analysis.confidence,
                dewarpStrength = analysis.dewarpStrength
            )

            if (analysis.autoSplitRecommended) {
                runCatching {
                    splitBookPageInternal(
                        documentId = documentId,
                        sourcePageId = page.id,
                        analysis = analysis,
                        dewarp = true
                    )
                }
            }
        }

        recognizeDocument(documentId)
    }

    private suspend fun splitBookPageInternal(
        documentId: String,
        sourcePageId: String,
        analysis: BookSpreadAnalysis,
        dewarp: Boolean
    ) {
        val source = dao.getPage(sourcePageId)
            ?: throw IllegalArgumentException("Book source page not found")
        require(!source.deleted && source.sourceSpreadPageId == null) {
            "Book source page is no longer available"
        }
        requireNoCoordinateEdits(source, "splitting this book page")
        val active = orderedPages(dao.getPages(documentId))
        val sourceIndex = active.indexOfFirst { it.id == sourcePageId }
        require(sourceIndex >= 0) { "Book source page is no longer active" }

        val nextPosition = dao.getMaxPagePosition(documentId) + 1
        val nextSortKey = dao.getMaxPageSortKey(documentId) + 1000L
        val leftId = UUID.randomUUID().toString()
        val rightId = UUID.randomUUID().toString()
        val leftFile = files.pageFile(documentId, leftId)
        val rightFile = files.pageFile(documentId, rightId)

        val working = prepareBookWorkingSource(source)
        val rendered = try {
            BookSpreadProcessor.renderSplitPages(
                sourceFile = working.first,
                leftDestination = leftFile,
                rightDestination = rightFile,
                analysis = analysis,
                dewarp = dewarp
            )
        } finally {
            if (working.second) working.first.delete()
        }

        try {
            val pages = rendered.sortedBy { it.side.ordinal }.mapIndexed { index, result ->
                val crop = PageBoundaryDetector.detect(result.file)
                PageEntity(
                    id = if (result.side == BookPageSide.LEFT) leftId else rightId,
                    documentId = documentId,
                    position = nextPosition + index,
                    sortKey = nextSortKey + index * 1000L,
                    deleted = false,
                    rotationDegrees = 0,
                    cropQuad = CropQuadCodec.encode(crop),
                    visualRecipe = source.visualRecipe,
                    cleanupRecipe = null,
                    imagePath = result.file.absolutePath,
                    width = result.width,
                    height = result.height,
                    ocrText = "",
                    ocrLayout = null,
                    ocrFingerprint = null,
                    ocrScript = null,
                    sourceSpreadPageId = sourcePageId,
                    bookSide = result.side.name,
                    bookSplitConfidence = analysis.confidence,
                    bookDewarpStrength = result.dewarpStrength,
                    preservedBookSource = false
                )
            }

            val replacementIds = pages
                .sortedBy { BookPageSide.valueOf(requireNotNull(it.bookSide)).ordinal }
                .map { it.id }
            val newOrder = active.map { it.id }.toMutableList().apply {
                removeAt(sourceIndex)
                addAll(sourceIndex, replacementIds)
            }

            dao.replaceActivePageWithBookPages(
                sourcePageId = sourcePageId,
                derivedPages = pages,
                orderedPageIds = newOrder
            )
            searchIndex.deletePage(sourcePageId)
        } catch (error: Throwable) {
            leftFile.delete()
            rightFile.delete()
            throw error
        }
    }

    private fun prepareBookWorkingSource(page: PageEntity): Pair<File, Boolean> {
        val original = File(page.imagePath)
        val cleanup = PageCleanupRecipeCodec.decode(page.cleanupRecipe)
        if (
            cleanup.isEmpty() &&
            CropQuadCodec.decode(page.cropQuad).isFullFrame() &&
            PageRotation.normalize(page.rotationDegrees) == 0
        ) {
            return original to false
        }

        val bitmap = renderSemanticPageBitmap(
            page = page,
            maxLongEdge = 3600
        )
        val temporary = File.createTempFile(
            "book-source-",
            ".jpg",
            context.cacheDir
        )
        return try {
            temporary.outputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)) {
                    "Unable to prepare edited book page"
                }
            }
            temporary to true
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderSemanticPageBitmap(
        page: PageEntity,
        maxLongEdge: Int
    ): android.graphics.Bitmap {
        val geometry = PageGeometryRenderer.renderUnrotatedForPdf(
            file = File(page.imagePath),
            cropQuad = CropQuadCodec.decode(page.cropQuad),
            maxLongEdge = maxLongEdge
        )
        val cleaned = PageCleanupRenderer.apply(
            geometry,
            PageCleanupRecipeCodec.decode(page.cleanupRecipe)
        )
        if (cleaned !== geometry) geometry.recycle()

        val rotated = PageGeometryRenderer.rotateBitmap(
            cleaned,
            page.rotationDegrees
        )
        if (rotated !== cleaned) cleaned.recycle()
        return rotated
    }

    suspend fun createFolder(
        name: String,
        parentId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Folder name cannot be blank" }
        require(cleanName.length <= 80) { "Folder name is too long" }
        if (parentId != null) {
            require(dao.getFolder(parentId) != null) { "Parent folder no longer exists" }
        }
        val normalized = normalizeOrganizationName(cleanName)
        require(dao.findFolder(normalized, parentId) == null) {
            "A folder with this name already exists here"
        }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertFolder(
            FolderEntity(
                id = id,
                name = cleanName,
                normalizedName = normalized,
                parentId = parentId,
                createdAt = now,
                updatedAt = now
            )
        )
        id
    }

    suspend fun renameFolder(folderId: String, name: String) =
        withContext(Dispatchers.IO) {
            val folder = dao.getFolder(folderId)
                ?: throw IllegalArgumentException("Folder not found")
            val cleanName = name.trim()
            require(cleanName.isNotBlank()) { "Folder name cannot be blank" }
            require(cleanName.length <= 80) { "Folder name is too long" }
            val normalized = normalizeOrganizationName(cleanName)
            val duplicate = dao.findFolder(normalized, folder.parentId)
            require(duplicate == null || duplicate.id == folderId) {
                "A folder with this name already exists here"
            }
            dao.renameFolder(
                id = folderId,
                name = cleanName,
                normalizedName = normalized,
                updatedAt = System.currentTimeMillis()
            )
        }

    suspend fun deleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        require(dao.getFolder(folderId) != null) { "Folder not found" }
        dao.deleteFolderAndPromoteContents(folderId)
    }

    suspend fun createTag(name: String): String = withContext(Dispatchers.IO) {
        val cleanName = name.trim().removePrefix("#").trim()
        require(cleanName.isNotBlank()) { "Tag name cannot be blank" }
        require(cleanName.length <= 40) { "Tag name is too long" }
        val normalized = normalizeOrganizationName(cleanName)
        dao.findTag(normalized)?.let { return@withContext it.id }

        val id = UUID.randomUUID().toString()
        dao.insertTag(
            TagEntity(
                id = id,
                name = cleanName,
                normalizedName = normalized,
                createdAt = System.currentTimeMillis()
            )
        )
        id
    }

    suspend fun deleteTag(tagId: String) = withContext(Dispatchers.IO) {
        dao.deleteTag(tagId)
    }

    suspend fun setDocumentFolder(
        documentIds: List<String>,
        folderId: String?
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        if (folderId != null) {
            require(dao.getFolder(folderId) != null) { "Folder no longer exists" }
        }
        dao.setDocumentFolder(ids, folderId, System.currentTimeMillis())
    }

    suspend fun setDocumentType(
        documentIds: List<String>,
        type: DocumentType
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        dao.setDocumentType(ids, type.name, System.currentTimeMillis())
    }

    suspend fun setDocumentsNeedsReview(
        documentIds: List<String>,
        needsReview: Boolean
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        dao.setDocumentsNeedsReview(ids, needsReview, System.currentTimeMillis())
    }

    suspend fun setDocumentsFavorite(
        documentIds: List<String>,
        favorite: Boolean
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        dao.setDocumentsFavorite(ids, favorite, System.currentTimeMillis())
    }

    suspend fun setDocumentsArchived(
        documentIds: List<String>,
        archived: Boolean
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        dao.setDocumentsArchived(ids, archived, System.currentTimeMillis())
    }

    suspend fun addTagsToDocuments(
        documentIds: List<String>,
        tagIds: List<String>
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        val validTags = dao.getTags().map { it.id }.toSet()
        require(tagIds.all { it in validTags }) { "A selected tag no longer exists" }
        dao.addDocumentTags(ids, tagIds.distinct())
    }

    suspend fun replaceDocumentTags(
        documentIds: List<String>,
        tagIds: List<String>
    ) = withContext(Dispatchers.IO) {
        val ids = editableDocumentIds(documentIds)
        val validTags = dao.getTags().map { it.id }.toSet()
        require(tagIds.all { it in validTags }) { "A selected tag no longer exists" }
        dao.replaceDocumentTags(ids, tagIds.distinct())
    }

    suspend fun acceptSuggestedType(documentId: String) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        val suggestion = document.suggestedType
            ?.let { DocumentType.valueOf(it) }
            ?: return@withContext
        dao.setDocumentType(
            documentIds = listOf(documentId),
            documentType = suggestion.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun setScanMode(
        documentId: String,
        scanMode: ScanMode,
        applyEnhancementDefaults: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }
        val oldMode = ScanMode.fromStored(document.scanMode)
        if (oldMode == scanMode && !applyEnhancementDefaults) return@withContext

        val oldProfile = ScanModeProfiles.forMode(oldMode)
        val profile = ScanModeProfiles.forMode(scanMode)
        val pages = orderedPages(dao.getPages(documentId))
        if (!profile.ocrEnabled) {
            pages.forEach { requireNoTextEdits(it, "changing to this scan mode") }
        }
        if (scanMode == ScanMode.BOOK) {
            pages.forEach { requireNoCoordinateEdits(it, "changing to Book mode") }
        }
        dao.setDocumentScanMode(
            id = documentId,
            scanMode = scanMode.name,
            documentType = profile.defaultDocumentType.name,
            needsReview = false,
            updatedAt = System.currentTimeMillis()
        )

        if (applyEnhancementDefaults) {
            val recipe = PageVisualRecipeCodec.encode(
                PageVisualRecipe.forPreset(profile.defaultPreset)
            )
            pages.forEach { page ->
                dao.setPageVisualRecipe(page.id, recipe)
            }
        }

        when {
            !profile.ocrEnabled -> {
                pages.forEach { page ->
                    dao.clearPageOcr(page.id)
                    searchIndex.deletePage(page.id)
                }
                dao.finishProcessing(
                    id = documentId,
                    text = "",
                    processing = false,
                    pageCount = pages.size,
                    updatedAt = System.currentTimeMillis()
                )
                refreshSpecializedFields(documentId)
            }

            scanMode == ScanMode.BOOK -> {
                dao.setProcessing(documentId, true, System.currentTimeMillis())
                appScope.launch(Dispatchers.IO) {
                    processBookDocument(documentId)
                }
            }

            !oldProfile.ocrEnabled -> {
                pages.forEach { page ->
                    dao.clearPageOcr(page.id)
                    searchIndex.deletePage(page.id)
                }
                dao.setProcessing(documentId, true, System.currentTimeMillis())
                appScope.launch(Dispatchers.IO) {
                    recognizeDocument(documentId)
                }
            }

            else -> {
                refreshTypeSuggestion(documentId)
                refreshSpecializedFields(documentId)
            }
        }
    }

    suspend fun defaultPdfQuality(documentId: String): PdfQuality =
        withContext(Dispatchers.IO) {
            val document = dao.getDocument(documentId)
                ?: throw IllegalArgumentException("Document not found")
            ScanModeProfiles.forMode(
                ScanMode.fromStored(document.scanMode)
            ).defaultPdfQuality
        }

    suspend fun ensureSpatialOcr(documentId: String) =
        withContext(Dispatchers.IO) {
            val document = dao.getDocument(documentId) ?: return@withContext
            if (document.trashedAt != null || document.processing) return@withContext

            val mode = ScanMode.fromStored(document.scanMode)
            if (!ScanModeProfiles.forMode(mode).ocrEnabled) return@withContext
            val script = OcrScript.fromStored(document.ocrScript)
            val stalePages = orderedPages(dao.getPages(documentId)).filter { page ->
                page.ocrLayout.isNullOrBlank() ||
                    page.ocrFingerprint.isNullOrBlank() ||
                    page.ocrScript != script.name
            }
            if (stalePages.isEmpty()) return@withContext

            dao.setProcessing(documentId, true, System.currentTimeMillis())
            appScope.launch(Dispatchers.IO) {
                recognizePagesAndRefresh(documentId, stalePages.map { it.id })
            }
        }

    suspend fun setOcrScript(documentId: String, script: OcrScript) =
        withContext(Dispatchers.IO) {
            val document = requireEditableDocument(documentId)
            require(!document.processing) { "Document is still processing" }
            if (document.ocrScript == script.name) return@withContext

            val pages = orderedPages(dao.getPages(documentId))
            pages.forEach { requireNoTextEdits(it, "changing the OCR language") }
            dao.setDocumentOcrScript(
                id = documentId,
                script = script.name,
                updatedAt = System.currentTimeMillis()
            )
            pages.forEach { page ->
                dao.clearPageOcr(page.id)
                searchIndex.deletePage(page.id)
            }
            refreshDocumentSummary(documentId, processing = true)
            appScope.launch(Dispatchers.IO) {
                recognizeDocument(documentId)
            }
        }

    suspend fun searchDocuments(
        filter: LibraryFilter,
        query: String
    ): List<DocumentEntity> = withContext(Dispatchers.IO) {
        searchIndex.searchDocumentIds(filter, query)
            .mapNotNull { dao.getDocument(it) }
    }

    suspend fun searchDocumentPages(
        documentId: String,
        query: String
    ): List<DocumentPageSearchHit> = withContext(Dispatchers.IO) {
        val pages = orderedPages(dao.getPages(documentId))
        val numberById = pages.mapIndexed { index, page -> page.id to (index + 1) }.toMap()
        searchIndex.searchPages(documentId, query).mapNotNull { indexed ->
            val page = pages.firstOrNull { it.id == indexed.pageId } ?: return@mapNotNull null
            val layout = OcrLayoutCodec.decode(page.ocrLayout)
            DocumentPageSearchHit(
                pageId = indexed.pageId,
                pageNumber = numberById[indexed.pageId] ?: page.position + 1,
                snippet = indexed.snippet,
                rank = indexed.rank,
                matchingWords = OcrSearchTerms.matchingWords(layout, query)
            )
        }
    }

    suspend fun rename(id: String, title: String) {
        requireEditableDocument(id)
        dao.rename(id, title.trim().ifBlank { "Scan" }, System.currentTimeMillis())
        refreshTypeSuggestion(id)
    }

    suspend fun setFavorite(id: String, value: Boolean) {
        requireEditableDocument(id)
        dao.setFavorite(id, value, System.currentTimeMillis())
    }

    suspend fun setArchived(id: String, value: Boolean) {
        requireEditableDocument(id)
        dao.setArchived(id, value, System.currentTimeMillis())
    }

    suspend fun trashDocument(id: String) = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext
        if (document.trashedAt != null) return@withContext
        require(!document.processing) { "Wait for document processing to finish before moving it to Trash" }
        val now = System.currentTimeMillis()
        dao.setTrashed(id, now, now)
    }

    suspend fun restoreDocument(id: String) = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext
        if (document.trashedAt == null) return@withContext
        dao.setTrashed(id, null, System.currentTimeMillis())
    }

    suspend fun deleteForever(id: String) = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext
        require(document.trashedAt != null) { "Move the document to Trash before deleting it forever" }
        searchIndex.deleteDocument(id)
        dao.deleteDocument(id)
        files.deleteDocument(id)
        files.deleteExportsForDocument(id)
    }

    suspend fun movePage(documentId: String, pageId: String, direction: Int) = withContext(Dispatchers.IO) {
        require(direction == -1 || direction == 1) { "Invalid page move" }
        val document = dao.getDocument(documentId) ?: return@withContext
        require(document.trashedAt == null) { "Restore the document before editing it" }
        require(!document.processing) { "Document is still processing" }

        val pages = dao.getPages(documentId).toMutableList()
        val currentIndex = pages.indexOfFirst { it.id == pageId }
        require(currentIndex >= 0) { "Page not found" }
        val targetIndex = currentIndex + direction
        if (targetIndex !in pages.indices) return@withContext

        val moved = pages.removeAt(currentIndex)
        pages.add(targetIndex, moved)
        dao.replacePageOrder(documentId, pages.map { it.id })
        refreshDocumentSummary(documentId)
    }

    suspend fun softDeletePage(documentId: String, pageId: String) {
        softDeletePages(documentId, listOf(pageId))
    }

    suspend fun softDeletePages(documentId: String, pageIds: List<String>) =
        withContext(Dispatchers.IO) {
            val document = dao.getDocument(documentId) ?: return@withContext
            require(document.trashedAt == null) { "Restore the document before editing it" }
            require(!document.processing) { "Document is still processing" }

            val requested = pageIds.distinct()
            require(requested.isNotEmpty()) { "Select at least one page" }
            val pages = orderedPages(dao.getPages(documentId))
            val selected = pages.filter { it.id in requested }
            require(selected.size == requested.size) { "One or more selected pages are unavailable" }
            require(pages.size - selected.size >= 1) { "A document must keep at least one page" }

            dao.setPagesDeleted(selected.map { it.id }, true)
            selected.forEach { searchIndex.deletePage(it.id) }
            refreshDocumentSummary(documentId)
        }

    suspend fun restorePage(documentId: String, pageId: String) {
        restorePages(documentId, listOf(pageId))
    }

    suspend fun restorePages(documentId: String, pageIds: List<String>) =
        withContext(Dispatchers.IO) {
            val document = dao.getDocument(documentId) ?: return@withContext
            require(document.trashedAt == null) { "Restore the document before editing it" }
            require(!document.processing) { "Document is still processing" }

            val requested = pageIds.distinct()
            require(requested.isNotEmpty()) { "Select at least one page" }
            val deleted = dao.getDeletedPages(documentId)
            val selected = deleted.filter { it.id in requested }
            require(selected.size == requested.size) { "One or more deleted pages are unavailable" }

            dao.setPagesDeleted(selected.map { it.id }, false)
            val activeScript = OcrScript.fromStored(document.ocrScript)
            val stale = selected.filter { it.ocrScript != activeScript.name || it.ocrLayout.isNullOrBlank() }
            val reusable = selected - stale.toSet()
            reusable.forEach {
                searchIndex.upsertPage(documentId, it.id, it.ocrText)
            }
            if (stale.isEmpty()) {
                refreshDocumentSummary(documentId)
            } else {
                stale.forEach {
                    dao.clearPageOcr(it.id)
                    searchIndex.deletePage(it.id)
                }
                refreshDocumentSummary(documentId, processing = true)
                appScope.launch(Dispatchers.IO) {
                    recognizePagesAndRefresh(documentId, stale.map { it.id })
                }
            }
        }

    suspend fun document(id: String): DocumentEntity? = dao.getDocument(id)

    suspend fun createPdfExport(
        id: String,
        password: String? = null,
        quality: PdfQuality = PdfQuality.ORIGINAL
    ): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        require(document.trashedAt == null) { "Restore the document before exporting it" }
        val pages = orderedPages(dao.getPages(id))
        val deletedPages = dao.getDeletedPages(id)
        val source = nativePdfSource(document, pages)
        val includeOcrTextLayer = ScanModeProfiles.forMode(
            ScanMode.fromStored(document.scanMode)
        ).ocrEnabled

        val destination = files.pdfExportFile(
            documentId = id,
            title = document.title,
            protected = !password.isNullOrBlank()
        )

        val hasGeometryEdits = pages.any { !CropQuadCodec.decode(it.cropQuad).isFullFrame() }
        val hasVisualEdits = pages.any {
            !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal() ||
                !PageCleanupRecipeCodec.decode(it.cleanupRecipe).isEmpty() ||
                !PageTextEditRecipeCodec.decode(it.textEditRecipe).isEmpty() ||
                !PageMarkupRecipeCodec.decode(it.markupRecipe).isEmpty() ||
                PageFormRecipeCodec.decode(it.formFillRecipe).hasFillContent()
        }

        if (
            source != null &&
            quality == PdfQuality.ORIGINAL &&
            !hasGeometryEdits &&
            !hasVisualEdits
        ) {
            val nativeOrder = pages.map { it.position }
            val rotations = pages.map { it.rotationDegrees }
            val unchanged = deletedPages.isEmpty() &&
                nativeOrder == (0 until pages.size).toList() &&
                rotations.all { it == 0 }

            if (unchanged) {
                if (password.isNullOrBlank()) {
                    return@withContext files.copyToExport(source, destination)
                }
                val temporary = files.temporaryExport(destination)
                runCatching {
                    pdfEngine.protectExisting(source, temporary, password)
                    files.commitGeneratedExport(temporary, destination)
                }.getOrElse {
                    temporary.delete()
                    throw it
                }
            } else {
                val temporary = files.temporaryExport(destination)
                runCatching {
                    pdfEngine.extractPages(
                        source = source,
                        pageIndices = nativeOrder,
                        destination = temporary,
                        password = password,
                        rotationDeltas = rotations
                    )
                    files.commitGeneratedExport(temporary, destination)
                }.getOrElse {
                    temporary.delete()
                    throw it
                }
            }
        } else if (pages.isNotEmpty()) {
            val temporary = files.temporaryExport(destination)
            runCatching {
                pdfEngine.createSearchablePdf(
                    pages = pages,
                    destination = temporary,
                    password = password,
                    quality = quality,
                    includeOcrTextLayer = includeOcrTextLayer
                )
                files.commitGeneratedExport(temporary, destination)
            }.getOrElse {
                temporary.delete()
                throw it
            }
        } else {
            null
        }
    }

    suspend fun extractPages(id: String, rangeSpec: String): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        require(document.trashedAt == null) { "Restore the document before exporting it" }
        require(!document.processing) { "Document is still processing" }
        val pages = orderedPages(dao.getPages(id))
        if (pages.isEmpty()) return@withContext null

        val selectedNumbers = PageRangeParser.parse(rangeSpec, pages.size)
        val selectedPages = selectedNumbers.map { pageNumber -> pages[pageNumber - 1] }
        val destination = files.extractedPdfExportFile(id, document.title)
        val temporary = files.temporaryExport(destination)
        val source = nativePdfSource(document, pages)
        val includeOcrTextLayer = ScanModeProfiles.forMode(
            ScanMode.fromStored(document.scanMode)
        ).ocrEnabled

        runCatching {
            val hasGeometryEdits = selectedPages.any {
                !CropQuadCodec.decode(it.cropQuad).isFullFrame()
            }
            val hasVisualEdits = selectedPages.any {
                !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal() ||
                    !PageCleanupRecipeCodec.decode(it.cleanupRecipe).isEmpty() ||
                    !PageTextEditRecipeCodec.decode(it.textEditRecipe).isEmpty() ||
                !PageMarkupRecipeCodec.decode(it.markupRecipe).isEmpty() ||
                PageFormRecipeCodec.decode(it.formFillRecipe).hasFillContent()
            }
            if (source != null && !hasGeometryEdits && !hasVisualEdits) {
                pdfEngine.extractPages(
                    source = source,
                    pageIndices = selectedPages.map { it.position },
                    destination = temporary,
                    rotationDeltas = selectedPages.map { it.rotationDegrees }
                )
            } else {
                pdfEngine.createSearchablePdf(
                    pages = selectedPages,
                    destination = temporary,
                    includeOcrTextLayer = includeOcrTextLayer
                )
            }
            files.commitGeneratedExport(temporary, destination)
        }.getOrElse {
            temporary.delete()
            throw it
        }
    }

    suspend fun createPdfExportForPages(
        id: String,
        pageIds: List<String>,
        quality: PdfQuality = PdfQuality.ORIGINAL
    ): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        require(document.trashedAt == null) { "Restore the document before exporting it" }
        require(!document.processing) { "Document is still processing" }

        val requested = pageIds.distinct()
        require(requested.isNotEmpty()) { "Select at least one page" }
        val pages = orderedPages(dao.getPages(id))
        val selected = pages.filter { it.id in requested }
        require(selected.size == requested.size) { "One or more selected pages are unavailable" }

        val destination = files.selectedPdfExportFile(id, document.title)
        val temporary = files.temporaryExport(destination)
        val source = nativePdfSource(document, pages)
        val includeOcrTextLayer = ScanModeProfiles.forMode(
            ScanMode.fromStored(document.scanMode)
        ).ocrEnabled
        val hasGeometryEdits = selected.any {
            !CropQuadCodec.decode(it.cropQuad).isFullFrame()
        }
        val hasVisualEdits = selected.any {
            !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal() ||
                !PageCleanupRecipeCodec.decode(it.cleanupRecipe).isEmpty() ||
                !PageTextEditRecipeCodec.decode(it.textEditRecipe).isEmpty() ||
                !PageMarkupRecipeCodec.decode(it.markupRecipe).isEmpty() ||
                PageFormRecipeCodec.decode(it.formFillRecipe).hasFillContent()
        }

        runCatching {
            if (
                source != null &&
                quality == PdfQuality.ORIGINAL &&
                !hasGeometryEdits &&
                !hasVisualEdits
            ) {
                pdfEngine.extractPages(
                    source = source,
                    pageIndices = selected.map { it.position },
                    destination = temporary,
                    rotationDeltas = selected.map { it.rotationDegrees }
                )
            } else {
                pdfEngine.createSearchablePdf(
                    pages = selected,
                    destination = temporary,
                    quality = quality,
                    includeOcrTextLayer = includeOcrTextLayer
                )
            }
            files.commitGeneratedExport(temporary, destination)
        }.getOrElse {
            temporary.delete()
            throw it
        }
    }

    suspend fun createTextExportForPages(
        id: String,
        pageIds: List<String>
    ): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        require(document.trashedAt == null) { "Restore the document before exporting it" }
        val requested = pageIds.distinct()
        require(requested.isNotEmpty()) { "Select at least one page" }
        val pages = orderedPages(dao.getPages(id))
        val selected = pages.filter { it.id in requested }
        require(selected.size == requested.size) { "One or more selected pages are unavailable" }

        val output = files.selectedTextExportFile(id, document.title)
        output.parentFile?.mkdirs()
        output.writeText(
            selected.mapIndexed { index, page ->
                buildString {
                    append("Page ${index + 1}\n\n")
                    append(page.ocrText.ifBlank { "[No recognized text]" })
                }
            }.joinToString("\n\n──────────\n\n")
        )
        output
    }

    suspend fun mergeDocuments(ids: List<String>): File? = withContext(Dispatchers.IO) {
        val orderedIds = ids.distinct()
        require(orderedIds.size >= 2) { "Select at least two documents" }

        val mergeInputs = mutableListOf<File>()
        val temporaryInputs = mutableListOf<File>()
        try {
            orderedIds.forEach { id ->
                val document = dao.getDocument(id)
                    ?: throw IllegalArgumentException("A selected document no longer exists")
                require(document.trashedAt == null) { "${document.title} is in Trash" }
                require(!document.processing) { "${document.title} is still processing" }
                val pages = orderedPages(dao.getPages(id))
                val deleted = dao.getDeletedPages(id)
                val nativeSource = nativePdfSource(document, pages)
                val includeOcrTextLayer = ScanModeProfiles.forMode(
                    ScanMode.fromStored(document.scanMode)
                ).ocrEnabled

                if (nativeSource != null) {
                    val nativeOrder = pages.map { it.position }
                    val rotations = pages.map { it.rotationDegrees }
                    val hasGeometryEdits = pages.any {
                        !CropQuadCodec.decode(it.cropQuad).isFullFrame()
                    }
                    val hasVisualEdits = pages.any {
                        !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal() ||
                            !PageCleanupRecipeCodec.decode(it.cleanupRecipe).isEmpty() ||
                            !PageTextEditRecipeCodec.decode(it.textEditRecipe).isEmpty() ||
                !PageMarkupRecipeCodec.decode(it.markupRecipe).isEmpty() ||
                PageFormRecipeCodec.decode(it.formFillRecipe).hasFillContent()
                    }
                    val unchanged = deleted.isEmpty() &&
                        nativeOrder == (0 until pages.size).toList() &&
                        rotations.all { it == 0 } &&
                        !hasGeometryEdits &&
                        !hasVisualEdits

                    if (unchanged) {
                        mergeInputs += nativeSource
                    } else if (!hasGeometryEdits && !hasVisualEdits) {
                        val working = files.temporaryWorkingPdf("scan-native-edit")
                        pdfEngine.extractPages(
                            source = nativeSource,
                            pageIndices = nativeOrder,
                            destination = working,
                            rotationDeltas = rotations
                        )
                        mergeInputs += working
                        temporaryInputs += working
                    } else {
                        val working = files.temporaryWorkingPdf("scan-geometry")
                        pdfEngine.createSearchablePdf(
                            pages = pages,
                            destination = working,
                            includeOcrTextLayer = includeOcrTextLayer
                        )
                        mergeInputs += working
                        temporaryInputs += working
                    }
                } else {
                    require(pages.isNotEmpty()) { "${document.title} has no pages" }
                    val working = files.temporaryWorkingPdf("scan-merge")
                    pdfEngine.createSearchablePdf(
                        pages = pages,
                        destination = working,
                        includeOcrTextLayer = includeOcrTextLayer
                    )
                    mergeInputs += working
                    temporaryInputs += working
                }
            }

            val destination = files.mergedPdfExportFile()
            val temporaryOutput = files.temporaryExport(destination)
            runCatching {
                pdfEngine.merge(mergeInputs, temporaryOutput)
                files.commitGeneratedExport(temporaryOutput, destination)
            }.getOrElse {
                temporaryOutput.delete()
                throw it
            }
        } finally {
            temporaryInputs.forEach { it.delete() }
        }
    }

    suspend fun saveExportToUri(file: File, destination: Uri) = withContext(Dispatchers.IO) {
        require(file.isFile) { "Export file is unavailable" }
        context.contentResolver.openOutputStream(destination, "w").use { output ->
            requireNotNull(output) { "Unable to open the selected destination" }
            file.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        }
    }

    suspend fun createTextExport(id: String): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        require(document.trashedAt == null) { "Restore the document before exporting it" }
        val pages = orderedPages(dao.getPages(id))
        val output = files.textExportFile(id, document.title)
        output.parentFile?.mkdirs()
        output.writeText(
            pages.mapIndexed { index, page ->
                buildString {
                    append("Page ${index + 1}\n\n")
                    append(page.ocrText.ifBlank { "[No recognized text]" })
                }
            }.joinToString("\n\n──────────\n\n")
        )
        output
    }

    private fun kickProcessingQueue() {
        appScope.launch(Dispatchers.IO) {
            processingQueueMutex.withLock {
                drainHighSpeedProcessingQueue()
            }
        }
    }

    private suspend fun drainHighSpeedProcessingQueue() {
        while (true) {
            val sessions = dao.getCaptureSessionsByStatus(
                listOf(
                    CaptureSessionStatus.PROCESSING.name,
                    CaptureSessionStatus.PAUSED.name,
                    CaptureSessionStatus.INTERRUPTED.name
                )
            )
            if (sessions.isEmpty()) return

            var completedAny = false
            for (snapshot in sessions) {
                val session = dao.getCaptureSession(snapshot.id) ?: continue
                if (session.status == CaptureSessionStatus.CAPTURING.name) {
                    continue
                }

                val completed = runCatching {
                    processHighSpeedSession(session)
                }.getOrElse { error ->
                    failHighSpeedSession(session, error)
                    true
                }
                if (!completed) return
                completedAny = true
            }

            if (!completedAny) return
        }
    }

    private suspend fun processHighSpeedSession(
        session: CaptureSessionEntity
    ): Boolean {
        val document = dao.getDocument(session.documentId) ?: return true
        val mode = ScanMode.fromStored(session.scanMode)

        if (dao.getActiveCaptureSessionCount() > 0) {
            dao.updateCaptureSessionState(
                sessionId = session.id,
                status = CaptureSessionStatus.PAUSED.name,
                updatedAt = System.currentTimeMillis(),
                completedAt = null,
                pausedReason = "Background processing deferred while rapid capture is active."
            )
            scheduleHighSpeedRetry(30_000L)
            return false
        }

        val budget = highSpeedPolicy.currentBudget(mode)

        if (!budget.canProcess) {
            val now = System.currentTimeMillis()
            dao.updateCaptureSessionState(
                sessionId = session.id,
                status = CaptureSessionStatus.PAUSED.name,
                updatedAt = now,
                completedAt = null,
                pausedReason = budget.pausedReason
            )
            scheduleHighSpeedRetry(budget.retryDelayMillis)
            return false
        }

        if (
            session.status == CaptureSessionStatus.PAUSED.name ||
            session.status == CaptureSessionStatus.INTERRUPTED.name
        ) {
            dao.updateCaptureSessionState(
                sessionId = session.id,
                status = CaptureSessionStatus.PROCESSING.name,
                updatedAt = System.currentTimeMillis(),
                completedAt = null,
                pausedReason = null
            )
        }

        val fingerprintStatuses = listOf(
            PageProcessingStatus.QUEUED.name,
            PageProcessingStatus.FINGERPRINTING.name,
            PageProcessingStatus.PROCESSING.name
        )
        val jobs = dao.getSessionProcessingJobsByStatus(
            session.id,
            fingerprintStatuses
        )

        jobs.filter {
            it.fingerprintHash == null &&
                it.status != PageProcessingStatus.COMPLETE.name &&
                it.status != PageProcessingStatus.DUPLICATE.name
        }.forEach { job ->
            val now = System.currentTimeMillis()
            dao.updateProcessingJobState(
                pageId = job.pageId,
                status = PageProcessingStatus.FINGERPRINTING.name,
                updatedAt = now,
                attemptIncrement = 1
            )

            val page = dao.getPage(job.pageId)
            if (page == null) {
                dao.updateProcessingJobState(
                    pageId = job.pageId,
                    status = PageProcessingStatus.FAILED.name,
                    updatedAt = System.currentTimeMillis(),
                    lastError = "Captured page is missing"
                )
                return@forEach
            }

            val fingerprint = runCatching {
                CaptureFrameAnalyzer.analyze(File(page.imagePath))
            }.getOrElse { error ->
                dao.updateProcessingJobState(
                    pageId = job.pageId,
                    status = PageProcessingStatus.FAILED.name,
                    updatedAt = System.currentTimeMillis(),
                    lastError = error.message ?: "Capture analysis failed"
                )
                return@forEach
            }

            val duplicate = dao.getReferenceFingerprints(document.id)
                .firstOrNull { reference ->
                    reference.pageId != job.pageId &&
                        CaptureFrameAnalyzer.isLikelyDuplicate(
                            candidate = fingerprint,
                            referenceHashHex = reference.fingerprintHash,
                            referenceMeanLuma = reference.meanLuma,
                            referenceEdgeEnergy = reference.edgeEnergy,
                            referenceAspectRatio = reference.aspectRatio
                        )
                }

            if (duplicate != null) {
                dao.markCapturedPageDuplicate(
                    pageId = job.pageId,
                    fingerprintHash = fingerprint.hashHex,
                    meanLuma = fingerprint.meanLuma,
                    edgeEnergy = fingerprint.edgeEnergy,
                    aspectRatio = fingerprint.aspectRatio,
                    qualityScore = fingerprint.qualityScore,
                    duplicateOfPageId = duplicate.pageId,
                    updatedAt = System.currentTimeMillis()
                )
                searchIndex.deletePage(job.pageId)
                File(page.imagePath).delete()
            } else {
                dao.updateProcessingFingerprint(
                    pageId = job.pageId,
                    status = PageProcessingStatus.PROCESSING.name,
                    fingerprintHash = fingerprint.hashHex,
                    meanLuma = fingerprint.meanLuma,
                    edgeEnergy = fingerprint.edgeEnergy,
                    aspectRatio = fingerprint.aspectRatio,
                    qualityScore = fingerprint.qualityScore,
                    duplicateOfPageId = null,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }

        dao.getSessionProcessingJobsByStatus(
            session.id,
            listOf(PageProcessingStatus.QUEUED.name)
        )
            .filter { it.fingerprintHash != null }
            .forEach { job ->
                dao.updateProcessingJobState(
                    pageId = job.pageId,
                    status = PageProcessingStatus.PROCESSING.name,
                    updatedAt = System.currentTimeMillis(),
                    lastError = null
                )
            }

        dao.refreshCaptureSessionCounters(
            session.id,
            System.currentTimeMillis()
        )

        val accepted = dao.getSessionProcessingJobsByStatus(
            session.id,
            listOf(PageProcessingStatus.PROCESSING.name)
        )
        if (accepted.isNotEmpty()) {
            val chunks = if (mode == ScanMode.BOOK) {
                listOf(accepted)
            } else {
                accepted.chunked(budget.maxPagesPerChunk)
            }

            for ((index, chunk) in chunks.withIndex()) {
                val currentBudget = highSpeedPolicy.currentBudget(mode)
                if (!currentBudget.canProcess) {
                    dao.updateCaptureSessionState(
                        sessionId = session.id,
                        status = CaptureSessionStatus.PAUSED.name,
                        updatedAt = System.currentTimeMillis(),
                        completedAt = null,
                        pausedReason = currentBudget.pausedReason
                    )
                    scheduleHighSpeedRetry(currentBudget.retryDelayMillis)
                    return false
                }

                val pageIds = chunk.mapNotNull { job ->
                    dao.getPage(job.pageId)?.id
                }
                if (pageIds.isEmpty()) {
                    chunk.forEach { job ->
                        dao.updateProcessingJobState(
                            pageId = job.pageId,
                            status = PageProcessingStatus.FAILED.name,
                            updatedAt = System.currentTimeMillis(),
                            lastError = "Captured page is unavailable"
                        )
                    }
                    continue
                }

                dao.setProcessing(
                    document.id,
                    true,
                    System.currentTimeMillis()
                )
                runCatching {
                    if (mode == ScanMode.BOOK) {
                        processBookPagesAndRecognize(document.id, pageIds)
                    } else {
                        recognizePagesAndRefresh(document.id, pageIds)
                    }
                }
                    .onSuccess {
                        chunk.forEach { job ->
                            dao.updateProcessingJobState(
                                pageId = job.pageId,
                                status = PageProcessingStatus.COMPLETE.name,
                                updatedAt = System.currentTimeMillis(),
                                lastError = null
                            )
                        }
                    }
                    .onFailure { error ->
                        chunk.forEach { job ->
                            dao.updateProcessingJobState(
                                pageId = job.pageId,
                                status = PageProcessingStatus.FAILED.name,
                                updatedAt = System.currentTimeMillis(),
                                lastError = error.message ?: "Page processing failed"
                            )
                        }
                    }

                dao.refreshCaptureSessionCounters(
                    session.id,
                    System.currentTimeMillis()
                )

                if (index < chunks.lastIndex) {
                    delay(25L)
                }
            }
        }

        dao.refreshCaptureSessionCounters(
            session.id,
            System.currentTimeMillis()
        )
        val latest = dao.getCaptureSession(session.id) ?: return true
        val remaining = dao.getSessionProcessingJobsByStatus(
            session.id,
            listOf(
                PageProcessingStatus.QUEUED.name,
                PageProcessingStatus.FINGERPRINTING.name,
                PageProcessingStatus.PROCESSING.name
            )
        )
        if (remaining.isNotEmpty()) {
            return true
        }

        refreshDocumentSummary(document.id)
        val finished = dao.getCaptureSession(session.id) ?: latest
        if (finished.lowQualityCount > 0 || finished.failedCount > 0) {
            dao.setDocumentsNeedsReview(
                documentIds = listOf(document.id),
                needsReview = true,
                updatedAt = System.currentTimeMillis()
            )
        }

        val now = System.currentTimeMillis()
        dao.updateCaptureSessionState(
            sessionId = session.id,
            status = if (finished.failedCount > 0) {
                CaptureSessionStatus.FAILED.name
            } else {
                CaptureSessionStatus.COMPLETE.name
            },
            updatedAt = now,
            completedAt = now,
            pausedReason = null
        )
        return true
    }

    private suspend fun failHighSpeedSession(
        session: CaptureSessionEntity,
        error: Throwable
    ) {
        val now = System.currentTimeMillis()
        dao.getSessionProcessingJobsByStatus(
            session.id,
            listOf(
                PageProcessingStatus.QUEUED.name,
                PageProcessingStatus.FINGERPRINTING.name,
                PageProcessingStatus.PROCESSING.name
            )
        ).forEach { job ->
            dao.updateProcessingJobState(
                pageId = job.pageId,
                status = PageProcessingStatus.FAILED.name,
                updatedAt = now,
                lastError = error.message ?: "Background processing failed"
            )
        }
        dao.refreshCaptureSessionCounters(session.id, now)
        dao.updateCaptureSessionState(
            sessionId = session.id,
            status = CaptureSessionStatus.FAILED.name,
            updatedAt = now,
            completedAt = now,
            pausedReason = null
        )
        refreshDocumentSummary(session.documentId)
        dao.setDocumentsNeedsReview(
            documentIds = listOf(session.documentId),
            needsReview = true,
            updatedAt = now
        )
    }

    private fun scheduleHighSpeedRetry(delayMillis: Long) {
        appScope.launch(Dispatchers.IO) {
            delay(delayMillis.coerceAtLeast(30_000L))
            kickProcessingQueue()
        }
    }

    private suspend fun requireEditableDocument(id: String): DocumentEntity {
        val document = dao.getDocument(id) ?: throw IllegalArgumentException("Document not found")
        require(document.trashedAt == null) { "Restore the document before editing it" }
        return document
    }

    private fun requireNoTextEdits(
        page: PageEntity,
        action: String
    ) {
        require(page.textEditRecipe.isNullOrBlank()) {
            "Revert OCR text edits before $action"
        }
    }

    private fun requireNoCoordinateEdits(page: PageEntity, action: String) {
        requireNoTextEdits(page, action)
        require(page.markupRecipe.isNullOrBlank()) {
            "Revert page markup before $action"
        }
        require(page.formFillRecipe.isNullOrBlank()) {
            "Clear form fields before $action"
        }
    }

    private suspend fun refreshDocumentSummary(
        documentId: String,
        processing: Boolean = false
    ) {
        val pages = orderedPages(dao.getPages(documentId))
        dao.finishProcessing(
            id = documentId,
            text = pages.map { it.ocrText }.filter { it.isNotBlank() }.joinToString("\n\n"),
            processing = processing,
            pageCount = pages.size,
            updatedAt = System.currentTimeMillis()
        )
        if (!processing) {
            refreshTypeSuggestion(documentId)
            refreshSpecializedFields(documentId)
        }
    }

    private suspend fun refreshTypeSuggestion(documentId: String) {
        val document = dao.getDocument(documentId) ?: return
        if (DocumentType.fromStored(document.documentType) != DocumentType.UNSPECIFIED) {
            if (document.suggestedType != null) {
                dao.setSuggestedDocumentType(
                    documentId,
                    null,
                    document.needsReview
                )
            }
            return
        }

        val suggestion = DocumentClassifier.suggest(document.title, document.ocrText)
        dao.setSuggestedDocumentType(
            id = documentId,
            suggestedType = suggestion?.type?.name,
            needsReview = document.needsReview || suggestion != null
        )
    }

    private suspend fun invalidateBookAnalysisIfOriginal(
        document: DocumentEntity,
        page: PageEntity
    ) {
        if (
            ScanMode.fromStored(document.scanMode) == ScanMode.BOOK &&
            page.sourceSpreadPageId == null
        ) {
            dao.clearBookAnalysis(page.id)
        }
    }

    private suspend fun refreshSpecializedFields(documentId: String) {
        val document = dao.getDocument(documentId) ?: return
        val mode = ScanMode.fromStored(document.scanMode)
        val profile = ScanModeProfiles.forMode(mode)
        val pages = orderedPages(dao.getPages(documentId))
        val extracted = SpecializedFieldExtractor.extract(
            mode = mode,
            title = document.title,
            ocrText = document.ocrText
        )

        val warnings = mutableListOf<String>()
        if (profile.requiresTwoSidedCapture) {
            when {
                pages.size < 2 -> warnings += "Back side has not been captured."
                pages.size > 2 -> warnings += "ID Card mode expects exactly two active pages."
            }
        }
        profile.pageLimit?.let { limit ->
            if (
                mode != ScanMode.BOOK &&
                !profile.requiresTwoSidedCapture &&
                pages.size > limit
            ) {
                warnings += "${profile.mode.label} mode expects at most $limit active page${if (limit == 1) "" else "s"}."
            }
        }
        pages.forEach { page ->
            ScanModeProfiles.aspectRatioWarning(
                mode = mode,
                width = page.width,
                height = page.height
            )?.let(warnings::add)
            ScanModeProfiles.resolutionWarning(
                mode = mode,
                width = page.width,
                height = page.height
            )?.let(warnings::add)
        }

        val rapidFields = mutableListOf<DocumentFieldEntity>()
        val latestCaptureSession = dao.getLatestCaptureSession(documentId)
        if (
            latestCaptureSession != null &&
            latestCaptureSession.capturedCount > 0
        ) {
            rapidFields += DocumentFieldEntity(
                documentId = documentId,
                fieldKey = "rapid_capture_summary",
                label = "Rapid capture",
                value = buildString {
                    append("${latestCaptureSession.capturedCount} frames captured")
                    if (latestCaptureSession.duplicateCount > 0) {
                        append(" · ${latestCaptureSession.duplicateCount} duplicates suppressed")
                    }
                    if (latestCaptureSession.lowQualityCount > 0) {
                        append(" · ${latestCaptureSession.lowQualityCount} low-quality pages")
                    }
                    if (latestCaptureSession.failedCount > 0) {
                        append(" · ${latestCaptureSession.failedCount} failed")
                    }
                },
                confidence = 1f,
                source = "RAPID_CAPTURE"
            )
            if (latestCaptureSession.lowQualityCount > 0) {
                warnings +=
                    "${latestCaptureSession.lowQualityCount} rapid-capture page" +
                        if (latestCaptureSession.lowQualityCount == 1) {
                            " was flagged as low quality."
                        } else {
                            "s were flagged as low quality."
                        }
            }
            if (latestCaptureSession.failedCount > 0) {
                warnings +=
                    "${latestCaptureSession.failedCount} rapid-capture page" +
                        if (latestCaptureSession.failedCount == 1) {
                            " failed background processing."
                        } else {
                            "s failed background processing."
                        }
            }
        }

        val bookFields = mutableListOf<DocumentFieldEntity>()
        if (mode == ScanMode.BOOK) {
            val preservedSources = dao.getPreservedBookSources(documentId)
            val derivedCount = pages.count { it.sourceSpreadPageId != null }
            val uncertain = pages.filter(BookReviewPolicy::needsManualReview)

            if (preservedSources.isNotEmpty()) {
                bookFields += DocumentFieldEntity(
                    documentId = documentId,
                    fieldKey = "book_split_status",
                    label = "Book processing",
                    value = "${preservedSources.size} spread${if (preservedSources.size == 1) "" else "s"} split into $derivedCount logical pages. Originals are preserved.",
                    confidence = 1f,
                    source = "BOOK_ANALYSIS"
                )
            }
            if (uncertain.isNotEmpty()) {
                warnings += "${uncertain.size} possible book spread${if (uncertain.size == 1) "" else "s"} need manual split review."
                bookFields += DocumentFieldEntity(
                    documentId = documentId,
                    fieldKey = "book_review_count",
                    label = "Spread review",
                    value = "${uncertain.size} page${if (uncertain.size == 1) "" else "s"} kept unsplit because gutter confidence was below the auto-split threshold.",
                    confidence = uncertain.mapNotNull { it.bookSplitConfidence }
                        .average()
                        .toFloat()
                        .coerceIn(0f, 1f),
                    source = "BOOK_ANALYSIS"
                )
            }
        }

        val fields = buildList {
            extracted.forEach { field ->
                add(
                    DocumentFieldEntity(
                        documentId = documentId,
                        fieldKey = field.key,
                        label = field.label,
                        value = field.value,
                        confidence = field.confidence
                    )
                )
            }
            addAll(bookFields)
            addAll(rapidFields)
            warnings.distinct().takeIf { it.isNotEmpty() }?.let { distinctWarnings ->
                add(
                    DocumentFieldEntity(
                        documentId = documentId,
                        fieldKey = "capture_warning",
                        label = "Capture check",
                        value = distinctWarnings.joinToString(" "),
                        confidence = 1f,
                        source = "MODE_VALIDATION"
                    )
                )
            }
        }
        dao.replaceDocumentFields(documentId, fields)

        if (warnings.isNotEmpty() && !document.needsReview) {
            dao.setDocumentsNeedsReview(
                documentIds = listOf(documentId),
                needsReview = true,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun editableDocumentIds(documentIds: List<String>): List<String> {
        val ids = documentIds.distinct()
        require(ids.isNotEmpty()) { "Select at least one document" }
        ids.forEach { id ->
            val document = requireEditableDocument(id)
            require(!document.processing) { "${document.title} is still processing" }
        }
        return ids
    }

    private fun normalizeOrganizationName(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun nativePdfSource(
        document: DocumentEntity,
        pages: List<PageEntity>
    ): File? {
        val source = document.pdfPath?.let(::File)?.takeIf { it.isFile } ?: return null
        if (pages.isEmpty()) return null
        val importedPdf = pages.all { page ->
            page.id == deterministicPageId(document.id, page.position)
        }
        val hasCleanup = pages.any {
            !PageCleanupRecipeCodec.decode(it.cleanupRecipe).isEmpty()
        }
        return source.takeIf { importedPdf && !hasCleanup }
    }

    private fun orderedPages(pages: List<PageEntity>): List<PageEntity> =
        pages.sortedWith(compareBy<PageEntity> { it.sortKey }.thenBy { it.position })

    private fun imageSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }

    private fun defaultTitle(
        timestamp: Long,
        scanMode: ScanMode = ScanMode.DOCUMENT
    ): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        val local = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        val prefix = if (scanMode == ScanMode.DOCUMENT) "Scan" else scanMode.label
        return "$prefix · ${formatter.format(local)}"
    }

    private fun deterministicPageId(documentId: String, index: Int): String =
        UUID.nameUUIDFromBytes("$documentId:$index".toByteArray()).toString()
}
