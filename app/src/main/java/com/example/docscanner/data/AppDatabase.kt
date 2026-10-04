package com.example.docscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DocumentEntity::class, PageEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "docscanner.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Version 2: curved pages, book spreads and finger removal. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN mode TEXT NOT NULL DEFAULT 'FLAT'")
                db.execSQL("ALTER TABLE pages ADD COLUMN side TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE pages ADD COLUMN spine TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN remove_fingers INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
