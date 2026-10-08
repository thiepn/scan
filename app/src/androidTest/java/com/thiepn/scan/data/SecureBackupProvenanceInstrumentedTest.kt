package com.thiepn.scan.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Portable backups must retain native PDF bytes and original book provenance,
 * while ignoring files that were only partially written when the backup began.
 */
class SecureBackupProvenanceInstrumentedTest {
    @Test
    fun encryptedRestoreKeepsNativePdfAndRemapsPreservedBookSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val files = FileStore(context)
        val originalId = UUID.randomUUID().toString()
        var restoredId: String? = null
        var archive: File? = null
        try {
            val pdfBytes = "%PDF-1.4\nuntouched-source-pdf\n".toByteArray()
            val originalPdf = files.pdfFile(originalId).apply { writeBytes(pdfBytes) }
            val stage = files.temporaryExport(originalPdf).apply {
                writeText("unfinished sensitive export bytes")
            }
            val imagePaths = listOf("spread", "left", "right").associateWith { pageId ->
                files.pageFile(originalId, pageId).apply {
                    writeText("original pixel bytes: $pageId")
                }.absolutePath
            }

            runBlocking {
                val dao = database.documentDao()
                dao.insertDocument(
                    DocumentEntity(
                        id = originalId,
                        title = "Portable book",
                        createdAt = 10,
                        updatedAt = 20,
                        pdfPath = originalPdf.absolutePath,
                        pageCount = 2,
                        scanMode = ScanMode.BOOK.name
                    )
                )
                dao.insertPages(
                    listOf(
                        PageEntity(
                            id = "spread", documentId = originalId, position = 0,
                            imagePath = imagePaths.getValue("spread"),
                            width = 2000, height = 1400,
                            deleted = true, preservedBookSource = true
                        ),
                        PageEntity(
                            id = "left", documentId = originalId, position = 1,
                            imagePath = imagePaths.getValue("left"),
                            width = 1000, height = 1400,
                            sourceSpreadPageId = "spread", bookSide = "LEFT"
                        ),
                        PageEntity(
                            id = "right", documentId = originalId, position = 2,
                            imagePath = imagePaths.getValue("right"),
                            width = 1000, height = 1400,
                            sourceSpreadPageId = "spread", bookSide = "RIGHT"
                        )
                    )
                )
                val backup = SecureDocumentBackup(
                    dao = dao,
                    files = files,
                    searchIndex = OcrSearchIndex(database)
                )
                val generated = backup.create(
                    originalId,
                    "P24-provenance-test".toCharArray()
                )
                archive = generated.file
                restoredId = backup.restore(
                    generated.file,
                    "P24-provenance-test".toCharArray()
                )

                val recoveredId = requireNotNull(restoredId)
                assertNotEquals(originalId, recoveredId)
                val document = dao.getDocument(recoveredId)
                assertNotNull(document)
                assertEquals(ScanMode.BOOK.name, document?.scanMode)
                assertArrayEquals(
                    pdfBytes,
                    File(requireNotNull(document?.pdfPath)).readBytes()
                )
                val recoveredPages = dao.getAllDocumentPages(recoveredId)
                assertEquals(3, recoveredPages.size)
                val source = recoveredPages.single { it.preservedBookSource }
                assertTrue(source.deleted)
                val derived = recoveredPages.filter { !it.preservedBookSource }
                assertEquals(setOf("LEFT", "RIGHT"), derived.map { it.bookSide }.toSet())
                assertTrue(derived.all { it.sourceSpreadPageId == source.id })
                assertTrue(recoveredPages.all { File(it.imagePath).isFile })
                assertFalse(
                    File(files.documentDir(recoveredId), stage.name).exists()
                )
                assertFalse(
                    files.documentDir(recoveredId).walkTopDown()
                        .any { it.isFile && it.name.endsWith(".stage") }
                )
            }
        } finally {
            restoredId?.let(files::deleteDocument)
            files.deleteDocument(originalId)
            files.deleteExportsForDocument(originalId)
            archive?.delete()
            database.close()
        }
    }
}
