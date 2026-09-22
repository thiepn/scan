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

    suspend fun appendScan(documentId: String, pageUris: List<Uri>): Int = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "No pages were captured" }
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }

        val nextPosition = dao.getMaxPagePosition(documentId) + 1
        val nextSortKey = dao.getMaxPageSortKey(documentId) + 1000L
        val copiedFiles = mutableListOf<File>()
        val pages = mutableListOf<PageEntity>()

        dao.setProcessing(documentId, true, System.currentTimeMillis())
        try {
            pageUris.forEachIndexed { index, uri ->
                val pageId = UUID.randomUUID().toString()
                val file = files.copyUri(uri, files.pageFile(documentId, pageId))
                copiedFiles += file
                val size = imageSize(file)
                pages += PageEntity(
                    id = pageId,
                    documentId = documentId,
                    position = nextPosition + index,
                    sortKey = nextSortKey + index * 1000L,
                    imagePath = file.absolutePath,
                    width = size.first,
                    height = size.second
                )
            }

            dao.insertPages(pages)
            appScope.launch(Dispatchers.IO) {
                recognizeDocument(documentId)
            }
            pages.size
        } catch (error: Throwable) {
            copiedFiles.forEach { it.delete() }
            dao.setProcessing(documentId, false, System.currentTimeMillis())
            throw error
        }
    }

    suspend fun rotatePage(documentId: String, pageId: String) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }

        val page = dao.getPages(documentId).firstOrNull { it.id == pageId }
            ?: throw IllegalArgumentException("Page not found")
        val nextRotation = PageRotation.clockwise(page.rotationDegrees)
        dao.setPageRotation(pageId, nextRotation)
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    suspend fun duplicatePage(documentId: String, pageId: String) = withContext(Dispatchers.IO) {
        val document = requireEditableDocument(documentId)
        require(!document.processing) { "Document is still processing" }

        val pages = orderedPages(dao.getPages(documentId))
        val sourceIndex = pages.indexOfFirst { it.id == pageId }
        require(sourceIndex >= 0) { "Page not found" }
        val source = pages[sourceIndex]

        val duplicateId = UUID.randomUUID().toString()
        val file = files.copyPageFile(
            documentId = documentId,
            source = File(source.imagePath),
            newPageId = duplicateId
        )

        try {
            val duplicate = source.copy(
                id = duplicateId,
                position = dao.getMaxPagePosition(documentId) + 1,
                sortKey = dao.getMaxPageSortKey(documentId) + 1000L,
                deleted = false,
                imagePath = file.absolutePath
            )
            val newOrder = pages.map { it.id }.toMutableList().apply {
                add(sourceIndex + 1, duplicateId)
            }
            dao.insertPageWithOrder(duplicate, newOrder)
            refreshDocumentSummary(documentId)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
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
                dao.insertPage(
                    PageEntity(
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
            val text = runCatching { ocr.recognize(File(page.imagePath)) }.getOrDefault("")
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

    suspend fun softDeletePage(documentId: String, pageId: String) = withContext(Dispatchers.IO) {
        val document = dao.getDocument(documentId) ?: return@withContext
        require(document.trashedAt == null) { "Restore the document before editing it" }
        require(!document.processing) { "Document is still processing" }
        val pages = dao.getPages(documentId)
        require(pages.size > 1) { "A document must keep at least one page" }
        require(pages.any { it.id == pageId }) { "Page not found" }

        dao.setPageDeleted(pageId, true)
        refreshDocumentSummary(documentId)
    }

    suspend fun restorePage(documentId: String, pageId: String) = withContext(Dispatchers.IO) {
        val document = dao.getDocument(documentId) ?: return@withContext
        require(document.trashedAt == null) { "Restore the document before editing it" }
        require(!document.processing) { "Document is still processing" }
        val deleted = dao.getDeletedPages(documentId)
        require(deleted.any { it.id == pageId }) { "Deleted page not found" }

        dao.setPageDeleted(pageId, false)
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

        if (source != null && quality == PdfQuality.ORIGINAL) {
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
            if (source != null) {
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
                    val unchanged = deleted.isEmpty() &&
                        nativeOrder == (0 until pages.size).toList() &&
                        rotations.all { it == 0 }

                    if (unchanged) {
                        mergeInputs += nativeSource
                    } else {
                        val working = files.temporaryWorkingPdf("scan-native-edit")
                        pdfEngine.extractPages(
                            source = nativeSource,
                            pageIndices = nativeOrder,
                            destination = working,
                            rotationDeltas = rotations
                        )
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

    private suspend fun refreshDocumentSummary(documentId: String) {
        val pages = orderedPages(dao.getPages(documentId))
        dao.finishProcessing(
            id = documentId,
            text = pages.map { it.ocrText }.filter { it.isNotBlank() }.joinToString("\n\n"),
            processing = false,
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
