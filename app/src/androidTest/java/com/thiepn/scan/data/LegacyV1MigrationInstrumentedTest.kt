package com.thiepn.scan.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class LegacyV1MigrationInstrumentedTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "scan-v1-migration-certification.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesRealV1ShapeToV22WithoutLosingDocumentOrPage() {
        createV1Database()

        val database = ScanDatabase.create(
            context = context,
            databaseName = databaseName
        )

        runBlocking {
            val document = database.documentDao().getDocument("legacy-document")
            val pages = database.documentDao().getPages("legacy-document")

            assertNotNull(document)
            assertEquals("Legacy document", document?.title)
            assertEquals(1, pages.size)
            assertEquals("legacy-page", pages.single().id)
            assertEquals("legacy OCR", pages.single().ocrText)
            assertEquals(1_000L, pages.single().sortKey)
            assertFalse(pages.single().deleted)
            assertFalse(pages.single().preservedBookSource)
        }

        database.close()
    }

    private fun createV1Database() {
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    pdfPath TEXT,
                    pageCount INTEGER NOT NULL,
                    favorite INTEGER NOT NULL,
                    archived INTEGER NOT NULL,
                    processing INTEGER NOT NULL,
                    ocrText TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pages (
                    id TEXT NOT NULL PRIMARY KEY,
                    documentId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    imagePath TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    ocrText TEXT NOT NULL,
                    FOREIGN KEY(documentId) REFERENCES documents(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX index_pages_documentId ON pages(documentId)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX index_pages_documentId_position " +
                    "ON pages(documentId, position)"
            )
            database.execSQL(
                """
                INSERT INTO documents (
                    id, title, createdAt, updatedAt, pdfPath, pageCount,
                    favorite, archived, processing, ocrText
                ) VALUES (
                    'legacy-document', 'Legacy document', 100, 200, NULL, 1,
                    0, 0, 0, 'legacy OCR'
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO pages (
                    id, documentId, position, imagePath, width, height, ocrText
                ) VALUES (
                    'legacy-page', 'legacy-document', 0,
                    '/legacy/page.jpg', 1200, 1600, 'legacy OCR'
                )
                """.trimIndent()
            )
            database.version = 1
        }

        check(File(file.absolutePath).isFile)
    }
}
