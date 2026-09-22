package com.thiepn.scan.data

import android.content.Context
import android.net.Uri
import java.io.File

class FileStore(private val context: Context) {
    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val exports = File(context.filesDir, "exports").apply { mkdirs() }

    fun documentDir(documentId: String): File = File(root, documentId).apply { mkdirs() }

    fun pageFile(documentId: String, pageId: String): File {
        val pages = File(documentDir(documentId), "pages").apply { mkdirs() }
        return File(pages, "$pageId.jpg")
    }

    fun pdfFile(documentId: String): File = File(documentDir(documentId), "document.pdf")

    suspend fun copyUri(uri: Uri, destination: File): File {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open input" }
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        commitTemporary(temporary, destination)
        return destination
    }

    fun createPdfExport(documentId: String, title: String, source: File): File {
        require(source.isFile) { "PDF source is unavailable" }
        val destination = File(exports, "${safeName(title)}-${documentId.take(8)}.pdf")
        val temporary = File(exports, destination.name + ".tmp")
        source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        commitTemporary(temporary, destination)
        return destination
    }

    fun textExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}.txt")

    fun deleteDocument(documentId: String) {
        File(root, documentId).deleteRecursively()
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "Scan" }

    private fun commitTemporary(temporary: File, destination: File) {
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("Unable to replace ${destination.name}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Unable to commit ${destination.name}")
        }
    }
}
