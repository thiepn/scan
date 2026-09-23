package com.thiepn.scan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        DocumentEntity::class,
        FolderEntity::class,
        TagEntity::class,
        DocumentTagCrossRef::class,
        DocumentFieldEntity::class,
        PageEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE pages ADD COLUMN sortKey INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("UPDATE pages SET sortKey = (position + 1) * 1000")
                connection.execSQL("ALTER TABLE pages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pages_documentId_deleted_sortKey " +
                        "ON pages(documentId, deleted, sortKey)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE documents ADD COLUMN trashedAt INTEGER")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_trashedAt " +
                        "ON documents(trashedAt)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE pages_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        sortKey INTEGER NOT NULL DEFAULT 0,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        rotationDegrees INTEGER NOT NULL DEFAULT 0,
                        imagePath TEXT NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        ocrText TEXT NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES documents(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    INSERT INTO pages_new (
                        id, documentId, position, sortKey, deleted,
                        rotationDegrees, imagePath, width, height, ocrText
                    )
                    SELECT
                        id, documentId, position, sortKey, deleted,
                        0, imagePath, width, height, ocrText
                    FROM pages
                    """.trimIndent()
                )
                connection.execSQL("DROP TABLE pages")
                connection.execSQL("ALTER TABLE pages_new RENAME TO pages")
                connection.execSQL(
                    "CREATE INDEX index_pages_documentId ON pages(documentId)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX index_pages_documentId_position " +
                        "ON pages(documentId, position)"
                )
                connection.execSQL(
                    "CREATE INDEX index_pages_documentId_deleted_sortKey " +
                        "ON pages(documentId, deleted, sortKey)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE pages ADD COLUMN cropQuad TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE pages ADD COLUMN visualRecipe TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE documents ADD COLUMN ocrScript TEXT NOT NULL DEFAULT 'LATIN'")
                connection.execSQL("ALTER TABLE pages ADD COLUMN ocrLayout TEXT")
                connection.execSQL("ALTER TABLE pages ADD COLUMN ocrFingerprint TEXT")
                connection.execSQL("ALTER TABLE pages ADD COLUMN ocrScript TEXT")
                connection.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS ocr_pages_fts
                    USING fts5(
                        documentId UNINDEXED,
                        pageId UNINDEXED,
                        content,
                        tokenize='unicode61 remove_diacritics 2'
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    INSERT INTO ocr_pages_fts(documentId, pageId, content)
                    SELECT documentId, id, ocrText
                    FROM pages
                    WHERE deleted = 0 AND TRIM(ocrText) <> ''
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE documents ADD COLUMN folderId TEXT")
                connection.execSQL(
                    "ALTER TABLE documents ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNSPECIFIED'"
                )
                connection.execSQL("ALTER TABLE documents ADD COLUMN suggestedType TEXT")
                connection.execSQL(
                    "ALTER TABLE documents ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_folderId ON documents(folderId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_documentType ON documents(documentType)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_needsReview ON documents(needsReview)"
                )

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        parentId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_folders_parentId ON folders(parentId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_folders_parentId_normalizedName " +
                        "ON folders(parentId, normalizedName)"
                )

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tags (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tags_normalizedName " +
                        "ON tags(normalizedName)"
                )

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_tags (
                        documentId TEXT NOT NULL,
                        tagId TEXT NOT NULL,
                        PRIMARY KEY(documentId, tagId),
                        FOREIGN KEY(documentId) REFERENCES documents(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(tagId) REFERENCES tags(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_tags_documentId " +
                        "ON document_tags(documentId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_tags_tagId " +
                        "ON document_tags(tagId)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE documents ADD COLUMN scanMode TEXT NOT NULL DEFAULT 'DOCUMENT'"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_scanMode ON documents(scanMode)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_fields (
                        documentId TEXT NOT NULL,
                        fieldKey TEXT NOT NULL,
                        label TEXT NOT NULL,
                        value TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        source TEXT NOT NULL,
                        PRIMARY KEY(documentId, fieldKey),
                        FOREIGN KEY(documentId) REFERENCES documents(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_fields_documentId " +
                        "ON document_fields(documentId)"
                )
            }
        }

        fun create(context: Context): ScanDatabase = Room.databaseBuilder(
            context.applicationContext,
            ScanDatabase::class.java,
            "scan.db"
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9
            )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}
