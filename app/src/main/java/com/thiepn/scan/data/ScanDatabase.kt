package com.thiepn.scan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 2,
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

        fun create(context: Context): ScanDatabase = Room.databaseBuilder(
            context.applicationContext,
            ScanDatabase::class.java,
            "scan.db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
