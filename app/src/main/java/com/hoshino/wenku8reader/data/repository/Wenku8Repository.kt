package com.hoshino.wenku8reader.data.repository

import android.content.Context
import com.hoshino.wenku8reader.data.BookInfo
import com.hoshino.wenku8reader.data.BookReviewPage
import com.hoshino.wenku8reader.data.BookcaseItem
import com.hoshino.wenku8reader.data.ChapterContent
import com.hoshino.wenku8reader.data.HomeBook
import com.hoshino.wenku8reader.data.HomeSection
import com.hoshino.wenku8reader.data.SearchResult
import com.hoshino.wenku8reader.data.Volume
import com.hoshino.wenku8reader.data.ReviewThreadPage
import com.hoshino.wenku8reader.data.Wenku8Client
import com.hoshino.wenku8reader.data.local.LocalBookImporter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single source of truth for wenku8 and local library data.
 */
class Wenku8Repository(
    private val client: Wenku8Client,
    private val context: Context? = null,
) {

    suspend fun search(keyword: String, byAuthor: Boolean): Result<List<SearchResult>> =
        runCatchingNotCancelling { client.search(keyword, byAuthor) }

    suspend fun bookInfo(id: Int): Result<BookInfo> =
        runCatchingNotCancelling {
            if (id < 0 && context != null) {
                LocalBookImporter.getBookInfo(context, id)
                    ?: BookInfo(id = id, title = "本地书籍", status = "本地")
            } else {
                client.bookInfo(id)
            }
        }

    suspend fun bookReviews(bookId: Int, page: Int): Result<BookReviewPage> =
        runCatchingNotCancelling { client.bookReviews(bookId, page) }

    suspend fun reviewThread(reviewId: Int, page: Int): Result<ReviewThreadPage> =
        runCatchingNotCancelling { client.reviewThread(reviewId, page) }

    suspend fun chapters(bookId: Int, groupId: Int): Result<List<Volume>> =
        runCatchingNotCancelling {
            if (bookId < 0 && context != null) {
                LocalBookImporter.getLocalVolumes(context, bookId)
            } else {
                client.chapters(bookId, groupId)
            }
        }

    suspend fun chapterContent(gid: Int, bookId: Int, cid: String): Result<ChapterContent> =
        runCatchingNotCancelling {
            if (bookId < 0 && context != null) {
                LocalBookImporter.getLocalChapterContent(context, bookId, cid)
                    ?: ChapterContent(title = "", text = "")
            } else {
                client.chapterContent(gid, bookId, cid)
            }
        }

    suspend fun bookcase(): Result<List<BookcaseItem>> =
        runCatchingNotCancelling { client.bookcase() }

    suspend fun homepage(): Result<List<HomeSection>> =
        runCatchingNotCancelling { client.homepage() }

    suspend fun tags(): Result<List<String>> =
        runCatchingNotCancelling { client.tags() }

    suspend fun tagBooks(tag: String, page: Int = 1): Result<List<HomeBook>> =
        runCatchingNotCancelling { client.tagBooks(tag, page) }

    suspend fun downloadFullTxt(id: Int, type: String): Result<ByteArray> =
        runCatchingNotCancelling { client.downloadFullTxt(id, type) }

    /** The `gid` required by /novel/ URLs. Falls back to id/1000 when the detail page omits it. */
    fun groupIdOf(info: BookInfo): Int = if (info.id < 0) 0 else bookGroupId(info)
}

/**
 * 与 [Wenku8Client] 同口径的 `runCatching`：**不吞协程取消**。
 *
 * 标准 `runCatching` 捕获 `Throwable`，会把 `CancellationException` 也包成
 * `Result.failure`；调用方（如下载任务、阅读器加载）随后会把"取消"误判成
 * "网络失败"并继续执行后续逻辑，取消信号就此丢失。
 */
private inline fun <T> runCatchingNotCancelling(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
