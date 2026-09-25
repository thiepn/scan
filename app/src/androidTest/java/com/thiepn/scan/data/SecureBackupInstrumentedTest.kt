package com.thiepn.scan.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class SecureBackupInstrumentedTest {
    @Test
    fun encryptedBackupRoundTripsAndRejectsWrongPasswordAndTampering() {
        val context =
            InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ScanDatabase::class.java
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val files = FileStore(context)
        val sourceId = "backup-cert-source"
        var restoredId: String? = null
        var backupFile: File? = null
        var tamperedFile: File? = null

        try {
            files.deleteDocument(sourceId)
            files.deleteExportsForDocument(sourceId)

            runBlocking {
                val pageId = "backup-cert-page"
                val pageFile = files.pageFile(
                    sourceId,
                    pageId
                ).apply {
                    writeBytes(
                        byteArrayOf(
                            0x53,
                            0x43,
                            0x41,
                            0x4E
                        )
                    )
                }

                database.documentDao().insertDocument(
                    DocumentEntity(
                        id = sourceId,
                        title = "Portable backup certification",
                        createdAt = 1L,
                        updatedAt = 2L,
                        pdfPath = null,
                        pageCount = 1,
                        ocrText = "portable recovery text"
                    )
                )
                database.documentDao().insertPage(
                    PageEntity(
                        id = pageId,
                        documentId = sourceId,
                        position = 0,
                        imagePath = pageFile.absolutePath,
                        width = 1200,
                        height = 1600,
                        ocrText = "portable recovery text"
                    )
                )

                val backup = SecureDocumentBackup(
                    dao = database.documentDao(),
                    files = files,
                    searchIndex = OcrSearchIndex(database)
                )

                val created = backup.create(
                    sourceId,
                    "correct-password".toCharArray()
                )
                backupFile = created.file

                assertTrue(created.file.isFile)
                assertTrue(created.file.length() > 0L)
                assertFalse(
                    created.file
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .contains("portable recovery text")
                )

                restoredId = backup.restore(
                    created.file,
                    "correct-password".toCharArray()
                )
                val restored = database.documentDao()
                    .getDocument(restoredId!!)
                val restoredPages = database.documentDao()
                    .getPages(restoredId!!)

                assertNotNull(restored)
                assertNotEquals(sourceId, restoredId)
                assertTrue(
                    restored?.title
                        ?.endsWith(" (Restored)") == true
                )
                assertEquals(
                    "portable recovery text",
                    restored?.ocrText
                )
                assertEquals(1, restoredPages.size)
                assertEquals(
                    "portable recovery text",
                    restoredPages.single().ocrText
                )
                assertTrue(
                    File(
                        restoredPages.single().imagePath
                    ).isFile
                )

                val wrongPassword = runCatching {
                    backup.restore(
                        created.file,
                        "definitely-wrong".toCharArray()
                    )
                }.exceptionOrNull()
                assertNotNull(wrongPassword)

                tamperedFile = File(
                    context.cacheDir,
                    "scan-backup-cert-tampered.scanbak"
                ).also {
                    created.file.copyTo(
                        it,
                        overwrite = true
                    )
                    RandomAccessFile(it, "rw").use { random ->
                        val position =
                            (random.length() - 1L)
                                .coerceAtLeast(0L)
                        random.seek(position)
                        val current = random.read()
                        random.seek(position)
                        random.write(current xor 0x01)
                    }
                }

                val tampered = runCatching {
                    backup.restore(
                        tamperedFile!!,
                        "correct-password".toCharArray()
                    )
                }.exceptionOrNull()
                assertNotNull(tampered)
            }
        } finally {
            restoredId?.let(files::deleteDocument)
            files.deleteDocument(sourceId)
            backupFile?.delete()
            tamperedFile?.delete()
            database.close()
        }
    }
}
