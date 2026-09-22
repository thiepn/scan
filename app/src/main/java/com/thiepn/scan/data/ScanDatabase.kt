package com.thiepn.scan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 4,
    exportSchema = false
)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN sortKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE pages SET sortKey = (position + 1) * 1000")
                db.execSQL("ALTER TABLE pages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pages_documentId_deleted_sortKey " +
                        "ON pages(documentId, deleted, sortKey)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN trashedAt INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_trashedAt " +
                        "ON documents(trashedAt)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
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
                db.execSQL("DROP TABLE pages")
                db.execSQL("ALTER TABLE pages_new RENAME TO pages")
                db.execSQL(
                    "CREATE INDEX index_pages_documentId ON pages(documentId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_pages_documentId_position " +
                        "ON pages(documentId, position)"
                )
                db.execSQL(
                    "CREATE INDEX index_pages_documentId_deleted_sortKey " +
                        "ON pages(documentId, deleted, sortKey)"
                )
            }
        }

        fun create(context: Context): ScanDatabase = Room.databaseBuilder(
            context.applicationContext,
            ScanDatabase::class.java,
            "scan.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }
}
