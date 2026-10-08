package com.thiepn.scan.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * P24: reopen the existing v22 on-disk database and verify preservation of
 * soft deletion, source-spread provenance, and native PDF references.
 */
class V22DurabilityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reopeningV22PreservesPdfAndBookSourceAndSoftDeletion() {
        val databaseName = "scan-p24-${UUID.randomUUID()}.db"
        val id = UUID.randomUUID().toString()
        val files = FileStore(context)
        val pdfBytes = "%PDF-1.4\noriginal".toByteArray()
        try {
            val sourceFile = files.pageFile(id, "spread").apply {
                writeText("preserved original spread")
            }
            val leftFile = files.pageFile(id, "left").apply { writeText("left") }
            val rightFile = files.pageFile(id, "right").apply { writeText("right") }
            val pdf = files.pdfFile(id).apply { writeBytes(pdfBytes) }

            val first = ScanDatabase.create(context, databaseName)
            try {
                runBlocking {
                    first.documentDao().insertDocument(
                        DocumentEntity(
                            id = id,
                            title = "Restorable book",
                            createdAt = 100L,
                            updatedAt = 200L,
                            pdfPath = pdf.absolutePath,
                            pageCount = 2,
                            favorite = true,
                            archived = true,
                            trashedAt = 300L,
                            scanMode = ScanMode.BOOK.name
                        )
                    )
                    first.documentDao().insertPages(
                        listOf(
                            PageEntity(
                                id = "spread", documentId = id, position = 0,
                                imagePath = sourceFile.absolutePath,
                                width = 2000, height = 1400,
                                deleted = true, preservedBookSource = true
                            ),
                            PageEntity(
                                id = "left", documentId = id, position = 1,
                                imagePath = leftFile.absolutePath,
                                width = 1000, height = 1400,
                                sourceSpreadPageId = "spread", bookSide = "LEFT"
                            ),
                            PageEntity(
                                id = "right", documentId = id, position = 2,
                                imagePath = rightFile.absolutePath,
                                width = 1000, height = 1400,
                                sourceSpreadPageId = "spread", bookSide = "RIGHT"
                            )
                        )
                    )
                }
            } finally {
                first.close()
            }

            val reopened = ScanDatabase.create(context, databaseName)
            try {
                runBlocking {
                    val doc = reopened.documentDao().getDocument(id)
                    assertNotNull(doc)
                    assertEquals(pdf.absolutePath, doc?.pdfPath)
                    assertEquals(300L, doc?.trashedAt)
                    assertTrue(doc!!.favorite)
                    assertTrue(doc.archived)
                    assertEquals(ScanMode.BOOK.name, doc.scanMode)

                    val preserved = reopened.documentDao().getPage("spread")
                    assertNotNull(preserved)
                    assertTrue(preserved!!.deleted)
                    assertTrue(preserved.preservedBookSource)
                    assertTrue(java.io.File(preserved.imagePath).isFile)

                    val left = reopened.documentDao().getPage("left")
                    val right = reopened.documentDao().getPage("right")
                    assertEquals("spread", left?.sourceSpreadPageId)
                    assertEquals("spread", right?.sourceSpreadPageId)
                    assertEquals("LEFT", left?.bookSide)
                    assertEquals("RIGHT", right?.bookSide)
                    assertFalse(left!!.deleted)
                    assertFalse(right!!.deleted)
                    assertTrue(pdf.readBytes().contentEquals(pdfBytes))
                }
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
            files.deleteDocument(id)
        }
    }
}
