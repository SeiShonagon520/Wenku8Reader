package com.hoshino.wenku8reader.data.local

import android.content.Context
import android.net.Uri
import com.hoshino.wenku8reader.data.Wenku8Hosts
import com.hoshino.wenku8reader.data.local.db.BookEntity
import com.hoshino.wenku8reader.data.local.db.LibraryDao
import com.hoshino.wenku8reader.data.local.db.ReadingProgressEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

data class LibraryBackupSummary(
    val bookCount: Int,
    val progressCount: Int,
)

/** 用户可控的书架与阅读进度备份；不包含账号、密码、Cookie 或缓存内容。 */
class LibraryBackupManager internal constructor(
    context: Context,
    private val dao: LibraryDao,
    private val migration: LocalDataMigration,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun export(uri: Uri): LibraryBackupSummary {
        migration.ensure()
        val books = dao.books()
        val progress = dao.allProgress()
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
            .put("readingProgress", JSONArray().apply { progress.forEach { put(it.toJson()) } })
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法写入所选文件")
        output.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        return LibraryBackupSummary(books.size, progress.size)
    }

    suspend fun import(uri: Uri): LibraryBackupSummary {
        migration.ensure()
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        val root = input.use { JSONObject(readLimited(it).toString(Charsets.UTF_8)) }
        require(root.optString("format") == FORMAT) { "文件不是轻小说文库备份" }
        require(root.optInt("version", -1) == VERSION) { "备份文件版本不受支持" }

        val books = root.getJSONArray("books").toBookRows()
        val progress = root.getJSONArray("readingProgress").toProgressRows()
        require(books.map { it.id }.distinct().size == books.size) { "备份中有重复书籍" }
        require(progress.map { it.bookId }.distinct().size == progress.size) { "备份中有重复进度记录" }

        dao.mergeBackup(books, progress)
        return LibraryBackupSummary(books.size, progress.size)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BACKUP_BYTES) { "备份文件过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun BookEntity.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("status", status)
        .put("lastUpdate", lastUpdate)
        .put("wordCount", wordCount)
        .put("description", description)
        .put("coverUrl", coverUrl ?: JSONObject.NULL)
        .put("groupId", groupId ?: JSONObject.NULL)
        .put("tags", JSONArray(tags))
        .put("shelf", shelf)
        .put("addedAt", addedAt)

    private fun ReadingProgressEntity.toJson() = JSONObject()
        .put("bookId", bookId)
        .put("resumeCid", resumeCid ?: JSONObject.NULL)
        .put("lastReadAt", lastReadAt ?: JSONObject.NULL)
        .put("totalChapters", totalChapters)
        .put("finishedCids", JSONArray(finishedCids))
        .put("resumeFraction", resumeFraction)

    private fun JSONArray.toBookRows(): List<BookEntity> {
        require(length() <= MAX_RECORDS) { "备份中的书籍数量过多" }
        return (0 until length()).map { index ->
            val item = getJSONObject(index)
            val id = item.getInt("id")
            val title = item.getString("title").trim()
            require(id > 0 && title.isNotEmpty()) { "备份中有无效书籍记录" }
            val coverUrl = if (item.isNull("coverUrl")) null else
                Wenku8Hosts.normalizeImageUrl(item.getString("coverUrl")).ifBlank { null }
            val groupId = if (item.isNull("groupId")) null else item.getInt("groupId")
            BookEntity(
                id = id,
                title = title,
                author = item.optString("author"),
                status = item.optString("status"),
                lastUpdate = item.optString("lastUpdate"),
                wordCount = item.optString("wordCount"),
                description = item.optString("description"),
                coverUrl = coverUrl,
                groupId = groupId,
                tags = item.optJSONArray("tags")?.toStringList().orEmpty(),
                shelf = item.optString("shelf", "默认").ifBlank { "默认" },
                addedAt = item.optLong("addedAt", 0L).coerceAtLeast(0L),
            )
        }
    }

    private fun JSONArray.toProgressRows(): List<ReadingProgressEntity> {
        require(length() <= MAX_RECORDS) { "备份中的进度记录过多" }
        return (0 until length()).map { index ->
            val item = getJSONObject(index)
            val bookId = item.getInt("bookId")
            val totalChapters = item.optInt("totalChapters", 0)
            val fraction = item.optDouble("resumeFraction", 0.0)
            require(bookId > 0 && totalChapters >= 0 && fraction.isFinite()) {
                "备份中有无效阅读进度"
            }
            val finished = item.optJSONArray("finishedCids")?.toStringList().orEmpty()
            ReadingProgressEntity(
                bookId = bookId,
                resumeCid = if (item.isNull("resumeCid")) null else item.getString("resumeCid"),
                lastReadAt = if (item.isNull("lastReadAt")) null else item.getLong("lastReadAt"),
                totalChapters = totalChapters,
                finishedCids = finished,
                resumeFraction = fraction.toFloat().coerceIn(0f, 1f),
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

    companion object {
        private const val FORMAT = "wenku8reader-backup"
        private const val VERSION = 1
        private const val MAX_RECORDS = 100_000
        private const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
    }
}
