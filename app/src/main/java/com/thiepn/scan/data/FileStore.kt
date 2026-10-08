package com.thiepn.scan.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        StorageSpaceGuard.require(
            anchor = destination,
            estimatedWorkingBytes = source.length(),
            operation = "copy this page"
        )
        val temporary = newStagingFile(destination)
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            commitTemporary(temporary, destination)
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun copyUri(
        uri: Uri,
        destination: File
    ): File {
        destination.parentFile?.mkdirs()
        val expectedBytes = estimateUriSize(uri)

        if (expectedBytes != null) {
            StorageSpaceGuard.require(
                anchor = destination,
                estimatedWorkingBytes = expectedBytes,
                operation = "import this file"
            )
        }

        val temporary = newStagingFile(destination)
        try {
            context.contentResolver
                .openInputStream(uri)
                .use { input ->
                    requireNotNull(input) {
                        "Unable to open input"
                    }
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            commitTemporary(temporary, destination)
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun estimateUriSize(uri: Uri): Long? =
        runCatching {
            context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { descriptor ->
                    descriptor.length.takeIf {
                        it >= 0L
                    }
                }
        }.getOrNull()

    fun pdfExportFile(documentId: String, title: String, protected: Boolean): File {
        val suffix = if (protected) "-protected" else ""
        return File(exports, "${safeName(title)}-${documentId.take(8)}$suffix.pdf")
    }

    fun extractedPdfExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-extract.pdf")

    fun selectedPdfExportFile(documentId: String, title: String): File =
        File(exports, "${safeName(title)}-${documentId.take(8)}-selected.pdf")

    fun mergedPdfExportFile(
        documentIds: List<String>
    ): File {
        val markers = documentIds
            .distinct()
            .joinToString("") {
                "-${it.take(8)}"
            }
        return File(
            exports,
            "Merged$markers-${System.currentTimeMillis()}.pdf"
        )
    }

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

    /**
     * Every writer gets its own sibling staging file. Sharing a fixed ".tmp"
     * filename can corrupt an in-flight export when two operations overlap.
     */
    fun temporaryExport(destination: File): File =
        newStagingFile(destination)

    private fun newStagingFile(destination: File): File {
        val directory = requireNotNull(destination.absoluteFile.parentFile) {
            "Storage destination has no parent directory"
        }
        require(directory.isDirectory || directory.mkdirs()) {
            "Storage directory is unavailable"
        }
        return File.createTempFile(
            destination.name.take(32).padEnd(3, '_') + "-",
            ".stage",
            directory
        )
    }

    fun copyToExport(source: File, destination: File): File {
        require(source.isFile) { "PDF source is unavailable" }
        StorageSpaceGuard.require(
            anchor = destination,
            estimatedWorkingBytes =
                StorageBudgetPolicy.pdfExportWorkingBytes(
                    source.length()
                ),
            operation = "export this PDF"
        )
        val temporary = temporaryExport(destination)
        try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return commitGeneratedExport(
                temporary,
                destination
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
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

    fun deletePlaintextExportsForDocument(
        documentId: String
    ) {
        val marker = "-${documentId.take(8)}"
        exports.listFiles()
            ?.filter {
                it.isFile &&
                    marker in it.name &&
                    !it.name.endsWith(".scanbak")
            }
            ?.forEach { it.delete() }
    }

    fun deleteExportsForDocument(documentId: String) {
        val marker = "-${documentId.take(8)}"
        exports.listFiles()
            ?.filter { it.isFile && marker in it.name }
            ?.forEach { it.delete() }
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "Scan" }

    /**
     * Same-filesystem atomic replace. Never delete the previous good file
     * before the replacement is committed. If ATOMIC_MOVE is unsupported,
     * fail closed rather than performing a destructive delete-and-rename.
     */
    private fun commitTemporary(temporary: File, destination: File) {
        require(temporary.isFile) { "Staged file is unavailable" }
        require(temporary.canonicalPath != destination.canonicalPath) {
            "Source and destination must differ"
        }
        try {
            // Renderers also write through temporaryExport(). Flush those
            // bytes before publishing the finished file name.
            FileOutputStream(temporary, true).use { it.fd.sync() }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
}
