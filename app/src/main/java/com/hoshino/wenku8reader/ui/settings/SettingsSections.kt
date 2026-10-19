package com.hoshino.wenku8reader.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.data.UpdateCenter
import com.hoshino.wenku8reader.data.Wenku8Hosts
import com.hoshino.wenku8reader.data.local.ReaderSettingsState
import com.hoshino.wenku8reader.ui.components.ExpressiveSlider
import com.hoshino.wenku8reader.ui.components.HapticFeedback
import com.hoshino.wenku8reader.ui.components.SegmentedColumn
import com.hoshino.wenku8reader.ui.components.SegmentedDropdownItem
import com.hoshino.wenku8reader.ui.components.SegmentedListItem
import com.hoshino.wenku8reader.ui.components.SegmentedSwitchItem
import com.hoshino.wenku8reader.ui.theme.seedColorOptions
import com.hoshino.wenku8reader.ui.theme.UiStyle

// ------------------------------------------------------------------ //
// 分组
// ------------------------------------------------------------------ //

/**
 * 账号分组：支持登录个人账号并单向同步云端书架，也可切换回内置免密账号。
 */
@Composable
internal fun AccountSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val currentUsername by vm.currentUsername.collectAsStateWithLifecycle()
    val isCustomAccount by vm.isCustomAccount.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncingBookshelf.collectAsStateWithLifecycle()

    var showLoginDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val items = mutableListOf<@Composable () -> Unit>()

    if (isCustomAccount) {
        // 个人账号卡片
        items.add {
            SegmentedListItem(
                leadingContent = {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_account_personal, currentUsername ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                supportingContent = { Text(stringResource(R.string.settings_account_personal_desc)) },
                trailingContent = {
                    TextButton(onClick = { showLogoutDialog = true }) {
                        Text(stringResource(R.string.settings_account_logout), color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
        // 云端书架同步项
        items.add {
            SegmentedListItem(
                leadingContent = {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                headlineContent = { Text(stringResource(R.string.settings_account_sync_bookshelf)) },
                supportingContent = {
                    Text(
                        if (isSyncing) stringResource(R.string.settings_account_syncing)
                        else "拉取网页端个人收藏，单向导入至本地书架"
                    )
                },
                trailingContent = {
                    FilledTonalButton(
                        onClick = {
                            vm.syncCloudBookshelf { _, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isSyncing,
                    ) {
                        Text(if (isSyncing) "同步中" else "立即同步")
                    }
                },
            )
        }
    } else {
        // 内置账号展示
        items.add {
            SegmentedListItem(
                leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_builtin_account)) },
                supportingContent = { Text(stringResource(R.string.settings_account_builtin_desc)) },
                trailingContent = {
                    Button(onClick = { showLoginDialog = true }) {
                        Text(stringResource(R.string.settings_account_login))
                    }
                },
            )
        }
    }

    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_section_account),
        items = items,
    )

    // 登录个人账号弹窗
    if (showLoginDialog) {
        PersonalAccountLoginDialog(
            onDismiss = { showLoginDialog = false },
            onLogin = { user, pass, onFinished ->
                vm.login(user, pass) { success, msg ->
                    onFinished(success, msg)
                    if (success) {
                        showLoginDialog = false
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        // 登录成功后自动拉取一次云端书架
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

/** 个人账号登录输入弹窗 */
@Composable
private fun PersonalAccountLoginDialog(
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

/** 外观分组：界面语言、深色模式、纯黑模式、动态取色。 */
@Composable
internal fun AppearanceSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
    context: Context,
) {
    val darkModeOptions = listOf(
        "system" to R.string.settings_dark_system,
        "light" to R.string.settings_dark_light,
        "dark" to R.string.settings_dark_dark,
    )
    val darkModeLabels = darkModeOptions.map { (_, res) -> stringResource(res) }
    // 界面语言（切换后重建 Activity 使 attachBaseContext 生效）
    val languageOptions = listOf(
        "system" to R.string.settings_language_system,
        "zh-CN" to R.string.settings_language_simplified,
        "zh-TW" to R.string.settings_language_traditional,
    )
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_section_appearance),
        items = listOf(
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.Language,
                    title = stringResource(R.string.settings_language),
                    summary = null,
                    items = languageOptions.map { (_, res) -> stringResource(res) },
                    selectedIndex = languageOptions.indexOfKey(rs.appLanguage),
                    onItemSelected = { index ->
                        val lang = languageOptions[index].first
                        if (lang != rs.appLanguage) {
                            vm.setAppLanguage(lang)
                            // LocalContext 在预览/测试或被包装时不是 Activity，直接强转会静默失败
                            // （语言不生效也没有提示），故递归解包 ContextWrapper 找宿主 Activity。
                            context.findActivity()?.recreate()
                        }
                    },
                )
            },
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.DarkMode,
                    title = stringResource(R.string.settings_dark_mode),
                    summary = null,
                    items = darkModeLabels,
                    selectedIndex = darkModeOptions.indexOfKey(rs.darkMode),
                    onItemSelected = { index ->
                        vm.setDarkMode(darkModeOptions[index].first)
                    },
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.DarkMode,
                    title = stringResource(R.string.settings_amoled),
                    summary = null,
                    checked = rs.amoled,
                    onCheckedChange = vm::setAmoled,
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.Palette,
                    title = stringResource(R.string.settings_dynamic_color),
                    summary = null,
                    checked = rs.dynamicColor,
                    onCheckedChange = vm::setDynamicColor,
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.Animation,
                    title = stringResource(R.string.settings_expressive_motion),
                    summary = null,
                    checked = rs.expressiveMotion,
                    onCheckedChange = vm::setExpressiveMotion,
                )
            },
        ),
    )
}

/** 手动取色分组（仅在关闭动态取色时展示；色值取自 theme 层的单一来源 [seedColorOptions]）。 */
@Composable
internal fun ManualColorSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
) {
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_manual_color),
        items = listOf {
            SegmentedListItem(
                headlineContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        seedColorOptions.forEach { color ->
                            ColorDot(
                                color = Color(color),
                                selected = rs.seedColor == color,
                                onClick = { vm.setSeedColor(color) },
                            )
                        }
                    }
                },
            )
        },
    )
}

