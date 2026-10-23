package com.hoshino.wenku8reader.ui.bookcase

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.ui.AppViewModelProvider
import com.hoshino.wenku8reader.ui.components.BookCover3D
import com.hoshino.wenku8reader.ui.components.ExpressiveEmptyState
import com.hoshino.wenku8reader.ui.components.ExpressiveLoadingIndicator
import com.hoshino.wenku8reader.ui.components.ExpressiveScaffold
import com.hoshino.wenku8reader.ui.components.ExpressiveTopAppBar
import com.hoshino.wenku8reader.ui.components.TonalCard
import com.hoshino.wenku8reader.ui.components.bounceClick

/**
 * 书架页（主 Tab）：
 * - 支持三种实体感多重视角（画廊海报网格、详细进度列表、拟真实体书架）；
 * - 结合 M3 Expressive 与触感回弹动效；
 * - 支持直接登录个人账号并单向同步文库8云端书架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookcasePage(
    onOpenBook: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenStats: () -> Unit,
    vm: BookcaseViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isCustomAccount by vm.isCustomAccount.collectAsStateWithLifecycle()
    val currentUsername by vm.currentUsername.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()
    var showAccountCenterDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    if (showAccountCenterDialog) {
        BookcaseAccountCenterDialog(
            isCustomAccount = isCustomAccount,
            currentUsername = currentUsername,
            isSyncing = isSyncing,
            totalBooks = ui.entries.size,
            readingBooks = ui.entries.count {
                it.resumeCid != null && !(it.progressTotal > 0 && it.readCount >= it.progressTotal)
            },
            finishedBooks = ui.entries.count { it.progressTotal > 0 && it.readCount >= it.progressTotal },
            onDismiss = { showAccountCenterDialog = false },
            onLoginAndSync = { u, p, onFinished ->
                vm.login(u, p) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        vm.syncCloudBookshelf { syncSuccess, syncMsg ->
                            Toast.makeText(context, syncMsg, Toast.LENGTH_LONG).show()
                            if (syncSuccess) {
                                showAccountCenterDialog = false
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
                        showAccountCenterDialog = false
                    }
                    onFinished(syncSuccess, syncMsg)
                }
            },
            onLogout = { onFinished ->
                vm.logout { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onFinished()
                }
            },
        )
    }

    ExpressiveScaffold(
        topBar = {
            ExpressiveTopAppBar(
                title = stringResource(R.string.bookcase_title),
                actions = {
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.action_stats))
                    }
                    IconButton(onClick = { showAccountCenterDialog = true }) {
                        if (isCustomAccount && !currentUsername.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = currentUsername.orEmpty().take(1).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        } else {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = stringResource(R.string.bookcase_account_title),
                            )
                        }
                    }
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.action_downloads))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { inner ->
        when {
            ui.isLoading && ui.entries.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator()
                }

            ui.error != null && ui.entries.isEmpty() -> ExpressiveEmptyState(
                title = ui.error?.asString(LocalContext.current) ?: "",
                icon = Icons.Filled.Warning,
                shape = MaterialShapes.Boom,
                error = true,
                actionLabel = stringResource(R.string.action_retry),
                onAction = { vm.load() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )

            ui.entries.isEmpty() -> ExpressiveEmptyState(
                title = stringResource(R.string.bookcase_empty_local),
                icon = Icons.AutoMirrored.Filled.MenuBook,
                shape = MaterialShapes.Cookie9Sided,
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
                    label = { Text(stringResource(R.string.bookcase_search_hint)) },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BookcaseFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = ui.filter == filter,
                            onClick = { vm.setFilter(filter) },
                            label = { Text(stringResource(filter.labelRes)) },
                        )
                    }
                }
                // 顶部工具栏：排序 + 视角切换
                BookcaseToolbar(
                    ui = ui,
                    onSelectSort = { vm.setSortType(it) },
                    onToggleReverse = { vm.setSortReversed(!ui.sortReversed) },
                    onSelectViewMode = { vm.setViewMode(it) },
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 6.dp,
                    ),
                )

                if (ui.visibleEntries.isEmpty()) {
                    ExpressiveEmptyState(
                        title = stringResource(R.string.bookcase_no_matches),
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        shape = MaterialShapes.Cookie9Sided,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    // 核心内容区：视角平滑淡入切换
                    AnimatedContent(
                        targetState = ui.viewMode,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(150))
                        },
                        modifier = Modifier.weight(1f),
                        label = "bookcaseViewTransition",
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
                            ) {
                                items(ui.visibleEntries, key = { it.bookId }) { entry ->
                                    BookcaseCard(
                                        entry = entry,
                                        onOpenBook = onOpenBook,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                                item { Spacer(Modifier.height(24.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookcaseToolbar(
    ui: BookcaseUiState,
    onSelectSort: (BookcaseSortType) -> Unit,
    onToggleReverse: () -> Unit,
    onSelectViewMode: (BookcaseViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：排序 SplitButton
        BookcaseSortBar(
            ui = ui,
            onSelect = onSelectSort,
            onToggleReverse = onToggleReverse,
        )

        // 右侧：四种视角模式切换按钮组（紧凑画廊、海报画廊、实体书架、详细列表）
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewModeButton(
                icon = Icons.Filled.Apps,
                description = stringResource(R.string.bookcase_view_compact),
                selected = ui.viewMode == BookcaseViewMode.COMPACT,
                onClick = { onSelectViewMode(BookcaseViewMode.COMPACT) },
            )
            ViewModeButton(
                icon = Icons.Filled.GridView,
                description = stringResource(R.string.bookcase_view_grid),
                selected = ui.viewMode == BookcaseViewMode.GRID,
                onClick = { onSelectViewMode(BookcaseViewMode.GRID) },
            )
            ViewModeButton(
                icon = Icons.Filled.TableRows,
                description = stringResource(R.string.bookcase_view_shelf),
                selected = ui.viewMode == BookcaseViewMode.SHELF,
                onClick = { onSelectViewMode(BookcaseViewMode.SHELF) },
            )
            ViewModeButton(
                icon = Icons.Filled.ViewAgenda,
                description = stringResource(R.string.bookcase_view_list),
                selected = ui.viewMode == BookcaseViewMode.LIST,
                onClick = { onSelectViewMode(BookcaseViewMode.LIST) },
            )
        }
    }
}

@Composable
private fun ViewModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = if (selected) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun BookcaseSortBar(
    ui: BookcaseUiState,
    onSelect: (BookcaseSortType) -> Unit,
    onToggleReverse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 根据排序类型与当前正反向动态展示指示图标
    val sortIcon = when {
        ui.sortType == BookcaseSortType.DEFAULT -> Icons.Filled.SwapVert
        !ui.sortReversed -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.ArrowUpward
    }

    val handleToggleReverseWithToast = {
        val willBeReversed = !ui.sortReversed
        onToggleReverse()
        val tip = when (ui.sortType) {
            BookcaseSortType.LATEST ->
                if (willBeReversed) "已切换为：最早更新排在前（旧到新）"
                else "已切换为：最新更新排在前（新到旧）"
            BookcaseSortType.LAST_READ ->
                if (willBeReversed) "已切换为：较早阅读的书排在前"
                else "已切换为：最近阅读的书排在前"
            BookcaseSortType.NAME ->
                if (willBeReversed) "已切换为：书名降序（Z → A）"
                else "已切换为：书名升序（A → Z）"
            BookcaseSortType.WORD_COUNT ->
                if (willBeReversed) "已切换为：字数由少到多"
                else "已切换为：字数由多到少"
            BookcaseSortType.DEFAULT ->
                if (willBeReversed) "已开启倒序排列"
                else "已恢复默认顺序"
        }
        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
    }

    Box(modifier) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.TonalLeadingButton(onClick = { expanded = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(ui.sortType.labelRes), maxLines = 1)
                }
            },
            trailingButton = {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = ui.sortReversed && ui.sortType != BookcaseSortType.DEFAULT,
                    enabled = ui.sortType != BookcaseSortType.DEFAULT,
                    onCheckedChange = { handleToggleReverseWithToast() },
                ) {
                    Icon(
                        sortIcon,
                        contentDescription = stringResource(R.string.bookcase_sort_reverse),
                        modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize),
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                stringResource(R.string.bookcase_sort),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 6.dp),
            )
            BookcaseSortType.entries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(type.labelRes), style = MaterialTheme.typography.bodyLarge)
                            val descText = when (type) {
                                BookcaseSortType.DEFAULT -> "加入书架的先后时间"
                                BookcaseSortType.LATEST -> "按轻小说章节更新时间"
                                BookcaseSortType.LAST_READ -> "按最后阅读时间排序"
                                BookcaseSortType.NAME -> "按作品中文拼音顺序"
                                BookcaseSortType.WORD_COUNT -> "按作品全书字数规模"
                            }
                            Text(
                                descText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        RadioButton(
                            selected = ui.sortType == type,
                            onClick = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(type)
                    },
                )
            }

            if (ui.sortType != BookcaseSortType.DEFAULT) {
                Spacer(Modifier.height(4.dp))
                val (normalDesc, reversedDesc) = when (ui.sortType) {
                    BookcaseSortType.LATEST ->
                        stringResource(R.string.bookcase_sort_latest_desc) to stringResource(R.string.bookcase_sort_latest_asc)
                    BookcaseSortType.LAST_READ ->
                        stringResource(R.string.bookcase_sort_last_read_desc) to stringResource(R.string.bookcase_sort_last_read_asc)
                    BookcaseSortType.NAME ->
                        stringResource(R.string.bookcase_sort_name_asc) to stringResource(R.string.bookcase_sort_name_desc)
                    BookcaseSortType.WORD_COUNT ->
                        stringResource(R.string.bookcase_sort_word_desc) to stringResource(R.string.bookcase_sort_word_asc)
                    BookcaseSortType.DEFAULT -> "" to ""
                }

                DropdownMenuItem(
                    text = { Text(normalDesc, style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        RadioButton(
                            selected = !ui.sortReversed,
                            onClick = null,
                        )
                    },
                    onClick = {
                        if (ui.sortReversed) onToggleReverse()
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(reversedDesc, style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        RadioButton(
                            selected = ui.sortReversed,
                            onClick = null,
                        )
                    },
                    onClick = {
                        if (!ui.sortReversed) onToggleReverse()
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 列表视图模式下的高质感实体卡片。
 */
