package com.example.docscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DocumentEntity::class, PageEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "docscanner.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

        /** Version 3: optional recognised text per page. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN ocr TEXT")
            }
        }

        /** Version 4: page rotation. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN rotation INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 5: Trash (soft delete) and folder covers. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN cover_page_id INTEGER")
                db.execSQL("ALTER TABLE documents ADD COLUMN deleted_at INTEGER")
                db.execSQL("ALTER TABLE pages ADD COLUMN deleted_at INTEGER")
            }
        }
    }
}