/**
 * 实验性分组：UI 风格切换（Material 3 Expressive ↔ MIUIX）。
 *
 * 参考 SukiSU-Ultra：整套界面代码只有一份，由 [LocalUiStyle] 决定公共组件走哪套实现，
 * 根主题相应切换 `MaterialExpressiveTheme` / `MiuixTheme`，因此切换无需重启。
 */
@Composable
internal fun ExperimentalSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
) {
    val styles = UiStyle.entries
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_section_experimental),
        items = listOf(
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.Animation,
                    title = stringResource(R.string.settings_ui_style),
                    summary = stringResource(R.string.settings_ui_style_summary),
                    items = styles.map { style ->
                        stringResource(
                            when (style) {
                                UiStyle.MATERIAL3 -> R.string.settings_ui_style_material3
                                UiStyle.MIUIX -> R.string.settings_ui_style_miuix
                            },
                        )
                    },
                    selectedIndex = styles.indexOf(UiStyle.fromKey(rs.uiStyle)).coerceAtLeast(0),
                    onItemSelected = { index -> vm.setUiStyle(styles[index].key) },
                )
            },
            // 说明：这里**不再**放「悬浮底栏 / 液态玻璃」两个开关。
            // M3 底栏是固定样式的 NavigationBar，不接受这两个设置（见 MainScaffold 的
            // `floatingBar = isMiuixStyle() && …`），摆在 MD3 页里只会让人以为"开了却没效果"。
            // 它们只属于 MIUIX 的实验性页（MiuixExperimentalPage）。
        ),
    )
}

/** 通用分组：触觉反馈总开关。 */
@Composable
internal fun GeneralSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
) {
    val context = LocalContext.current
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_section_general),
        items = listOf(
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.Vibration,
                    title = stringResource(R.string.settings_haptics),
                    summary = null,
                    checked = rs.hapticsEnabled,
                    onCheckedChange = { enabled ->
                        vm.setHapticsEnabled(enabled)
                        if (enabled) {
                            HapticFeedback.vibrate(context, rs.hapticsStrength)
                        }
                    },
                )
            },
        ),
    )
}