@Composable
private fun BookcaseCard(
    entry: BookcaseEntry,
    onOpenBook: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    TonalCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .bounceClick(targetScale = 0.97f, onClick = { onOpenBook(entry.bookId) }),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(118.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            BookCover3D(
                url = entry.coverUrl,
                width = 72.dp,
                height = 102.dp,
                contentDescription = entry.title,
                cornerRadius = 6.dp,
                elevation = 6.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (entry.author.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            entry.author,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (entry.progressTotal > 0) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(
                                    R.string.bookcase_read_chapters,
                                    entry.readCount,
                                    entry.progressTotal,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${(entry.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { entry.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 个人账户中心与云端书架同步弹窗。
 * 结合文库8账号体系，支持查看当前账户状态、藏书统计概览、单向云端同步与账号管理。
 */
@Composable
private fun BookcaseAccountCenterDialog(
    isCustomAccount: Boolean,
    currentUsername: String?,
    isSyncing: Boolean,
    totalBooks: Int,
    readingBooks: Int,
    finishedBooks: Int,
    onDismiss: () -> Unit,
    onLoginAndSync: (String, String, (Boolean, String) -> Unit) -> Unit,
    onSyncDirectly: ((Boolean, String) -> Unit) -> Unit,
    onLogout: (() -> Unit) -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showLoginFields by remember { mutableStateOf(!isCustomAccount) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.settings_account_logout_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.settings_account_logout_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout {
                            showLoginFields = true
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_account_logout), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isSyncing) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (showLoginFields && !isCustomAccount) {
                        stringResource(R.string.settings_account_login_title)
                    } else {
                        stringResource(R.string.bookcase_account_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. 用户信息卡片（已登录或访客展示）
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isCustomAccount && !currentUsername.isNullOrBlank()) {
                                Text(
                                    text = currentUsername.take(2).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isCustomAccount && !currentUsername.isNullOrBlank()) {
                                    currentUsername
                                } else {
                                    stringResource(R.string.settings_builtin_account)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isCustomAccount) Icons.Filled.CheckCircle else Icons.Filled.AccountCircle,
                                    contentDescription = null,
                                    tint = if (isCustomAccount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isCustomAccount) {
                                        stringResource(R.string.bookcase_account_badge_verified)
                                    } else {
                                        stringResource(R.string.bookcase_account_badge_guest)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCustomAccount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // 2. 藏书与阅读统计卡片（3 列并排）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccountStatItem(
                        label = stringResource(R.string.bookcase_account_books_local),
                        count = totalBooks,
                        modifier = Modifier.weight(1f),
                    )
                    AccountStatItem(
                        label = stringResource(R.string.bookcase_account_books_reading),
                        count = readingBooks,
                        modifier = Modifier.weight(1f),
                    )
                    AccountStatItem(
                        label = stringResource(R.string.bookcase_account_books_read),
                        count = finishedBooks,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 3. 登录输入框（若未登录或处于切换账号状态）
                if (showLoginFields) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = user,
                            onValueChange = {
                                user = it
                                errorMessage = null
                            },
                            label = { Text(stringResource(R.string.settings_account_username)) },
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
                            label = { Text(stringResource(R.string.settings_account_password)) },
                            singleLine = true,
                            enabled = !isSyncing,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_account_sync_bookshelf_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )

                if (errorMessage != null) {
                    Text(
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
                            } else {
                                showLoginFields = false
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
                    Text(stringResource(R.string.settings_account_syncing))
                } else {
                    Text(
                        if (showLoginFields) {
                            stringResource(R.string.settings_account_login_and_sync)
                        } else {
                            stringResource(R.string.settings_account_sync_now)
                        }
                    )
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!showLoginFields && isCustomAccount) {
                    TextButton(onClick = { showLogoutConfirm = true }, enabled = !isSyncing) {
                        Text(stringResource(R.string.settings_account_logout), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showLoginFields = true }, enabled = !isSyncing) {
                        Text(stringResource(R.string.settings_account_switch))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !isSyncing) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun AccountStatItem(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

