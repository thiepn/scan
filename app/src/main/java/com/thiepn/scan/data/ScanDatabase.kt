package com.thiepn.scan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        fun create(context: Context): ScanDatabase = Room.databaseBuilder(
            context.applicationContext,
            ScanDatabase::class.java,
            "scan.db"
        ).build()
    }
}
