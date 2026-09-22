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
    private val appScope: CoroutineScope
) {
    fun observeDocuments(filter: LibraryFilter, query: String): Flow<List<DocumentEntity>> {
        val normalized = query.trim()
        if (normalized.isNotBlank()) {
            return when (filter) {
                LibraryFilter.ACTIVE -> dao.searchActive(normalized)
                LibraryFilter.FAVORITES -> dao.searchFavorites(normalized)
                LibraryFilter.ARCHIVED -> dao.searchArchived(normalized)
            }
        }
        return when (filter) {
            LibraryFilter.ACTIVE -> dao.observeActive()
            LibraryFilter.FAVORITES -> dao.observeFavorites()
            LibraryFilter.ARCHIVED -> dao.observeArchived()
        }
    }

    fun observeDocument(id: String): Flow<DocumentEntity?> = dao.observeDocument(id)
    fun observePages(id: String): Flow<List<PageEntity>> = dao.observePages(id)

    fun resumePendingProcessing() {
        appScope.launch(Dispatchers.IO) {
            dao.getProcessingDocuments().forEach { document ->
                val pages = dao.getPages(document.id)
                val pdf = document.pdfPath?.let(::File)?.takeIf { it.isFile }
                val looksLikePdfImport = pdf != null && (
                    pages.isEmpty() ||
                        pages.any { page -> page.id == deterministicPageId(document.id, page.position) }
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
            dao.finishProcessing(documentId, pages.joinToString("\n\n") { it.ocrText }, false, pages.size, System.currentTimeMillis())
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
        dao.rename(id, title.trim().ifBlank { "Scan" }, System.currentTimeMillis())
    }

    suspend fun setFavorite(id: String, value: Boolean) {
        dao.setFavorite(id, value, System.currentTimeMillis())
    }

    suspend fun setArchived(id: String, value: Boolean) {
        dao.setArchived(id, value, System.currentTimeMillis())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.deleteDocument(id)
        files.deleteDocument(id)
    }

    suspend fun document(id: String): DocumentEntity? = dao.getDocument(id)

    suspend fun createPdfExport(id: String): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        val source = document.pdfPath?.let(::File)?.takeIf { it.isFile } ?: return@withContext null
        files.createPdfExport(id, document.title, source)
    }

    suspend fun createTextExport(id: String): File? = withContext(Dispatchers.IO) {
        val document = dao.getDocument(id) ?: return@withContext null
        val pages = dao.getPages(id)
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
