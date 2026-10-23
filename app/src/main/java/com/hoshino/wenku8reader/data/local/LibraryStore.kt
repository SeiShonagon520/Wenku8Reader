package com.hoshino.wenku8reader.data.local

import com.hoshino.wenku8reader.data.BookInfo
import com.hoshino.wenku8reader.data.local.db.BookEntity
import com.hoshino.wenku8reader.data.local.db.LibraryDao
import com.hoshino.wenku8reader.data.local.db.toBookInfo
import com.hoshino.wenku8reader.data.local.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 书架条目：书目快照 + 入架信息。
 *
 * 与旧 `LocalLibraryStore.LibraryBook` 同形，调用方（书架页 / 详情页）无需改动。
 */
data class LibraryBook(
    val book: BookInfo,
    val shelf: String = "默认",
    val addedAt: Long = 0L,
    val isLocalFavorite: Boolean = true,
    val isAccountFavorite: Boolean = false,
)

data class AccountFavoriteSyncSummary(
    val totalAccountFavorites: Int,
    val newBooks: Int,
    val newlyMarkedAccountFavorites: Int,
)

/**
 * 本地书架的读写入口（Room 支撑）。
 *
 * 相比旧的 `LocalLibraryStore`：不再把整份 JSON 读出来全量解析——旧的 `contains()`
 * 在详情页与书架页高频调用，百本规模下每次都要重建整个 map；现在按主键查、由 SQLite 索引兜底。
 *
 * 读接口一律先过 [LocalDataMigration.ensure]，保证首帧就落在"已搬迁完成"的状态上。
 */
class LibraryStore internal constructor(
    private val dao: LibraryDao,
    private val migration: LocalDataMigration,
) {

    suspend fun all(): List<LibraryBook> {
        migration.ensure()
        return dao.books().map { it.toLibraryBook() }
    }

    /** 观察整份书架（按入架时间倒序，新入架在前）。 */
    fun observeAll(): Flow<List<LibraryBook>> = flow {
        migration.ensure()
        emitAll(dao.observeBooks().map { rows -> rows.map { it.toLibraryBook() } })
    }

    suspend fun contains(bookId: Int): Boolean {
        migration.ensure()
        return dao.containsBook(bookId)
    }

    /** 观察某本书是否属于本地收藏（详情页收藏星标的数据源）。 */
    fun observeContains(bookId: Int): Flow<Boolean> = flow {
        migration.ensure()
        emitAll(dao.observeBook(bookId).map { it?.isLocalFavorite == true })
    }

    /**
     * 加入书架。已存在时**保留原来的入架时间**（否则书架排序会把它当成新书跳到最前），
     * 书目快照则用新数据覆盖——与旧 `LocalLibraryStore.add` 的语义一致。
     */
    suspend fun add(book: BookInfo, shelf: String = "默认") {
        migration.ensure()
        dao.addLocalFavorite(
            book.toEntity(shelf = shelf, addedAt = System.currentTimeMillis()),
        )
    }

    /** Remove only the device-only favorite; a remote account favorite keeps the row. */
    suspend fun removeLocalFavorite(bookId: Int) {
        migration.ensure()
        dao.removeLocalFavorite(bookId)
    }

    /** Merge account favorites into the local library without changing local-favorite flags. */
    suspend fun syncAccountFavorites(books: List<BookInfo>): AccountFavoriteSyncSummary {
        migration.ensure()
        val existingById = dao.books().associateBy { it.id }
        val now = System.currentTimeMillis()
        val uniqueBooks = books.distinctBy { it.id }
        val accountRows = uniqueBooks.map { book ->
            val existing = existingById[book.id]
            val snapshot = if (existing == null) {
                book
            } else {
                book.copy(
                    title = book.title.ifBlank { existing.title },
                    author = book.author.ifBlank { existing.author },
                    status = book.status.ifBlank { existing.status },
                    lastUpdate = book.lastUpdate.ifBlank { existing.lastUpdate },
                    wordCount = book.wordCount.ifBlank { existing.wordCount },
                    description = book.description.ifBlank { existing.description },
                    coverUrl = book.coverUrl ?: existing.coverUrl,
                    groupId = book.groupId ?: existing.groupId,
                    tags = book.tags.ifEmpty { existing.tags },
                )
            }
            snapshot.toEntity(
                shelf = existing?.shelf ?: "默认",
                addedAt = existing?.addedAt ?: now,
            ).copy(
                isLocalFavorite = existing?.isLocalFavorite ?: false,
                isAccountFavorite = true,
            )
        }
        dao.mergeAccountFavorites(accountRows)
        return AccountFavoriteSyncSummary(
            totalAccountFavorites = uniqueBooks.size,
            newBooks = accountRows.count { existingById[it.id] == null },
            newlyMarkedAccountFavorites = accountRows.count {
                existingById[it.id]?.isAccountFavorite != true
            },
        )
    }
}

private fun BookEntity.toLibraryBook(): LibraryBook =
    LibraryBook(
        book = toBookInfo(),
        shelf = shelf,
        addedAt = addedAt,
        isLocalFavorite = isLocalFavorite,
        isAccountFavorite = isAccountFavorite,
    )
