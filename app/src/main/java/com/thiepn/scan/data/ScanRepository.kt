package com.thiepn.scan.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class ScanRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val files: FileStore,
    private val ocr: OcrEngine,
    private val rasterizer: PdfPageRasterizer,
    private val pdfEngine: PdfEngine,
    private val appScope: CoroutineScope
) {
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

    fun resumePendingProcessing() {
        appScope.launch(Dispatchers.IO) {
            dao.getProcessingDocuments().forEach { document ->
                val pages = dao.getPages(document.id)
                val pdf = document.pdfPath?.let(::File)?.takeIf { it.isFile }
                val looksLikePdfImport = pdf != null && (
                    pages.isEmpty() ||
                        pages.all { page -> page.id == deterministicPageId(document.id, page.position) }
                    )

                if (looksLikePdfImport) {
                    renderPdfAndRecognize(document.id, pdf)
                } else {
                    recognizeDocument(document.id)
                }
            }
        }
    }

    suspend fun ingestScan(pageUris: List<Uri>, pdfUri: Uri?): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pdf = pdfUri?.let { files.copyUri(it, files.pdfFile(id)) }
        val title = defaultTitle(now)

        dao.insertDocument(
            DocumentEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                pdfPath = pdf?.absolutePath,
                pageCount = pageUris.size,
                processing = true
            )
        )

        pageUris.forEachIndexed { index, uri ->
            val pageId = UUID.randomUUID().toString()
            val file = files.copyUri(uri, files.pageFile(id, pageId))
            val size = imageSize(file)
            dao.insertPage(
                PageEntity(
                    id = pageId,
                    documentId = id,
                    position = index,
                    sortKey = (index + 1L) * 1000L,
                    imagePath = file.absolutePath,
                    width = size.first,
                    height = size.second
                )
            )
        }

        appScope.launch(Dispatchers.IO) {
            if (pageUris.isEmpty() && pdf != null) renderPdfAndRecognize(id, pdf)
            else recognizeDocument(id)
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
                recognizePagesAndRefresh(documentId, insertedPages.map { it.id })
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
                visualRecipe = null,
                imagePath = replacementFile.absolutePath,
                width = size.first,
                height = size.second,
                ocrText = ""
            )
            val newOrder = currentPages.map { it.id }.toMutableList().apply {
                this[sourceIndex] = replacementId
            }
            dao.replacePageRecord(
                oldPageId = source.id,
                newPage = replacement,
                orderedPageIds = newOrder
            )
            File(source.imagePath).takeIf { it.absolutePath != replacementFile.absolutePath }?.delete()
            appScope.launch(Dispatchers.IO) {
                recognizePageAndRefresh(documentId, replacementId)
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
        val nextRotation = PageRotation.clockwise(page.rotationDegrees)
        dao.setPageRotation(pageId, nextRotation)
        dao.updatePageOcr(pageId, "")
        refreshDocumentSummary(documentId, processing = true)
        appScope.launch(Dispatchers.IO) {
            recognizePageAndRefresh(documentId, pageId)
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

            selected.forEach { page ->
                dao.setPageRotation(page.id, PageRotation.clockwise(page.rotationDegrees))
                dao.updatePageOcr(page.id, "")
            }
            refreshDocumentSummary(documentId, processing = true)
            appScope.launch(Dispatchers.IO) {
                recognizePagesAndRefresh(documentId, selected.map { it.id })
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

        val encoded = CropQuadCodec.encode(cropQuad)
        dao.setPageCropQuad(pageId, encoded)
        dao.updatePageOcr(pageId, "")
        refreshDocumentSummary(documentId, processing = true)

        appScope.launch(Dispatchers.IO) {
            recognizePageAndRefresh(documentId, pageId)
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
                        imagePath = file.absolutePath
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
                refreshDocumentSummary(documentId)
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
                if (
                    PageRotation.normalize(page.rotationDegrees) != 0 ||
                    !CropQuadCodec.decode(page.cropQuad).isFullFrame()
                ) {
                    requiresOcr += page.id
                    dao.updatePageOcr(page.id, "")
                }
                dao.setPageRotation(page.id, 0)
                dao.setPageCropQuad(page.id, null)
                dao.setPageVisualRecipe(page.id, null)
            }

            if (requiresOcr.isEmpty()) {
                refreshDocumentSummary(documentId)
            } else {
                refreshDocumentSummary(documentId, processing = true)
                appScope.launch(Dispatchers.IO) {
                    recognizePagesAndRefresh(documentId, requiresOcr)
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

    private suspend fun recognizeDocument(documentId: String) {
        val pages = dao.getPages(documentId)
        val recognized = mutableListOf<String>()
        pages.forEach { page ->
            val text = recognizePage(page)
            dao.updatePageOcr(page.id, text)
            if (text.isNotBlank()) recognized += text
        }
        dao.finishProcessing(
            id = documentId,
            text = recognized.joinToString("\n\n"),
            processing = false,
            pageCount = pages.size,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun recognizePageAndRefresh(documentId: String, pageId: String) {
        recognizePagesAndRefresh(documentId, listOf(pageId))
    }

    private suspend fun recognizePagesAndRefresh(
        documentId: String,
        pageIds: List<String>
    ) {
        try {
            pageIds.distinct().forEach { pageId ->
                val page = dao.getPage(pageId) ?: return@forEach
                if (page.deleted || page.documentId != documentId) return@forEach
                val text = recognizePage(page)
                dao.updatePageOcr(pageId, text)
            }
        } finally {
            refreshDocumentSummary(documentId)
        }
    }

    private suspend fun recognizePage(page: PageEntity): String {
        val quad = CropQuadCodec.decode(page.cropQuad)
        if (quad.isFullFrame() && PageRotation.normalize(page.rotationDegrees) == 0) {
            return runCatching { ocr.recognize(File(page.imagePath)) }.getOrDefault("")
        }

        val bitmap = runCatching {
            PageGeometryRenderer.renderFile(
                file = File(page.imagePath),
                cropQuad = quad,
                rotationDegrees = page.rotationDegrees,
                maxLongEdge = 2800
            )
        }.getOrNull() ?: return ""

        return try {
            runCatching { ocr.recognize(bitmap) }.getOrDefault("")
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun rename(id: String, title: String) {
        requireEditableDocument(id)
        dao.rename(id, title.trim().ifBlank { "Scan" }, System.currentTimeMillis())
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
            refreshDocumentSummary(documentId)
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

        val destination = files.pdfExportFile(
            documentId = id,
            title = document.title,
            protected = !password.isNullOrBlank()
        )

        val hasGeometryEdits = pages.any { !CropQuadCodec.decode(it.cropQuad).isFullFrame() }
        val hasVisualEdits = pages.any {
            !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal()
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
                    quality = quality
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

        runCatching {
            val hasGeometryEdits = selectedPages.any {
                !CropQuadCodec.decode(it.cropQuad).isFullFrame()
            }
            val hasVisualEdits = selectedPages.any {
                !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal()
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
                    destination = temporary
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
        val hasGeometryEdits = selected.any {
            !CropQuadCodec.decode(it.cropQuad).isFullFrame()
        }
        val hasVisualEdits = selected.any {
            !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal()
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
                    quality = quality
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

                if (nativeSource != null) {
                    val nativeOrder = pages.map { it.position }
                    val rotations = pages.map { it.rotationDegrees }
                    val hasGeometryEdits = pages.any {
                        !CropQuadCodec.decode(it.cropQuad).isFullFrame()
                    }
                    val hasVisualEdits = pages.any {
                        !PageVisualRecipeCodec.decode(it.visualRecipe).isOriginal()
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
                        pdfEngine.createSearchablePdf(pages, working)
                        mergeInputs += working
                        temporaryInputs += working
                    }
                } else {
                    require(pages.isNotEmpty()) { "${document.title} has no pages" }
                    val working = files.temporaryWorkingPdf("scan-merge")
                    pdfEngine.createSearchablePdf(pages, working)
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

    private suspend fun requireEditableDocument(id: String): DocumentEntity {
        val document = dao.getDocument(id) ?: throw IllegalArgumentException("Document not found")
        require(document.trashedAt == null) { "Restore the document before editing it" }
        return document
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
    }

    private fun nativePdfSource(
        document: DocumentEntity,
        pages: List<PageEntity>
    ): File? {
        val source = document.pdfPath?.let(::File)?.takeIf { it.isFile } ?: return null
        if (pages.isEmpty()) return null
        val importedPdf = pages.all { page ->
            page.id == deterministicPageId(document.id, page.position)
        }
        return source.takeIf { importedPdf }
    }

    private fun orderedPages(pages: List<PageEntity>): List<PageEntity> =
        pages.sortedWith(compareBy<PageEntity> { it.sortKey }.thenBy { it.position })

    private fun imageSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }

    private fun defaultTitle(timestamp: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        val local = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return "Scan · ${formatter.format(local)}"
    }

    private fun deterministicPageId(documentId: String, index: Int): String =
        UUID.nameUUIDFromBytes("$documentId:$index".toByteArray()).toString()
}