/** 振动强度分组（仅在开启触觉反馈时展示）。 */
@Composable
internal fun HapticsStrengthSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
) {
    val context = LocalContext.current
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_haptics_strength_title),
        items = listOf {
            SegmentedListItem(
                headlineContent = {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.settings_haptics_strength),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${rs.hapticsStrength}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ExpressiveSlider(
                            value = rs.hapticsStrength.toFloat(),
                            onValueChange = { newValue ->
                                val intVal = newValue.toInt()
                                if (intVal != rs.hapticsStrength) {
                                    vm.setHapticsStrength(intVal)
                                    // 调节滑动跨越每 10% 步进或极值时触发试震预览
                                    if (intVal % 10 == 0 || intVal == 1 || intVal == 100) {
                                        HapticFeedback.vibrate(context, intVal)
                                    }
                                }
                            },
                            onValueChangeFinished = {
                                // 调节完毕松手时以最新力度触发试震
                                HapticFeedback.vibrate(context, rs.hapticsStrength)
                            },
                            valueRange = 0f..100f,
                        )
                    }
                },
            )
        },
    )
}

/** 网络分组：主站镜像切换（清单来自单一来源 [Wenku8Hosts.MIRRORS]）。 */
@Composable
internal fun NetworkSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
) {
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_section_network),
        items = listOf {
            SegmentedDropdownItem(
                icon = Icons.Filled.Public,
                title = stringResource(R.string.settings_primary_mirror),
                summary = stringResource(R.string.settings_primary_mirror_summary),
                items = Wenku8Hosts.MIRRORS,
                selectedIndex = Wenku8Hosts.MIRRORS.indexOfKey(rs.primaryMirror),
                onItemSelected = { index -> vm.setPrimaryMirror(Wenku8Hosts.MIRRORS[index]) },
            )
        },
    )
}

/** 更新分组：启动检查、更新通道、更新源、手动检查。 */
@Composable
internal fun UpdateSection(
    rs: ReaderSettingsState,
    vm: SettingsViewModel,
    version: String?,
    updateCenter: UpdateCenter,
) {
    val updateChannelOptions = listOf(
        "stable" to R.string.settings_update_channel_stable,
        "beta" to R.string.settings_update_channel_beta,
    )
    val updateSourceOptions = listOf(
        "github" to R.string.settings_update_source_github,
        "gh_proxy" to R.string.settings_update_source_ghproxy,
    )
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(R.string.settings_section_update),
        items = listOf(
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(R.string.settings_check_on_startup),
                    summary = stringResource(R.string.settings_check_on_startup_summary),
                    checked = rs.checkUpdatesOnStartup,
                    onCheckedChange = vm::setCheckUpdatesOnStartup,
                )
            },
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.settings_update_channel),
                    summary = stringResource(R.string.settings_update_channel_summary),
                    items = updateChannelOptions.map { (_, res) -> stringResource(res) },
                    selectedIndex = updateChannelOptions.indexOfKey(rs.updateChannel),
                    onItemSelected = { index -> vm.setUpdateChannel(updateChannelOptions[index].first) },
                )
            },
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.CloudDownload,
                    title = stringResource(R.string.settings_update_source),
                    summary = stringResource(R.string.settings_update_source_summary),
                    items = updateSourceOptions.map { (_, res) -> stringResource(res) },
                    selectedIndex = updateSourceOptions.indexOfKey(rs.updateSource),
                    onItemSelected = { index -> vm.setUpdateSource(updateSourceOptions[index].first) },
                )
            },
            {
                SegmentedListItem(
                    leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_check_update)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_version, version ?: "-"))
                    },
                    onClick = { updateCenter.check(manual = true) },
                )
            },
        ),
    )
}

// 分类二级页（PiliPlus 模式）

@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
    )
}
