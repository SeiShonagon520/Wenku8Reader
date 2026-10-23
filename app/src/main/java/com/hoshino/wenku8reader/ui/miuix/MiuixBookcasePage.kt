package com.hoshino.wenku8reader.ui.miuix

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.ui.AppViewModelProvider
import com.hoshino.wenku8reader.ui.bookcase.BookcaseCompactGridView
import com.hoshino.wenku8reader.ui.bookcase.BookcaseEntry
import com.hoshino.wenku8reader.ui.bookcase.BookcaseFilter
import com.hoshino.wenku8reader.ui.bookcase.BookcaseGridView
import com.hoshino.wenku8reader.ui.bookcase.BookcaseShelfView
import com.hoshino.wenku8reader.ui.bookcase.BookcaseSortType
import com.hoshino.wenku8reader.ui.bookcase.BookcaseViewMode
import com.hoshino.wenku8reader.ui.bookcase.BookcaseViewModel
import com.hoshino.wenku8reader.ui.components.BookCover3D
import com.hoshino.wenku8reader.ui.components.bounceClick
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX 书架页（主 Tab）——与 Material 版（`ui/bookcase/BookcaseScreen.kt`）并行的 HyperOS 设计。
 *
 * - 结合 HyperOS 设计语言，支持海报网格、实体书架、细腻列表三种视角；
 * - 顶栏支持单向同步文库8云端书架；
 * - 书籍卡片采用拟真实体书脊光影 `BookCover3D` 与触感微动效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiuixBookcasePage(
    onOpenBook: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenStats: () -> Unit,
    vm: BookcaseViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isCustomAccount by vm.isCustomAccount.collectAsStateWithLifecycle()
    val currentUsername by vm.currentUsername.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()
    var showSyncDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    if (showSyncDialog) {
        MiuixBookcaseSyncDialog(
            isCustomAccount = isCustomAccount,
            currentUsername = currentUsername,
            isSyncing = isSyncing,
            onDismiss = { showSyncDialog = false },
            onLoginAndSync = { u, p, onFinished ->
                vm.login(u, p) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        vm.syncCloudBookshelf { syncSuccess, syncMsg ->
                            Toast.makeText(context, syncMsg, Toast.LENGTH_LONG).show()
                            if (syncSuccess) {
                                showSyncDialog = false
                            }
                            onFinished(syncSuccess, syncMsg)
                        }
                    } else {
                        onFinished(false, msg)
                    }
                }
            },
            onSyncDirectly = { onFinished ->
                vm.syncCloudBookshelf { syncSuccess, syncMsg ->
                    Toast.makeText(context, syncMsg, Toast.LENGTH_LONG).show()
                    if (syncSuccess) {
                        showSyncDialog = false
                    }
                    onFinished(syncSuccess, syncMsg)
                }
            },
        )
    }

    MiuixPage(
        title = stringResource(R.string.bookcase_title),
        actions = {
            MiuixIconButton(
                icon = Icons.Filled.CalendarMonth,
                contentDescription = stringResource(R.string.action_stats),
                onClick = onOpenStats,
            )
            MiuixIconButton(
                icon = Icons.Filled.CloudSync,
                contentDescription = stringResource(R.string.settings_account_sync_bookshelf),
                onClick = { showSyncDialog = true },
            )
            MiuixIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.action_refresh),
                onClick = { vm.load() },
            )
            MiuixIconButton(
                icon = Icons.Filled.Download,
                contentDescription = stringResource(R.string.action_downloads),
                onClick = onOpenDownloads,
            )
        },
    ) { inner ->
        when {
            ui.isLoading && ui.entries.isEmpty() -> MiuixLoading(
                Modifier
                    .fillMaxSize()
                    .padding(inner),
            )

            ui.error != null && ui.entries.isEmpty() -> MiuixEmptyState(
                title = ui.error?.asString(context) ?: "",
                error = true,
                icon = Icons.AutoMirrored.Filled.MenuBook,
                actionText = stringResource(R.string.action_retry),
                onAction = { vm.load() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )

            ui.entries.isEmpty() -> MiuixEmptyState(
                title = stringResource(R.string.bookcase_empty_local),
                icon = Icons.AutoMirrored.Filled.MenuBook,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) {
                OutlinedTextField(
                    value = ui.searchQuery,
                    onValueChange = vm::setSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { androidx.compose.material3.Text(stringResource(R.string.bookcase_search_hint)) },
                    singleLine = true,
                )
                // 顶部工具栏：排序与视角切换
                MiuixSection(
                    title = stringResource(R.string.bookcase_sort),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    MiuixDropdownRow(
                        title = stringResource(R.string.bookcase_sort),
                        icon = Icons.AutoMirrored.Filled.Sort,
                        items = BookcaseSortType.entries.map { stringResource(it.labelRes) },
                        selectedIndex = BookcaseSortType.entries.indexOf(ui.sortType).coerceAtLeast(0),
                        onSelected = { index -> vm.setSortType(BookcaseSortType.entries[index]) },
                    )
                    MiuixRowDivider()
                    MiuixDropdownRow(
                        title = stringResource(R.string.bookcase_filter_label),
                        items = BookcaseFilter.entries.map { stringResource(it.labelRes) },
                        selectedIndex = BookcaseFilter.entries.indexOf(ui.filter).coerceAtLeast(0),
                        onSelected = { index -> vm.setFilter(BookcaseFilter.entries[index]) },
                    )
                    MiuixRowDivider()
                    MiuixDropdownRow(
                        title = "视图模式",
                        icon = when (ui.viewMode) {
                            BookcaseViewMode.COMPACT -> Icons.Filled.Apps
                            BookcaseViewMode.GRID -> Icons.Filled.GridView
                            BookcaseViewMode.SHELF -> Icons.Filled.TableRows
                            BookcaseViewMode.LIST -> Icons.Filled.ViewAgenda
                        },
                        items = BookcaseViewMode.entries.map { stringResource(it.labelRes) },
                        selectedIndex = BookcaseViewMode.entries.indexOf(ui.viewMode).coerceAtLeast(0),
                        onSelected = { index -> vm.setViewMode(BookcaseViewMode.entries[index]) },
                    )
                    MiuixRowDivider()
                    MiuixSwitchRow(
                        title = stringResource(R.string.bookcase_sort_reverse),
                        icon = Icons.Filled.SwapVert,
                        checked = ui.sortReversed,
                        enabled = ui.sortType != BookcaseSortType.DEFAULT,
                        onCheckedChange = { vm.setSortReversed(it) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (ui.visibleEntries.isEmpty()) {
                    MiuixEmptyState(
                        title = stringResource(R.string.bookcase_no_matches),
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    AnimatedContent(
                        targetState = ui.viewMode,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(150))
                        },
                        modifier = Modifier.weight(1f),
                        label = "miuixBookcaseViewTransition",
                    ) { mode ->
                        when (mode) {
                            BookcaseViewMode.COMPACT -> BookcaseCompactGridView(
                                entries = ui.visibleEntries,
                                onOpenBook = onOpenBook,
                            )
                            BookcaseViewMode.GRID -> BookcaseGridView(
                                entries = ui.visibleEntries,
                                onOpenBook = onOpenBook,
                            )
                            BookcaseViewMode.SHELF -> BookcaseShelfView(
                                entries = ui.visibleEntries,
                                onOpenBook = onOpenBook,
                            )
                            BookcaseViewMode.LIST -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 4.dp,
                                    bottom = 24.dp + LocalFloatingBarInset.current,
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(ui.visibleEntries, key = { it.bookId }) { entry ->
                                    MiuixBookCard(entry = entry, onOpenBook = onOpenBook)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单本书卡片：拟真 3D 封面 + 书名/作者 + 已读进度 + 触感回弹。
 */
@Composable
private fun MiuixBookCard(
    entry: BookcaseEntry,
    onOpenBook: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(0.97f) { onOpenBook(entry.bookId) },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover3D(
                url = entry.coverUrl,
                width = 68.dp,
                height = 96.dp,
                contentDescription = entry.title,
                cornerRadius = 6.dp,
                elevation = 4.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.author.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.author,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.progressTotal > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = entry.progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.bookcase_read_chapters,
                            entry.readCount,
                            entry.progressTotal,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
    }
}

/**
 * MIUIX 风格书架同步弹窗。
 */
@Composable
private fun MiuixBookcaseSyncDialog(
    isCustomAccount: Boolean,
    currentUsername: String?,
    isSyncing: Boolean,
    onDismiss: () -> Unit,
    onLoginAndSync: (String, String, (Boolean, String) -> Unit) -> Unit,
    onSyncDirectly: ((Boolean, String) -> Unit) -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showLoginFields by remember { mutableStateOf(!isCustomAccount) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSyncing) onDismiss() },
        title = {
            androidx.compose.material3.Text(
                if (showLoginFields) stringResource(R.string.settings_account_login_title) else stringResource(R.string.settings_account_sync_bookshelf),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!showLoginFields && isCustomAccount) {
                    androidx.compose.material3.Text(
                        stringResource(R.string.settings_account_current, currentUsername ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (showLoginFields) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = {
                            user = it
                            errorMessage = null
                        },
                        label = { androidx.compose.material3.Text(stringResource(R.string.settings_account_username)) },
                        singleLine = true,
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = {
                            pass = it
                            errorMessage = null
                        },
                        label = { androidx.compose.material3.Text(stringResource(R.string.settings_account_password)) },
                        singleLine = true,
                        enabled = !isSyncing,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                androidx.compose.material3.Text(
                    stringResource(R.string.settings_account_sync_bookshelf_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (errorMessage != null) {
                    androidx.compose.material3.Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    errorMessage = null
                    if (showLoginFields) {
                        onLoginAndSync(user, pass) { success, msg ->
                            if (!success) {
                                errorMessage = msg
                            }
                        }
                    } else {
                        onSyncDirectly { success, msg ->
                            if (!success) {
                                errorMessage = msg
                            }
                        }
                    }
                },
                enabled = !isSyncing && (!showLoginFields || (user.isNotBlank() && pass.isNotBlank())),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Text(stringResource(R.string.settings_account_syncing))
                } else {
                    androidx.compose.material3.Text(if (showLoginFields) stringResource(R.string.settings_account_login_and_sync) else stringResource(R.string.settings_account_sync_now))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!showLoginFields && isCustomAccount) {
                    TextButton(onClick = { showLoginFields = true }) {
                        androidx.compose.material3.Text(stringResource(R.string.settings_account_switch))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !isSyncing) {
                    androidx.compose.material3.Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

