package com.hoshino.wenku8reader.data.local

import com.hoshino.wenku8reader.data.local.db.BookEntity
import com.hoshino.wenku8reader.data.local.db.ReadingProgressEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupCodecTest {

    @Test
    fun `stream round trip preserves bookshelf sources and reading position`() {
        val books = listOf(
            BookEntity(
                id = 42,
                title = "测试小说",
                author = "作者",
                status = "连载",
                lastUpdate = "今天",
                wordCount = "10万",
                description = "介绍",
                coverUrl = "https://img.example/cover.jpg",
                groupId = 3,
                tags = listOf("奇幻", "冒险"),
                shelf = "常读",
                addedAt = 1234L,
                isLocalFavorite = false,
                isAccountFavorite = true,
            ),
        )
        val progress = listOf(
            ReadingProgressEntity(
                bookId = 42,
                resumeCid = "10042",
                lastReadAt = 5678L,
                totalChapters = 120,
                finishedCids = listOf("10001", "10002"),
                resumeFraction = 0.45f,
            ),
        )
        val output = ByteArrayOutputStream()

        LibraryBackupCodec.write(books, progress, output)
        val json = output.toString(Charsets.UTF_8.name())
        val decoded = LibraryBackupCodec.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(books, decoded.books)
        assertEquals(progress, decoded.readingProgress)
        assertFalse(json.contains("password"))
        assertFalse(json.contains("cookie", ignoreCase = true))
    }

    @Test
    fun `older backups default favorites to local`() {
        val oldBackup = """
            {
              "format":"wenku8reader-backup",
              "version":1,
              "books":[{"id":1,"title":"旧书"}],
              "readingProgress":[]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val book = LibraryBackupCodec.read(ByteArrayInputStream(oldBackup)).books.single()

        assertTrue(book.isLocalFavorite)
        assertFalse(book.isAccountFavorite)
    }

    @Test
    fun `rejects invalid identity and duplicate book rows before import`() {
        val duplicateBooks = """
            {"format":"wenku8reader-backup","version":1,
             "books":[{"id":1,"title":"A"},{"id":1,"title":"B"}],
             "readingProgress":[]}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val error = runCatching { LibraryBackupCodec.read(ByteArrayInputStream(duplicateBooks)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects payload above existing 20 MB limit`() {
        val oversized = ByteArray(LibraryBackupCodec.MAX_BACKUP_BYTES + 1)

        val error = runCatching { LibraryBackupCodec.read(ByteArrayInputStream(oversized)) }.exceptionOrNull()

        assertTrue(error is IOException)
    }
}
