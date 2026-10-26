package com.hoshino.wenku8reader.data.local

import com.hoshino.wenku8reader.data.Wenku8Hosts
import com.hoshino.wenku8reader.data.local.db.BookEntity
import com.hoshino.wenku8reader.data.local.db.ReadingProgressEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class LibraryBackupContents(
    val books: List<BookEntity>,
    val readingProgress: List<ReadingProgressEntity>,
)

/** JSON codec shared by document-provider backups and WebDAV snapshots. */
internal object LibraryBackupCodec {

    const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
    private const val FORMAT = "wenku8reader-backup"
    private const val VERSION = 1
    private const val MAX_RECORDS = 100_000

    fun write(
        books: List<BookEntity>,
        progress: List<ReadingProgressEntity>,
        output: OutputStream,
    ) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
            .put("readingProgress", JSONArray().apply { progress.forEach { put(it.toJson()) } })
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "备份文件过大" }
        output.write(bytes)
    }

    fun read(input: InputStream): LibraryBackupContents {
        val root = JSONObject(readLimited(input).toString(Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "文件不是轻小说文库备份" }
        require(root.optInt("version", -1) == VERSION) { "备份文件版本不受支持" }

        val books = root.getJSONArray("books").toBookRows()
        val progress = root.getJSONArray("readingProgress").toProgressRows()
        require(books.map { it.id }.distinct().size == books.size) { "备份中有重复书籍" }
        require(progress.map { it.bookId }.distinct().size == progress.size) { "备份中有重复进度记录" }
        return LibraryBackupContents(books, progress)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BACKUP_BYTES) throw IOException("备份文件过大")
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
        .put("isLocalFavorite", isLocalFavorite)
        .put("isAccountFavorite", isAccountFavorite)

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
                // Older backups predate source tracking; preserve their entries as local.
                isLocalFavorite = item.optBoolean("isLocalFavorite", true),
                isAccountFavorite = item.optBoolean("isAccountFavorite", false),
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
            ReadingProgressEntity(
                bookId = bookId,
                resumeCid = if (item.isNull("resumeCid")) null else item.getString("resumeCid"),
                lastReadAt = if (item.isNull("lastReadAt")) null else item.getLong("lastReadAt"),
                totalChapters = totalChapters,
                finishedCids = item.optJSONArray("finishedCids")?.toStringList().orEmpty(),
                resumeFraction = fraction.toFloat().coerceIn(0f, 1f),
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }
}
