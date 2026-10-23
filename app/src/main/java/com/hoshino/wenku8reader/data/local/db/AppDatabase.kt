package com.hoshino.wenku8reader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用本地数据库：书架 + 阅读进度。
 *
 * `exportSchema = false`：当前的迁移保持显式和小范围；如后续出现更复杂的表结构变更，
 * 应开启 schema 导出并为迁移补齐测试。
 */
@Database(
    entities = [BookEntity::class, ReadingProgressEntity::class, DownloadTaskEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {
        /** 数据库文件名。 */
        const val NAME = "wenku8.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reading_progress " +
                        "ADD COLUMN resumeFraction REAL NOT NULL DEFAULT 0.0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_tasks (" +
                        "bookId INTEGER NOT NULL, " +
                        "bookName TEXT NOT NULL, " +
                        "format TEXT NOT NULL, " +
                        "encoding TEXT NOT NULL, " +
                        "progress REAL NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "filePath TEXT, " +
                        "error TEXT, " +
                        "PRIMARY KEY(bookId))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
