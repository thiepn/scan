package com.thiepn.scan.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LargeDocumentRoomInstrumentedTest {
    @Test
    fun persistsAndReadsThousandPageDocumentInStableOrder() {
        val context =
            InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ScanDatabase::class.java
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        runBlocking {
            val documentId = "large-document"
            database.documentDao().insertDocument(
                DocumentEntity(
                    id = documentId,
                    title = "1,000-page certification document",
                    createdAt = 1L,
                    updatedAt = 1L,
                    pdfPath = null,
                    pageCount = 1_000
                )
            )

            database.documentDao().insertPages(
                (0 until 1_000).map { index ->
                    PageEntity(
                        id = "page-${index.toString().padStart(4, '0')}",
                        documentId = documentId,
                        position = index,
                        imagePath = "/synthetic/page-$index.jpg",
                        width = 2480,
                        height = 3508,
                        ocrText = "Synthetic OCR page $index"
                    )
                }
            )

            val pages = database.documentDao().getPages(documentId)
            assertEquals(1_000, pages.size)
            assertEquals(0, pages.first().position)
            assertEquals(999, pages.last().position)
            assertEquals(1_000L, pages.first().sortKey)
            assertEquals(1_000_000L, pages.last().sortKey)
        }

        database.close()
    }
}
