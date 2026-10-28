package com.hoshino.wenku8reader.ui.reviews

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoshino.wenku8reader.data.BookReview
import com.hoshino.wenku8reader.data.ReviewPost
import com.hoshino.wenku8reader.data.repository.Wenku8Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewListUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val bookTitle: String = "",
    val reviews: List<BookReview> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val error: String? = null,
    val refreshError: String? = null,
    val loadMoreError: String? = null,
)

class ReviewListViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: Wenku8Repository,
) : ViewModel() {
    val bookId: Int = savedStateHandle["bookId"] ?: 0

    private val _ui = MutableStateFlow(ReviewListUiState())
    val ui: StateFlow<ReviewListUiState> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_ui.value.loading || _ui.value.refreshing) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            repository.bookReviews(bookId, 1)
                .onSuccess { page ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            bookTitle = page.bookTitle,
                            reviews = page.reviews,
                            page = page.page,
                            totalPages = page.totalPages,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(loading = false, error = error.message ?: "书评列表加载失败") }
                }
        }
    }

    fun refresh() {
        if (_ui.value.loading || _ui.value.refreshing || _ui.value.loadingMore) return
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    refreshing = true,
                    error = if (it.reviews.isEmpty()) null else it.error,
                    refreshError = null,
                    loadMoreError = null,
                )
            }
            repository.bookReviews(bookId, 1)
                .onSuccess { page ->
                    _ui.update {
                        it.copy(
                            refreshing = false,
                            bookTitle = page.bookTitle,
                            reviews = page.reviews,
                            page = page.page,
                            totalPages = page.totalPages,
                            refreshError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(refreshing = false, refreshError = error.message ?: "刷新失败") }
                }
        }
    }

    fun loadMore() {
        val current = _ui.value
        if (current.loading || current.refreshing || current.loadingMore || current.page >= current.totalPages) return
        val requestedPage = current.page + 1
        viewModelScope.launch {
            _ui.update { it.copy(loadingMore = true, loadMoreError = null) }
            repository.bookReviews(bookId, requestedPage)
                .onSuccess { page ->
                    _ui.update {
                        it.copy(
                            loadingMore = false,
                            bookTitle = page.bookTitle.ifBlank { it.bookTitle },
                            reviews = (it.reviews + page.reviews).distinctBy(BookReview::id),
                            page = page.page,
                            totalPages = page.totalPages,
                            loadMoreError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(loadingMore = false, loadMoreError = error.message ?: "加载更多失败") }
                }
        }
    }
}

data class ReviewThreadUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val bookTitle: String = "",
    val title: String = "",
    val posts: List<ReviewPost> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val error: String? = null,
    val refreshError: String? = null,
    val loadMoreError: String? = null,
)

class ReviewThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: Wenku8Repository,
) : ViewModel() {
    val reviewId: Int = savedStateHandle["reviewId"] ?: 0

    private val _ui = MutableStateFlow(ReviewThreadUiState())
    val ui: StateFlow<ReviewThreadUiState> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_ui.value.loading || _ui.value.refreshing) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            repository.reviewThread(reviewId, 1)
                .onSuccess { page ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            bookTitle = page.bookTitle,
                            title = page.title,
                            posts = page.posts,
                            page = page.page,
                            totalPages = page.totalPages,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(loading = false, error = error.message ?: "书评内容加载失败") }
                }
        }
    }

    fun refresh() {
        if (_ui.value.loading || _ui.value.refreshing || _ui.value.loadingMore) return
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    refreshing = true,
                    error = if (it.posts.isEmpty()) null else it.error,
                    refreshError = null,
                    loadMoreError = null,
                )
            }
            repository.reviewThread(reviewId, 1)
                .onSuccess { page ->
                    _ui.update {
                        it.copy(
                            refreshing = false,
                            bookTitle = page.bookTitle,
                            title = page.title,
                            posts = page.posts,
                            page = page.page,
                            totalPages = page.totalPages,
                            refreshError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(refreshing = false, refreshError = error.message ?: "刷新失败") }
                }
        }
    }

    fun loadMore() {
        val current = _ui.value
        if (current.loading || current.refreshing || current.loadingMore || current.page >= current.totalPages) return
        val requestedPage = current.page + 1
        viewModelScope.launch {
            _ui.update { it.copy(loadingMore = true, loadMoreError = null) }
            repository.reviewThread(reviewId, requestedPage)
                .onSuccess { page ->
                    _ui.update {
                        it.copy(
                            loadingMore = false,
                            bookTitle = page.bookTitle.ifBlank { it.bookTitle },
                            title = page.title.ifBlank { it.title },
                            posts = (it.posts + page.posts).distinctBy(ReviewPost::id),
                            page = page.page,
                            totalPages = page.totalPages,
                            loadMoreError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(loadingMore = false, loadMoreError = error.message ?: "加载更多回复失败") }
                }
        }
    }
}
