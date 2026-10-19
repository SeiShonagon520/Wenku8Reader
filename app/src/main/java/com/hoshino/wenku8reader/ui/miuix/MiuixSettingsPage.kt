package com.hoshino.wenku8reader.ui.miuix

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.ui.AppViewModelProvider
import com.hoshino.wenku8reader.ui.settings.SettingsViewModel

/**
 * MIUIX 设置主页——**只放分类入口**（PiliPlus 模式，参考其 `pages/setting/view.dart`：
 * 主页是「分类 + 副标题 + 箭头」的列表，具体设置项都在各分类的二级页）。
 *
 * 与 Material 版设置页完全独立（后者仍是单页内联分组，见 §5.6 的约定）。
 * 逻辑（设置读写、更新检查）复用同一个 [SettingsViewModel] 与 `UpdateCenter`。
 */
@Composable
fun MiuixSettingsPage(
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenExperimental: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val currentUsername by vm.currentUsername.collectAsStateWithLifecycle()
    val isCustomAccount by vm.isCustomAccount.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncingBookshelf.collectAsStateWithLifecycle()

    var showLoginDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    MiuixPage(
        title = stringResource(R.string.tab_settings),
        actions = {
            MiuixIconButton(
                icon = Icons.Filled.Download,
                contentDescription = stringResource(R.string.action_downloads),
                onClick = onOpenDownloads,
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))
            MiuixSection(title = stringResource(R.string.settings_section_account)) {
                if (isCustomAccount) {
                    MiuixRow(
                        title = stringResource(R.string.settings_account_personal, currentUsername ?: ""),
                        summary = stringResource(R.string.settings_account_personal_desc),
                        icon = Icons.Filled.AccountCircle,
                    )
                    MiuixRowDivider()
                    MiuixArrowRow(
                        title = stringResource(R.string.settings_account_sync_bookshelf),
                        summary = if (isSyncing) stringResource(R.string.settings_account_syncing)
                        else "拉取网页端个人收藏，单向导入至本地书架",
                        icon = Icons.Filled.Sync,
                        onClick = {
                            vm.syncCloudBookshelf { _, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    MiuixRowDivider()
                    MiuixArrowRow(
                        title = stringResource(R.string.settings_account_logout),
                        summary = "退出登录并恢复内置共享账号",
                        icon = Icons.Filled.ExitToApp,
                        onClick = { showLogoutDialog = true },
                    )
                } else {
                    MiuixArrowRow(
                        title = stringResource(R.string.settings_builtin_account),
                        summary = "点击登录个人账号以同步云端书架",
                        icon = Icons.Filled.AccountCircle,
                        onClick = { showLoginDialog = true },
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            // 各分类入口（点击进二级页）
            MiuixSection {
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_appearance),
                    summary = stringResource(R.string.settings_category_appearance_summary),
                    icon = Icons.Filled.DarkMode,
                    onClick = onOpenAppearance,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_reading),
                    summary = stringResource(R.string.settings_category_reading_summary),
                    icon = Icons.Filled.Tune,
                    onClick = onOpenReading,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_network),
                    summary = stringResource(R.string.settings_category_network_summary),
                    icon = Icons.Filled.Public,
                    onClick = onOpenNetwork,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_update),
                    summary = stringResource(R.string.settings_category_update_summary),
                    icon = Icons.Filled.SystemUpdate,
                    onClick = onOpenUpdate,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_storage),
                    summary = stringResource(R.string.settings_category_storage_summary),
                    icon = Icons.Filled.Storage,
                    onClick = onOpenStorageSettings,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_experimental),
                    summary = stringResource(R.string.settings_category_experimental_summary),
                    icon = Icons.Filled.Animation,
                    onClick = onOpenExperimental,
                )
                MiuixRowDivider()
                MiuixArrowRow(
                    title = stringResource(R.string.settings_section_about),
                    summary = stringResource(R.string.settings_category_about_summary),
                    icon = Icons.Filled.Info,
                    onClick = onOpenAbout,
                )
            }
            Spacer(Modifier.height(24.dp + LocalFloatingBarInset.current))
        }
    }

    // 登录个人账号弹窗
    if (showLoginDialog) {
        MiuixPersonalLoginDialog(
            onDismiss = { showLoginDialog = false },
            onLogin = { user, pass, onFinished ->
                vm.login(user, pass) { success, msg ->
                    onFinished(success, msg)
                    if (success) {
                        showLoginDialog = false
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        vm.syncCloudBookshelf { _, syncMsg ->
                            Toast.makeText(context, syncMsg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    // 退出账号确认弹窗
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.settings_account_logout_confirm_title)) },
            text = { Text(stringResource(R.string.settings_account_logout_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        vm.logout()
                        Toast.makeText(context, "已退出登录，恢复内置共享账号", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** MIUIX 模式下的个人账号登录弹窗 */
@Composable
private fun MiuixPersonalLoginDialog(
    onDismiss: () -> Unit,
    onLogin: (user: String, pass: String, onFinished: (Boolean, String) -> Unit) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.settings_account_login_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_account_login_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.settings_account_username)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.settings_account_password)) },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = "请输入账号和密码"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    onLogin(username, password) { success, msg ->
                        isLoading = false
                        if (!success) {
                            errorMessage = msg
                        }
                    }
                },
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.settings_account_login))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
