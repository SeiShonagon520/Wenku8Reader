package com.hoshino.wenku8reader.ui.bookcase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.data.local.AppPreferences
import com.hoshino.wenku8reader.data.local.AccountSession
import com.hoshino.wenku8reader.data.local.LibraryBook
import com.hoshino.wenku8reader.data.local.LibraryStore
import com.hoshino.wenku8reader.data.local.ReadingProgress
import com.hoshino.wenku8reader.data.local.ReadingProgressStore
import com.hoshino.wenku8reader.data.BookInfo
import com.hoshino.wenku8reader.data.Wenku8Client
import com.hoshino.wenku8reader.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Collator
import java.util.Locale

enum class BookcaseSortType(val key: String, val labelRes: Int) {
    DEFAULT("default", R.string.bookcase_sort_default),
    LATEST("latest", R.string.bookcase_sort_latest),
    LAST_READ("last_read", R.string.bookcase_sort_last_read),
    NAME("name", R.string.bookcase_sort_name),
    WORD_COUNT("word_count", R.string.bookcase_sort_word_count);

    companion object {
        fun map(key: String): BookcaseSortType =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

enum class BookcaseFilter(val labelRes: Int) {
    ALL(R.string.bookcase_filter_all),
    READING(R.string.bookcase_filter_reading),
    FINISHED(R.string.bookcase_filter_finished),
    NOT_STARTED(R.string.bookcase_filter_not_started),
}

enum class BookcaseSourceFilter(val labelRes: Int) {
    ALL(R.string.bookcase_source_all),
    LOCAL(R.string.bookcase_source_local),
    ACCOUNT(R.string.bookcase_source_account),
}

enum class BookcaseViewMode(val key: String, val labelRes: Int) {
    COMPACT("compact", R.string.bookcase_view_compact),
    GRID("grid", R.string.bookcase_view_grid),
    SHELF("shelf", R.string.bookcase_view_shelf),
    LIST("list", R.string.bookcase_view_list);

    companion object {
        fun map(key: String): BookcaseViewMode =
            entries.firstOrNull { it.key == key } ?: GRID
    }
}

/** Unified display entry for the on-device bookshelf. */
data class BookcaseEntry(
    val bookId: Int,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val status: String = "",
    val lastUpdate: String = "",
    val wordCount: Int = 0,
    val addedAt: Long = 0L,
    val progressTotal: Int = 0,
    /** 已读章节数：目录页标记"已读"的章节（finishedChapters）数量。 */
    val readCount: Int = 0,
    val resumeCid: String? = null,
    val lastReadAt: Long? = null,
    val isLocalFavorite: Boolean = true,
    val isAccountFavorite: Boolean = false,
) {
    /** 已读进度 = 已读章节数 / 总章节数（基于目录"已读"标记，非阅读位置）。 */
    val progress: Float
        get() = if (progressTotal > 0) {
            (readCount.toFloat() / progressTotal).coerceIn(0f, 1f)
        } else {
            0f
        }
}

data class BookcaseUiState(
    val sortType: BookcaseSortType = BookcaseSortType.DEFAULT,
    val sortReversed: Boolean = false,
    val viewMode: BookcaseViewMode = BookcaseViewMode.GRID,
    val isLoading: Boolean = false,
    val entries: List<BookcaseEntry> = emptyList(),
    val visibleEntries: List<BookcaseEntry> = emptyList(),
    val searchQuery: String = "",
    val filter: BookcaseFilter = BookcaseFilter.ALL,
    val sourceFilter: BookcaseSourceFilter = BookcaseSourceFilter.ALL,
    val error: UiText? = null,
)

class BookcaseViewModel(
    private val libraryStore: LibraryStore,
    private val progressStore: ReadingProgressStore,
    private val preferences: AppPreferences,
    private val client: Wenku8Client,
    private val accountSession: AccountSession,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        BookcaseUiState(
            sortType = BookcaseSortType.map(preferences.bookcaseSortType),
            sortReversed = preferences.bookcaseSortReversed,
            viewMode = BookcaseViewMode.map(preferences.bookcaseViewMode),
        )
    )
    val ui: StateFlow<BookcaseUiState> = _ui.asStateFlow()

    val currentUsername: StateFlow<String?> = accountSession.username
    val isCustomAccount: StateFlow<Boolean> = accountSession.isCustomAccount

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var natural: List<BookcaseEntry> = emptyList()

    /** 登录个人文库8账号 */
    fun login(user: String, pass: String, onResult: (Boolean, String) -> Unit) {
        val trimmedUser = user.trim()
        val trimmedPass = pass.trim()
        if (trimmedUser.isBlank() || trimmedPass.isBlank()) {
            onResult(false, "账号与密码不能为空")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                val res = client.login(trimmedUser, trimmedPass)
                val credentialsSaved = res.success &&
                    accountSession.saveCustomAccount(trimmedUser, trimmedPass)
                withContext(Dispatchers.Main) {
                    if (res.success) {
                        onResult(
                            true,
                            if (credentialsSaved) "登录成功，已绑定个人账号"
                            else "登录成功，但密码未能安全保存；会话失效后需要重新登录",
                        )
                    } else {
                        val msg = res.message.ifBlank { "登录失败，请检查账号密码或网络连接" }
                        onResult(false, msg)
                    }
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /** 退出个人文库8账号并切回访客模式 */
    fun logout(onResult: (String) -> Unit) {
        accountSession.clearCustomAccount()
        viewModelScope.launch(Dispatchers.IO) {
            client.clearUserSession()
            withContext(Dispatchers.Main) {
                onResult("已退出当前账号，已切换为访客模式")
            }
            try {
                client.ensureLoggedIn()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local logout is complete even when the shared account cannot reconnect.
            }
        }
    }

    /**
     * 单向同步云端书架：从文库8网页端拉取个人书架收藏列表，批量导入到本地 Room 数据库。
     * 本地书架独立存储，不向云端回写，避免触发站点风控与账号限制。
     */
    fun syncCloudBookshelf(onResult: (Boolean, String) -> Unit) {
        if (_isSyncing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                val loggedIn = client.ensureLoggedIn()
                if (!loggedIn) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "登录态失效，请重新登录个人账号")
                    }
                    return@launch
                }
                val cloudItems = client.bookcase()
                if (cloudItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult(true, "云端书架为空或暂未读取到书籍")
                    }
                    return@launch
                }
                val existing = libraryStore.all().associateBy { it.book.id }
                val accountBooks = cloudItems.distinctBy { it.aid }.mapNotNull { item ->
                    if (item.aid <= 0) return@mapNotNull null
                    val previous = existing[item.aid]
                    previous?.book?.copy(title = item.name.ifBlank { previous.book.title }) ?: runCatching {
                        withTimeoutOrNull(3000L) { client.bookInfo(item.aid) }
                    }.getOrNull() ?: BookInfo(id = item.aid, title = item.name)
                }
                val summary = libraryStore.syncAccountFavorites(accountBooks)
                load()
                withContext(Dispatchers.Main) {
                    onResult(
                        true,
                        if (summary.newlyMarkedAccountFavorites > 0) {
                            "账号收藏同步成功：云端共 ${summary.totalAccountFavorites} 本，新增书籍 ${summary.newBooks} 本；本地收藏已保留"
                        } else {
                            "账号收藏同步成功：云端共 ${summary.totalAccountFavorites} 本；本地收藏已保留"
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "同步失败：${e.localizedMessage ?: "网络异常"}")
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    init {
        // 数据源是数据库 Flow：详情页加/删收藏、阅读器写入进度之后，书架会自动刷新，
        // 不再依赖"重新进入页面时手动 load()"（漏调用就会一直显示过期数据）。
        viewModelScope.launch {
            combine(
                libraryStore.observeAll(),
                progressStore.observeAll(),
            ) { books, progress -> books.map { it.toEntry(progress[it.book.id]) } }
                .collect { entries ->
                    natural = entries
                    _ui.update { it.copy(isLoading = false, error = null) }
                    applySort()
                }
        }
    }

    /**
     * 下拉刷新 / 错误重试：数据本来由 Flow 持续驱动，这里重读一次只是让刷新手势有即时反馈
     * （并顺带等待一次性搬迁完成）。
     */
    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true) }
            val progress = progressStore.readAll()
            natural = libraryStore.all()
                .sortedByDescending { it.addedAt }
                .map { it.toEntry(progress[it.book.id]) }
            _ui.update { it.copy(isLoading = false, error = null) }
            applySort()
        }
    }

