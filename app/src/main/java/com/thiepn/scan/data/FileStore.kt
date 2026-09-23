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

    fun copyPageFile(documentId: String, source: File, newPageId: String): File {
        require(source.isFile) { "Source page is unavailable" }
        val destination = pageFile(documentId, newPageId)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        commitTemporary(temporary, destination)
        return destination
    }

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

    fun pdfExportFile(documentId: String, title: String, protected: Boolean): File {
        val suffix = if (protected) "-protected" else ""
        return File(exports, "${safeName(title)}-${documentId.take(8)}$suffix.pdf")
    }

    fun extractedPdfExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-extract.pdf")

    fun selectedPdfExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-selected.pdf")

    fun mergedPdfExportFile(): File =
        File(exports, "Merged-${System.currentTimeMillis()}.pdf")

    fun temporaryWorkingPdf(prefix: String = "scan-work"): File {
        context.cacheDir.mkdirs()
        return File.createTempFile(prefix.take(24).padEnd(3, '_'), ".pdf", context.cacheDir)
    }

    fun temporaryDirectory(prefix: String): File {
        context.cacheDir.mkdirs()
        return File(
            context.cacheDir,
            prefix.take(32) + "-" + java.util.UUID.randomUUID()
        ).apply { mkdirs() }
    }

    fun temporaryExport(destination: File): File =
        File(destination.parentFile, destination.name + ".tmp").also { it.delete() }

    fun copyToExport(source: File, destination: File): File {
        require(source.isFile) { "PDF source is unavailable" }
        val temporary = temporaryExport(destination)
        source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        return commitGeneratedExport(temporary, destination)
    }

    fun commitGeneratedExport(temporary: File, destination: File): File {
        commitTemporary(temporary, destination)
        return destination
    }

    fun textExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}.txt")

    fun selectedTextExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-selected.txt")

    fun structuredCsvExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-data.csv")

    fun structuredJsonExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-data.json")

    fun structuredXlsxExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-data.xlsx")

    fun standardsPdfExportFile(
        documentId: String,
        title: String,
        standard: PdfStandard
    ): File {
        val suffix = when (standard) {
            PdfStandard.STANDARD -> "standards"
            PdfStandard.PDF_A_1B -> "pdfa-1b"
            PdfStandard.PDF_A_2B -> "pdfa-2b"
        }
        return File(
            exports,
            "${safeName(title)}-${documentId.take(8)}-$suffix.pdf"
        )
    }

    fun privacyPdfExportFile(documentId: String): File =
        File(
            exports,
            "Private-${documentId.take(8)}.pdf"
        )

    fun secureBackupFile(
        documentId: String,
        title: String
    ): File =
        File(
            exports,
            "${safeName(title)}-${documentId.take(8)}.scanbak"
        )

    fun signedPdfExportFile(
        documentId: String,
        title: String
    ): File =
        File(
            exports,
            "${safeName(title)}-${documentId.take(8)}-signed.pdf"
        )

    fun deleteDocument(documentId: String) {
        File(root, documentId).deleteRecursively()
    }

    fun deleteExportsForDocument(documentId: String) {
        val marker = "-${documentId.take(8)}"
        exports.listFiles()
            ?.filter { it.isFile && marker in it.name }
            ?.forEach { it.delete() }
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
