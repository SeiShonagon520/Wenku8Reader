package com.hoshino.wenku8reader.ui.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.ui.AppViewModelProvider
import com.hoshino.wenku8reader.ui.reviews.ReviewListViewModel
import com.hoshino.wenku8reader.ui.reviews.ReviewThreadViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixReviewListPage(
    onBack: () -> Unit,
    onOpenThread: (Int) -> Unit,
    vm: ReviewListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    MiuixSubPage(
        title = stringResource(
            R.string.reviews_list_title,
            ui.bookTitle.ifBlank { stringResource(R.string.reviews_book_fallback) },
        ),
        onBack = onBack,
        actions = {
            MiuixIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.reviews_refresh),
                onClick = vm::refresh,
            )
        },
    ) { inner ->
        when {
            (ui.loading || ui.refreshing) && ui.reviews.isEmpty() -> MiuixLoading(Modifier.fillMaxSize().padding(inner))
            ui.error != null && ui.reviews.isEmpty() -> MiuixEmptyState(
                title = ui.error.orEmpty(),
                error = true,
                icon = Icons.Filled.Forum,
                actionText = stringResource(R.string.action_retry),
                onAction = vm::load,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            ui.reviews.isEmpty() -> MiuixEmptyState(
                title = stringResource(R.string.reviews_empty),
                icon = Icons.Filled.Forum,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.refreshing) item(key = "refreshing") { MiuixReviewLoadingRow() }
                ui.refreshError?.let { error ->
                    item(key = "refresh_error") {
                        MiuixReviewActionRow(error, stringResource(R.string.action_retry), vm::refresh, isError = true)
                    }
                }
                items(ui.reviews, key = { it.id }) { review ->
                    MiuixSection(modifier = Modifier.fillMaxWidth().clickable { onOpenThread(review.id) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(review.title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.reviews_stats, review.replyCount, review.viewCount),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        }
                    }
                }
                if (ui.page < ui.totalPages) {
                    item(key = "load_more") {
                        MiuixReviewLoadMore(
                            loading = ui.loadingMore,
                            error = ui.loadMoreError,
                            label = stringResource(R.string.reviews_load_more_topics),
                            retryLabel = stringResource(R.string.reviews_retry_load_more),
                            onClick = vm::loadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiuixReviewThreadPage(
    onBack: () -> Unit,
    vm: ReviewThreadViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    MiuixSubPage(
        title = ui.title.ifBlank { stringResource(R.string.reviews_thread_title) },
        onBack = onBack,
        actions = {
            MiuixIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.reviews_refresh),
                onClick = vm::refresh,
            )
        },
    ) { inner ->
        when {
            (ui.loading || ui.refreshing) && ui.posts.isEmpty() -> MiuixLoading(Modifier.fillMaxSize().padding(inner))
            ui.error != null && ui.posts.isEmpty() -> MiuixEmptyState(
                title = ui.error.orEmpty(),
                error = true,
                icon = Icons.Filled.Forum,
                actionText = stringResource(R.string.action_retry),
                onAction = vm::load,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            ui.posts.isEmpty() -> MiuixEmptyState(
                title = stringResource(R.string.reviews_thread_empty),
                icon = Icons.Filled.Forum,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.refreshing) item(key = "refreshing") { MiuixReviewLoadingRow() }
                ui.refreshError?.let { error ->
                    item(key = "refresh_error") {
                        MiuixReviewActionRow(error, stringResource(R.string.action_retry), vm::refresh, isError = true)
                    }
                }
                items(ui.posts, key = { it.id }) { post ->
                    MiuixSection {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (post.floor == 1) stringResource(R.string.reviews_original_post)
                                    else stringResource(R.string.reviews_floor, post.floor),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                if (post.postedAt.isNotBlank()) {
                                    Text(post.postedAt, style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(post.author, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            Text(post.body, style = MiuixTheme.textStyles.body2)
                        }
                    }
                }
                if (ui.page < ui.totalPages) {
                    item(key = "load_more") {
                        MiuixReviewLoadMore(
                            loading = ui.loadingMore,
                            error = ui.loadMoreError,
                            label = stringResource(R.string.reviews_load_more_replies),
                            retryLabel = stringResource(R.string.reviews_retry_load_more),
                            onClick = vm::loadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixReviewLoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(progress = null, modifier = Modifier.width(20.dp).height(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.reviews_refreshing), style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun MiuixReviewActionRow(
    message: String,
    actionText: String,
    onAction: () -> Unit,
    isError: Boolean,
) {
    MiuixSection {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MiuixTheme.textStyles.footnote1,
                color = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            top.yukonga.miuix.kmp.basic.TextButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
private fun MiuixReviewLoadMore(
    loading: Boolean,
    error: String?,
    label: String,
    retryLabel: String,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(progress = null, modifier = Modifier.width(24.dp).height(24.dp))
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                top.yukonga.miuix.kmp.basic.TextButton(text = retryLabel, onClick = onClick)
            }
            else -> Button(onClick = onClick) { Text(label) }
        }
    }
}
