package com.hoshino.wenku8reader.ui.reviews

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewListScreen(
    onBack: () -> Unit,
    onOpenThread: (Int) -> Unit,
    vm: ReviewListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.reviews_list_title,
                            ui.bookTitle.ifBlank { stringResource(R.string.reviews_book_fallback) },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !ui.loading && !ui.refreshing && !ui.loadingMore) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reviews_refresh))
                    }
                },
            )
        },
    ) { inner ->
        when {
            (ui.loading || ui.refreshing) && ui.reviews.isEmpty() -> ReviewInitialLoading(Modifier.fillMaxSize().padding(inner))
            ui.error != null && ui.reviews.isEmpty() -> ReviewError(
                message = ui.error.orEmpty(),
                onRetry = vm::load,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            ui.reviews.isEmpty() -> ReviewEmpty(
                title = stringResource(R.string.reviews_empty),
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.refreshing) {
                    item(key = "refreshing") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.reviews_refreshing), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                ui.refreshError?.let { message ->
                    item(key = "refresh_error") {
                        ReviewInlineError(message = message, onRetry = vm::refresh)
                    }
                }
                items(ui.reviews, key = { it.id }) { review ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenThread(review.id) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(review.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                stringResource(R.string.reviews_stats, review.replyCount, review.viewCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (ui.page < ui.totalPages) {
                    item(key = "load_more") {
                        ReviewLoadMore(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewThreadScreen(
    onBack: () -> Unit,
    vm: ReviewThreadViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(ui.title.ifBlank { stringResource(R.string.reviews_thread_title) }, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !ui.loading && !ui.refreshing && !ui.loadingMore) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reviews_refresh))
                    }
                },
            )
        },
    ) { inner ->
        when {
            (ui.loading || ui.refreshing) && ui.posts.isEmpty() -> ReviewInitialLoading(Modifier.fillMaxSize().padding(inner))
            ui.error != null && ui.posts.isEmpty() -> ReviewError(
                message = ui.error.orEmpty(),
                onRetry = vm::load,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            ui.posts.isEmpty() -> ReviewEmpty(
                title = stringResource(R.string.reviews_thread_empty),
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.refreshing) {
                    item(key = "refreshing") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.reviews_refreshing), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                ui.refreshError?.let { message ->
                    item(key = "refresh_error") {
                        ReviewInlineError(message = message, onRetry = vm::refresh)
                    }
                }
                items(ui.posts, key = { it.id }) { post ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (post.floor == 1) stringResource(R.string.reviews_original_post)
                                    else stringResource(R.string.reviews_floor, post.floor),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (post.postedAt.isNotBlank()) {
                                    Text(post.postedAt, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(post.author, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            Text(post.body, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (ui.page < ui.totalPages) {
                    item(key = "load_more") {
                        ReviewLoadMore(
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
private fun ReviewInitialLoading(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.reviews_loading))
    }
}

@Composable
private fun ReviewEmpty(
    title: String = stringResource(R.string.reviews_empty),
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun ReviewInlineError(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun ReviewLoadMore(
    loading: Boolean,
    error: String?,
    label: String,
    retryLabel: String,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClick) { Text(retryLabel) }
            }
            else -> TextButton(onClick = onClick) { Text(label) }
        }
    }
}
