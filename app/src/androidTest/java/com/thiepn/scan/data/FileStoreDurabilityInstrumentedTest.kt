package com.thiepn.scan.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * P24: instrumented crash/replacement checks on the real Android filesystem.
 * These test published file durability, not just an in-memory mock.
 */
class FileStoreDurabilityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun atomicallyReplacesPublishedPdfWithoutTouchingOriginalUntilCommit() {
        val files = FileStore(context)
        val id = UUID.randomUUID().toString()
        try {
            val published = files.pdfFile(id).apply {
                writeBytes("old published PDF".toByteArray())
            }
            val old = published.readBytes()
            val stage = files.temporaryExport(published)
            stage.writeBytes("new published PDF".toByteArray())
            assertArrayEquals(old, published.readBytes())

            val committed = files.commitGeneratedExport(stage, published)
            assertEquals(published.absolutePath, committed.absolutePath)
            assertEquals("new published PDF", published.readText())
            assertFalse(stage.exists())
        } finally {
            files.deleteDocument(id)
        }
    }

    @Test
    fun failedCommitPreservesPreviousPublishedCopy() {
        val files = FileStore(context)
        val id = UUID.randomUUID().toString()
        try {
            val published = files.pdfFile(id).apply {
                writeText("original")
            }
            val missingStage = files.temporaryExport(published)
            assertTrue(missingStage.delete())

            val failed = runCatching {
                files.commitGeneratedExport(missingStage, published)
            }.exceptionOrNull()

            assertTrue(failed != null)
            assertEquals("original", published.readText())
        } finally {
            files.deleteDocument(id)
        }
    }

    @Test
    fun concurrentStagingPathsNeverOverwriteEachOthersBytes() {
        val files = FileStore(context)
        val id = UUID.randomUUID().toString()
        try {
            val published = files.pdfFile(id)
            val first = files.temporaryExport(published)
            val second = files.temporaryExport(published)
            assertNotEquals(first.absolutePath, second.absolutePath)

            first.writeText("first")
            second.writeText("second")
            files.commitGeneratedExport(first, published)
            assertEquals("first", published.readText())
            assertEquals("second", second.readText())
            files.commitGeneratedExport(second, published)
            assertEquals("second", published.readText())
        } finally {
            files.deleteDocument(id)
        }
    }

    @Test
    fun prunesOnlyOldStagesAndNeverTouchesPublishedFilesOrRecentWork() {
        val files = FileStore(context)
        val id = UUID.randomUUID().toString()
        try {
            val published = files.pdfFile(id).apply { writeText("keep") }
            val stale = files.temporaryExport(published).apply { writeText("obsolete") }
            val recent = files.temporaryExport(published).apply { writeText("active") }
            val now = System.currentTimeMillis()
            assertTrue(stale.setLastModified(now - 48L * 60 * 60 * 1000))
            assertTrue(recent.setLastModified(now))

            assertTrue(
                files.pruneAbandonedStages(
                    olderThanMillis = 24L * 60 * 60 * 1000,
                    nowMillis = now
                ) >= 1
            )
            assertFalse(stale.exists())
            assertTrue(recent.isFile)
            assertEquals("keep", published.readText())
        } finally {
            files.deleteDocument(id)
        }
    }
}
