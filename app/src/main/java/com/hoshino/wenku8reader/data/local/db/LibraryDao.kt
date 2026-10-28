package com.hoshino.wenku8reader.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 书架与阅读进度的唯一数据访问入口。
 *
 * 读接口同时提供 suspend（一次性）与 [Flow]（观察）两种形态：
 * 页面首帧用一次性读取即可，而「加入/移出书架后详情页按钮要立刻变」「阅读器记录进度后
 * 书架进度条要跟着变」这类跨页面联动只有靠 Flow 才能自动刷新——旧实现靠
 * `refreshLocalState()` 这种手动回调，漏调用就会显示过期状态。
 */
@Dao
interface LibraryDao {

    // ---------------------------------------------------------------- //
    // 书架
    // ---------------------------------------------------------------- //

    /** 按入架时间倒序（新入架在前），与旧书架页的 `sortedByDescending { addedAt }` 一致。 */
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    suspend fun books(): List<BookEntity>

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun book(bookId: Int): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: Int): Flow<BookEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :bookId)")
    suspend fun containsBook(bookId: Int): Boolean

    @Upsert
    suspend fun upsertBook(book: BookEntity)

    /** 批量写入（迁移导入用）；空列表直接返回，避免无意义的空事务。 */
    @Upsert
    suspend fun upsertBooks(books: List<BookEntity>)

    /** Add a local favorite while preserving an existing account-favorite marker and shelf time. */
    @Transaction
    suspend fun addLocalFavorite(incoming: BookEntity) {
        val existing = book(incoming.id)
        upsertBook(
            incoming.copy(
                shelf = existing?.shelf ?: incoming.shelf,
                addedAt = existing?.addedAt ?: incoming.addedAt,
                isLocalFavorite = true,
                isAccountFavorite = existing?.isAccountFavorite ?: false,
            ),
        )
    }

    /** Remove only local membership; preserve the row when it is still an account favorite. */
    @Transaction
    suspend fun removeLocalFavorite(bookId: Int) {
        val existing = book(bookId) ?: return
        if (existing.isAccountFavorite) {
            upsertBook(existing.copy(isLocalFavorite = false))
        } else {
            deleteBook(bookId)
        }
    }

    /** Merge fetched account memberships atomically so a concurrent local toggle cannot be lost. */
    @Transaction
    suspend fun mergeAccountFavorites(incoming: List<BookEntity>) {
        val merged = incoming.map { accountBook ->
            val existing = book(accountBook.id)
            accountBook.copy(
                shelf = existing?.shelf ?: accountBook.shelf,
                addedAt = existing?.addedAt ?: accountBook.addedAt,
                isLocalFavorite = existing?.isLocalFavorite ?: false,
                isAccountFavorite = true,
            )
        }
        upsertBooks(merged)
    }

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: Int)

    @Query("SELECT MIN(id) FROM books")
    suspend fun minBookId(): Int?

    @Query("UPDATE books SET shelf = :shelf WHERE id = :bookId")
    suspend fun updateShelf(bookId: Int, shelf: String)

    @Query("UPDATE books SET shelf = :shelf WHERE id IN (:bookIds)")
    suspend fun updateShelfForBooks(bookIds: List<Int>, shelf: String)

    @Query("UPDATE books SET shelf = :newShelf WHERE shelf = :oldShelf")
    suspend fun renameShelf(oldShelf: String, newShelf: String)

    @Query("SELECT DISTINCT shelf FROM books")
    suspend fun distinctShelves(): List<String>

    @Transaction
    suspend fun removeLocalFavorites(bookIds: List<Int>) {
        for (id in bookIds) {
            removeLocalFavorite(id)
        }
    }

    @Query("SELECT COUNT(*) FROM books")
    suspend fun bookCount(): Int

    // ---------------------------------------------------------------- //
    // 阅读进度
    // ---------------------------------------------------------------- //

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun progress(bookId: Int): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observeProgress(bookId: Int): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress")
    suspend fun allProgress(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress")
    fun observeAllProgress(): Flow<List<ReadingProgressEntity>>

    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    /** 原子更新章节内位置，避免与已读标记等进度字段的读改写互相覆盖。 */
    @Query(
        "UPDATE reading_progress SET resumeFraction = :fraction " +
            "WHERE bookId = :bookId AND resumeCid = :cid"
    )
    suspend fun updateResumeFraction(bookId: Int, cid: String, fraction: Float)

    /** 批量写入（迁移导入用）；空列表直接返回。 */
    @Upsert
    suspend fun upsertProgressAll(items: List<ReadingProgressEntity>)

    /** 导入备份时合并书架与进度；同一本书保留本机入架时间和较新的阅读位置。 */
    @Transaction
    suspend fun mergeBackup(
        books: List<BookEntity>,
        progressRows: List<ReadingProgressEntity>,
    ) {
        val mergedBooks = books.map { incoming ->
            val existing = book(incoming.id)
            if (existing == null) incoming else incoming.copy(
                addedAt = existing.addedAt,
                isLocalFavorite = incoming.isLocalFavorite || existing.isLocalFavorite,
                isAccountFavorite = incoming.isAccountFavorite || existing.isAccountFavorite,
            )
        }
        val mergedProgress = progressRows.map { incoming ->
            mergeProgress(progress(incoming.bookId), incoming)
        }
        upsertBooks(mergedBooks)
        upsertProgressAll(mergedProgress)
    }

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteProgress(bookId: Int)

    /**
     * 按主键批量删除（清理过期阅读数据用）。
     *
     * 哪些算"过期"由 `staleProgressBookIds` 这个**有单测的纯函数**决定，而不是写成 SQL 条件：
     * 这段逻辑会删用户数据，规则必须能在 JVM 单测里把边界钉死。空列表直接返回，
     * 避免生成 `IN ()` 这种 SQLite 不接受的语法。
     */
    @Query("DELETE FROM reading_progress WHERE bookId IN (:bookIds)")
    suspend fun deleteProgressByIds(bookIds: List<Int>)

    // ---------------------------------------------------------------- //
    // 下载任务
    // ---------------------------------------------------------------- //

    @Query("SELECT * FROM download_tasks")
    suspend fun downloadTasks(): List<DownloadTaskEntity>

    @Upsert
    suspend fun upsertDownloadTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE bookId = :bookId")
    suspend fun deleteDownloadTask(bookId: Int)
}

private fun mergeProgress(
    existing: ReadingProgressEntity?,
    incoming: ReadingProgressEntity,
): ReadingProgressEntity {
    if (existing == null) return incoming
    val preferIncomingPosition = when {
        existing.resumeCid == null && incoming.resumeCid != null -> true
        incoming.resumeCid == null -> false
        incoming.lastReadAt == null -> false
        existing.lastReadAt == null -> true
        else -> incoming.lastReadAt > existing.lastReadAt
    }
    val position = if (preferIncomingPosition) incoming else existing
    return position.copy(
        lastReadAt = listOfNotNull(existing.lastReadAt, incoming.lastReadAt).maxOrNull(),
        totalChapters = maxOf(existing.totalChapters, incoming.totalChapters),
        finishedCids = (existing.finishedCids + incoming.finishedCids).distinct(),
    )
}