    private fun LibraryBook.toEntry(progress: ReadingProgress?): BookcaseEntry = BookcaseEntry(
        bookId = book.id,
        title = book.title,
        author = book.author,
        coverUrl = book.coverUrl,
        status = book.status,
        lastUpdate = book.lastUpdate,
        wordCount = parseWordCount(book.wordCount),
        addedAt = addedAt,
        progressTotal = progress?.totalChapters ?: 0,
        readCount = progress?.finishedCids?.size ?: 0,
        resumeCid = progress?.resumeCid,
        lastReadAt = progress?.lastReadAt,
        isLocalFavorite = isLocalFavorite,
        isAccountFavorite = isAccountFavorite,
    )

    fun setSortType(type: BookcaseSortType) {
        preferences.bookcaseSortType = type.key
        _ui.update { it.copy(sortType = type) }
        applySort()
    }

    fun setSortReversed(reversed: Boolean) {
        preferences.bookcaseSortReversed = reversed
        _ui.update { it.copy(sortReversed = reversed) }
        applySort()
    }

    fun setViewMode(mode: BookcaseViewMode) {
        preferences.bookcaseViewMode = mode.key
        _ui.update { it.copy(viewMode = mode) }
    }

    fun setSearchQuery(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        applySort()
    }

    fun setFilter(filter: BookcaseFilter) {
        _ui.update { it.copy(filter = filter) }
        applySort()
    }

