package com.hoshino.wenku8reader.ui.miuix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.data.local.ReadingProgressStore
import com.hoshino.wenku8reader.ui.AppViewModelProvider
import com.hoshino.wenku8reader.ui.common.CacheCategory
import com.hoshino.wenku8reader.ui.common.formatByteSize
import com.hoshino.wenku8reader.ui.settings.CacheActionResult
import com.hoshino.wenku8reader.ui.settings.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * MIUIX 存储设置页——与 Material 版（`ui/settings/StorageSettingsScreen.kt`）**完全独立**：
 * 只用 miuix 组件（Card / BasicComponent 系列 / WindowDialog / Slider-less 列表），
 * 分组卡片 + 缩进分隔线是 HyperOS 的形态。逻辑仍复用 [SettingsViewModel]（存储统计与清理动作）。
 */
@Composable
fun MiuixStoragePage(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val rs by vm.ui.collectAsStateWithLifecycle()
    val stats by vm.storageStats.collectAsStateWithLifecycle()
    val cacheSizes by vm.cacheSizes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmCleanupHistory by remember { mutableStateOf(false) }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.exportLibraryBackup(uri) { result ->
                val message = result.fold(
                    onSuccess = {
                        context.getString(
                            R.string.settings_backup_export_done,
                            it.bookCount,
                            it.progressCount,
                        )
                    },
                    onFailure = {
                        context.getString(R.string.settings_backup_failed, it.message ?: "未知错误")
                    },
                )
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importLibraryBackup(uri) { result ->
                val message = result.fold(
                    onSuccess = {
                        context.getString(
                            R.string.settings_backup_import_done,
                            it.bookCount,
                            it.progressCount,
                        )
                    },
                    onFailure = {
                        context.getString(R.string.settings_backup_failed, it.message ?: "未知错误")
                    },
                )
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.cacheResults.collect { result ->
            val text = when (result.kind) {
                CacheActionResult.Kind.READING_HISTORY ->
                    if (result.affectedBooks > 0) {
                        context.getString(R.string.settings_cache_reading_cleaned, result.affectedBooks)
                    } else {
                        context.getString(R.string.settings_cache_reading_nothing)
                    }

                else ->
                    if (result.freedSomething) {
                        context.getString(R.string.settings_cache_freed, formatByteSize(result.freedBytes))
                    } else {
                        context.getString(R.string.settings_cache_nothing)
                    }
            }
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) { vm.refreshStorageStats() }

    MiuixSubPage(title = stringResource(R.string.settings_storage), onBack = onBack) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))

            MiuixSection(
                title = stringResource(R.string.settings_backup_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                MiuixRow(
                    title = stringResource(R.string.settings_backup_export),
                    summary = stringResource(R.string.settings_backup_desc),
                    trailing = {
                        TextButton(onClick = { exportBackup.launch("wenku8reader-backup.json") }) {
                            androidx.compose.material3.Text(stringResource(R.string.settings_backup_export))
                        }
                    },
                )
                MiuixRowDivider()
                MiuixRow(
                    title = stringResource(R.string.settings_backup_import),
                    trailing = {
                        TextButton(onClick = { importBackup.launch(arrayOf("application/json", "text/*")) }) {
                            androidx.compose.material3.Text(stringResource(R.string.settings_backup_import))
                        }
                    },
                )
            }

            // 占用合计（与系统设置同口径）
            MiuixSection(
                title = stringResource(R.string.settings_cache_storage_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                MiuixRow(
                    title = stringResource(R.string.settings_cache_total_cache),
                    icon = Icons.Filled.Storage,
                    trailing = {
                        Text(
                            text = formatByteSize(stats.cacheDirTotal),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    },
                )
                MiuixRowDivider()
                MiuixRow(
                    title = stringResource(R.string.settings_cache_total_data),
                    trailing = {
                        Text(
                            text = formatByteSize(stats.dataDirTotal),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    },
                )
                MiuixRowDivider()
                MiuixRow(
                    title = stringResource(R.string.settings_cache_code_cache),
                    trailing = {
                        Text(
                            text = formatByteSize(stats.codeCache),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    },
                )
            }

            // 按类型清理
            MiuixSection(
                title = stringResource(R.string.settings_cache),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                MiuixClearRow(
                    title = stringResource(R.string.settings_cache_images),
                    sizeText = formatByteSize(stats.imageCache),
                    icon = Icons.Filled.Image,
                    onClear = vm::clearImageCache,
                )
                MiuixRowDivider()
                MiuixClearRow(
                    title = stringResource(R.string.settings_cache_updates),
                    sizeText = formatByteSize(stats.updatePackage),
                    icon = Icons.Filled.SystemUpdate,
                    onClear = vm::clearUpdatePackages,
                )
                MiuixRowDivider()
                MiuixClearRow(
                    title = stringResource(R.string.settings_cache_other_title),
                    sizeText = formatByteSize(stats.webViewCache + stats.tempFiles + stats.otherCache),
                    icon = Icons.Filled.Public,
                    onClear = vm::clearOtherCaches,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_cache_clear_all),
                    summary = stringResource(R.string.settings_cache_clear_all_desc),
                    icon = Icons.Filled.Delete,
                    onClick = { confirmClearAll = true },
                )
            }

            // 网页离线缓存明细 + 上限
            MiuixSection(
                title = stringResource(R.string.settings_cache_html_detail),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                MiuixRow(
                    title = stringResource(R.string.settings_cache_size),
                    trailing = {
                        Text(
                            text = "${formatByteSize(stats.htmlCache)} / ${rs.cacheMaxMb} MB",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    },
                )
                val maxOptions = listOf(30, 50, 100, 200, 500)
                MiuixRowDivider()
                MiuixDropdownRow(
                    title = stringResource(R.string.settings_cache_max),
                    summary = null,
                    icon = Icons.Filled.Storage,
                    items = maxOptions.map { "$it MB" },
                    selectedIndex = maxOptions.indexOf(rs.cacheMaxMb).coerceAtLeast(0),
                    onSelected = { index -> vm.setCacheMaxMb(maxOptions[index]) },
                )
                CacheCategory.entries.forEach { category ->
                    MiuixRowDivider()
                    MiuixClearRow(
                        title = stringResource(category.labelRes),
                        sizeText = formatByteSize(cacheSizes[category.key] ?: 0L),
                        onClear = { vm.clearCache(category.key) },
                    )
                }
            }

            // 本地数据与过期清理
            MiuixSection(
                title = stringResource(R.string.settings_cache_reading_data),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                MiuixRow(
                    title = stringResource(R.string.settings_cache_reading_data),
                    icon = Icons.Filled.History,
                    trailing = {
                        Text(
                            text = formatByteSize(stats.preferences),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    },
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_cache_cleanup_reading),
                    summary = stringResource(
                        R.string.settings_cache_cleanup_reading_desc,
                        ReadingProgressStore.DEFAULT_KEEP_DAYS,
                    ),
                    icon = Icons.Filled.History,
                    onClick = { confirmCleanupHistory = true },
                )
            }
            Spacer(Modifier.height(24.dp + LocalFloatingBarInset.current))
        }
    }

    // HyperOS 风格确认弹窗（miuix WindowDialog）
    if (confirmClearAll) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.settings_cache_confirm_clear_all_title),
            summary = stringResource(R.string.settings_cache_confirm_clear_all_message),
            onDismissRequest = { confirmClearAll = false },
        ) {
            MiuixDialogButtons(
                confirmText = stringResource(R.string.action_confirm),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = {
                    confirmClearAll = false
                    vm.clearAllCaches()
                },
                onDismiss = { confirmClearAll = false },
            )
        }
    }
    if (confirmCleanupHistory) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.settings_cache_confirm_cleanup_title),
            summary = stringResource(
                R.string.settings_cache_confirm_cleanup_message,
                ReadingProgressStore.DEFAULT_KEEP_DAYS,
            ),
            onDismissRequest = { confirmCleanupHistory = false },
        ) {
            MiuixDialogButtons(
                confirmText = stringResource(R.string.action_confirm),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = {
                    confirmCleanupHistory = false
                    vm.cleanupReadingHistory()
                },
                onDismiss = { confirmCleanupHistory = false },
            )
        }
    }
}

/** 带"清理"按钮的行（miuix 行 + 可点击文本，颜色与字号取自 MiuixTheme）。 */
@Composable
private fun MiuixClearRow(
    title: String,
    sizeText: String? = null,
    summary: String? = null,
    onClear: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    MiuixRow(
        title = title,
        summary = summary,
        icon = icon,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!sizeText.isNullOrEmpty()) {
                    Text(
                        text = sizeText,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(R.string.settings_cache_clear),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onClear)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
    )
}

/** 弹窗内的两枚 miuix 按钮（确认 / 取消）。 */
@Composable
private fun MiuixDialogButtons(
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Button(
            onClick = onConfirm,
            // 必须是 fillMaxWidth：写成 fillMaxSize 时按钮会去撑满弹窗的整个可用空间，
            // 结果是弹窗被拉到满屏、次要按钮被挤出可见区域（用户看到的巨型弹窗就是这个原因）。
            modifier = Modifier.fillMaxWidth(),
        ) { Text(confirmText) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(dismissText) }
    }
}
