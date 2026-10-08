package com.thiepn.scan.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P24: simulate process death while a durable capture session is active.
 * Recovery must never delete already copied page data or reset completed work.
 */
class InterruptedCaptureRecoveryInstrumentedTest {
    @Test
    fun coldStartRecoveryKeepsQueuedPagesAndRetriesOnlyRunningJobs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ScanDatabase::class.java
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val files = FileStore(context)
        val documentId = "p24-capture-recovery"
        try {
            files.deleteDocument(documentId)
            runBlocking {
                val page1 = files.pageFile(documentId, "p24-one")
                    .apply { writeText("preserved scanned image 1") }
                val page2 = files.pageFile(documentId, "p24-two")
                    .apply { writeText("preserved scanned image 2") }
                database.documentDao().insertDocument(
                    DocumentEntity(
                        id = documentId,
                        title = "Interrupted capture",
                        createdAt = 10,
                        updatedAt = 20,
                        pdfPath = null,
                        pageCount = 2,
                        processing = true
                    )
                )
                database.documentDao().insertPages(
                    listOf(
                        PageEntity(
                            id = "p24-one",
                            documentId = documentId,
                            position = 0,
                            imagePath = page1.absolutePath,
                            width = 1200,
                            height = 1600
                        ),
                        PageEntity(
                            id = "p24-two",
                            documentId = documentId,
                            position = 1,
                            imagePath = page2.absolutePath,
                            width = 1200,
                            height = 1600
                        )
                    )
                )
                database.documentDao().insertCaptureSession(
                    CaptureSessionEntity(
                        id = "p24-session",
                        documentId = documentId,
                        scanMode = ScanMode.DOCUMENT.name,
                        startedAt = 10,
                        updatedAt = 20,
                        status = CaptureSessionStatus.CAPTURING.name,
                        capturedCount = 2
                    )
                )
                database.documentDao().insertPageProcessingJobs(
                    listOf(
                        PageProcessingEntity(
                            pageId = "p24-one",
                            documentId = documentId,
                            sessionId = "p24-session",
                            queuedAt = 10,
                            updatedAt = 20,
                            status = PageProcessingStatus.PROCESSING.name
                        ),
                        PageProcessingEntity(
                            pageId = "p24-two",
                            documentId = documentId,
                            sessionId = "p24-session",
                            queuedAt = 10,
                            updatedAt = 20,
                            status = PageProcessingStatus.COMPLETE.name
                        )
                    )
                )

                // Exactly the two startup transitions in ScanRepository.
                database.documentDao().markCapturingSessionsInterrupted(30)
                database.documentDao().resetInterruptedProcessingJobs(30)

                val recovered = database.documentDao().getCaptureSession("p24-session")
                val jobs = database.documentDao()
                    .getSessionProcessingJobs("p24-session")
                    .associateBy { it.pageId }
                assertNotNull(recovered)
                assertEquals(CaptureSessionStatus.INTERRUPTED.name, recovered?.status)
                assertEquals(PageProcessingStatus.QUEUED.name, jobs["p24-one"]?.status)
                assertEquals(PageProcessingStatus.COMPLETE.name, jobs["p24-two"]?.status)
                assertEquals(2, database.documentDao().getPages(documentId).size)
                assertTrue(page1.isFile)
                assertTrue(page2.isFile)
            }
        } finally {
            database.close()
            files.deleteDocument(documentId)
        }
    }
}