    fun setSourceFilter(filter: BookcaseSourceFilter) {
        _ui.update { it.copy(sourceFilter = filter) }
        applySort()
    }

    private fun applySort() {
        val state = _ui.value
        val sorted = sortEntries(natural, state.sortType, state.sortReversed)
        val query = state.searchQuery.trim().lowercase(Locale.ROOT)
        val visible = sorted.filter { entry ->
            val isFinished = entry.progressTotal > 0 && entry.readCount >= entry.progressTotal
            val matchesSource = when (state.sourceFilter) {
                BookcaseSourceFilter.ALL -> true
                BookcaseSourceFilter.LOCAL -> entry.isLocalFavorite
                BookcaseSourceFilter.ACCOUNT -> entry.isAccountFavorite
            }
            val matchesFilter = when (state.filter) {
                BookcaseFilter.ALL -> true
                BookcaseFilter.READING -> entry.resumeCid != null && !isFinished
                BookcaseFilter.FINISHED -> isFinished
                BookcaseFilter.NOT_STARTED -> entry.resumeCid == null && entry.readCount == 0
            }
            val matchesQuery = query.isEmpty() ||
                entry.title.lowercase(Locale.ROOT).contains(query) ||
                entry.author.lowercase(Locale.ROOT).contains(query)
            matchesSource && matchesFilter && matchesQuery
        }
        _ui.update { it.copy(entries = sorted, visibleEntries = visible) }
    }

    private fun sortEntries(
        source: List<BookcaseEntry>,
        type: BookcaseSortType,
        reversed: Boolean,
    ): List<BookcaseEntry> {
        if (type == BookcaseSortType.DEFAULT) return source
        val collator = Collator.getInstance(Locale.CHINA)
        val sorted = when (type) {
            BookcaseSortType.LATEST ->
                source.sortedWith(
                    compareByDescending<BookcaseEntry> { it.lastUpdate }
                        .thenBy { it.bookId }
                )
            BookcaseSortType.LAST_READ ->
                source.sortedWith(
                    compareByDescending<BookcaseEntry> { it.lastReadAt ?: Long.MIN_VALUE }
                        .thenBy { it.bookId }
                )
            BookcaseSortType.NAME ->
                source.sortedWith { a, b -> collator.compare(a.title, b.title) }
            BookcaseSortType.WORD_COUNT ->
                source.sortedWith(
                    compareByDescending<BookcaseEntry> { it.wordCount }
                        .thenBy { it.bookId }
                )
            BookcaseSortType.DEFAULT -> source
        }
        return if (reversed) sorted.reversed() else sorted
    }

}
