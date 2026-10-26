package com.hoshino.wenku8reader.data.local

import android.content.Context
import android.net.Uri
import com.hoshino.wenku8reader.data.local.db.LibraryDao
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class LibraryBackupSummary(
    val bookCount: Int,
    val progressCount: Int,
)

/** User-controlled bookshelf and reading-progress backup; never includes credentials or cookies. */
class LibraryBackupManager internal constructor(
    context: Context,
    private val dao: LibraryDao,
    private val migration: LocalDataMigration,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun export(uri: Uri): LibraryBackupSummary {
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选文件")
        return output.use { exportTo(it) }
    }

    /** Writes the same versioned JSON used by the document-provider backup UI. */
    suspend fun exportTo(output: OutputStream): LibraryBackupSummary {
        migration.ensure()
        val books = dao.books()
        val progress = dao.allProgress()
        LibraryBackupCodec.write(books, progress, output)
        return LibraryBackupSummary(books.size, progress.size)
    }

    suspend fun import(uri: Uri): LibraryBackupSummary {
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        return input.use { importFrom(it) }
    }

    /** Validates the complete payload before merging any rows into Room. */
    suspend fun importFrom(input: InputStream): LibraryBackupSummary {
        migration.ensure()
        val backup = LibraryBackupCodec.read(input)
        dao.mergeBackup(backup.books, backup.readingProgress)
        return LibraryBackupSummary(backup.books.size, backup.readingProgress.size)
    }
}
