package com.hoshino.wenku8reader.data.local

import android.content.Context
import coil.ImageLoader
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.hoshino.wenku8reader.data.ChapterContent
import com.hoshino.wenku8reader.data.Volume
import com.hoshino.wenku8reader.data.Wenku8Client
import com.hoshino.wenku8reader.data.Wenku8Hosts
import com.hoshino.wenku8reader.data.repository.Wenku8Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用内一键批量离线缓存管理器。
 *
 * 负责在后台静默将指定分卷或整本书的小说正文与内嵌插图批量拉取至本地：
 * - 章节 HTML 落盘至 [HtmlDiskCache]；
 * - 插图通过 Coil 写入内存与磁盘缓存；
 * - 缓存后即使无网、开启飞行模式，阅读器也能瞬间毫秒开卷无白屏。
 */
class OfflineCacheManager(
    private val context: Context,
    private val repository: Wenku8Repository,
    private val client: Wenku8Client,
    private val imageLoader: ImageLoader = context.imageLoader,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    data class CacheProgress(
        val bookId: Int,
        val bookTitle: String,
        val cachedCount: Int,
        val totalCount: Int,
        val isRunning: Boolean,
        val message: String = "",
    ) {
        val fraction: Float
            get() = if (totalCount > 0) (cachedCount.toFloat() / totalCount).coerceIn(0f, 1f) else 0f

        val percent: Int
            get() = (fraction * 100).toInt()
    }

    private val _progress = MutableStateFlow<CacheProgress?>(null)
    val progress: StateFlow<CacheProgress?> = _progress.asStateFlow()

    private var currentJob: Job? = null

    /**
     * 判断某章节是否已被缓存到本地。
     */
    fun isChapterCached(gid: Int, bookId: Int, cid: String): Boolean {
        if (bookId < 0) return true // 本地书籍永远离线可用
        return client.isChapterCached(gid, bookId, cid)
    }

    /**
     * 获取某分卷内已缓存章节数和总章节数。
     */
    fun getVolumeCachedCount(gid: Int, bookId: Int, volume: Volume): Pair<Int, Int> {
        val total = volume.chapters.size
        if (total == 0) return 0 to 0
        if (bookId < 0) return total to total
        val cached = volume.chapters.count { isChapterCached(gid, bookId, it.cid) }
        return cached to total
    }

    fun isVolumeFullyCached(gid: Int, bookId: Int, volume: Volume): Boolean {
        val (cached, total) = getVolumeCachedCount(gid, bookId, volume)
        return total > 0 && cached >= total
    }

    /**
     * 离线缓存整本书籍的所有分卷。
     */
    fun cacheBook(
        bookId: Int,
        gid: Int,
        bookTitle: String,
        volumes: List<Volume>,
    ) {
        val allChapters = volumes.flatMap { it.chapters }
        startBatchDownload(bookId, gid, bookTitle, allChapters)
    }

    /**
     * 离线缓存指定的分卷。
     */
    fun cacheVolume(
        bookId: Int,
        gid: Int,
        bookTitle: String,
        volume: Volume,
    ) {
        startBatchDownload(bookId, gid, "$bookTitle - ${volume.name}", volume.chapters)
    }

    private fun startBatchDownload(
        bookId: Int,
        gid: Int,
        title: String,
        chapters: List<com.hoshino.wenku8reader.data.Chapter>,
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            val total = chapters.size
            if (total == 0) {
                _progress.value = null
                return@launch
            }

            _progress.value = CacheProgress(
                bookId = bookId,
                bookTitle = title,
                cachedCount = 0,
                totalCount = total,
                isRunning = true,
                message = "正在准备离线缓存...",
            )

            var cachedCount = 0
            for ((index, chapter) in chapters.withIndex()) {
                val isAlready = isChapterCached(gid, bookId, chapter.cid)
                if (isAlready) {
                    cachedCount++
                    _progress.value = CacheProgress(
                        bookId = bookId,
                        bookTitle = title,
                        cachedCount = cachedCount,
                        totalCount = total,
                        isRunning = true,
                        message = "正在缓存：${chapter.name}",
                    )
                    continue
                }

                try {
                    val result = repository.chapterContent(gid, bookId, chapter.cid)
                    val content = result.getOrNull()
                    if (content != null) {
                        preloadIllustrations(content.images)
                    }
                    cachedCount++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 单章网络波动不中断整批缓存，继续向下推进
                }

                _progress.value = CacheProgress(
                    bookId = bookId,
                    bookTitle = title,
                    cachedCount = cachedCount,
                    totalCount = total,
                    isRunning = true,
                    message = "正在缓存 (${index + 1}/$total)：${chapter.name}",
                )
            }

            _progress.value = CacheProgress(
                bookId = bookId,
                bookTitle = title,
                cachedCount = cachedCount,
                totalCount = total,
                isRunning = false,
                message = "离线缓存完成！共缓存 $cachedCount/$total 章",
            )
        }
    }

    private fun preloadIllustrations(images: List<String>) {
        for (imgUrl in images) {
            val req = ImageRequest.Builder(context)
                .data(imgUrl)
                .addHeader("Referer", Wenku8Hosts.IMAGE_REFERER)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(req)
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _progress.value = null
    }
}
