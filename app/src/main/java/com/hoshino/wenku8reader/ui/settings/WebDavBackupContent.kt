package com.hoshino.wenku8reader.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.data.local.webdav.WebDavSnapshot
import com.hoshino.wenku8reader.ui.common.formatByteSize
import java.text.DateFormat
import java.util.Date

/** Shared WebDAV controls hosted in both Material and MIUIX storage settings pages. */
@Composable
fun WebDavBackupContent(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.webDavState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var serverUrl by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var remoteDirectory by rememberSaveable(state.remoteDirectory) { mutableStateOf(state.remoteDirectory) }
    var username by rememberSaveable(state.username) { mutableStateOf(state.username) }
    // Never put the WebDAV password in saved-instance-state or a Bundle.
    var password by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<WebDavSnapshot?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val formMatchesSaved = serverUrl == state.serverUrl &&
        remoteDirectory == state.remoteDirectory &&
        username == state.username && password.isEmpty()
    val savedConfigurationReady = state.passwordSaved && formMatchesSaved

    LaunchedEffect(Unit) {
        if (state.passwordSaved) vm.refreshWebDavSnapshots { /* Status is also kept in the shared state. */ }
    }

    pendingRestore?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.settings_webdav_restore_title)) },
            text = { Text(stringResource(R.string.settings_webdav_restore_confirm)) },
            confirmButton = {
                TextButton(
                    enabled = state.restoringFileName == null,
                    onClick = {
                        vm.restoreWebDavSnapshot(snapshot) { result ->
                            pendingRestore = null
                            result.fold(
                                onSuccess = {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.settings_webdav_restore_done,
                                            it.summary.bookCount,
                                            it.summary.progressCount,
                                        ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onFailure = {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_backup_failed, it.message ?: "未知错误"),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        }
                    },
                ) { Text(stringResource(R.string.settings_webdav_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_webdav_clear)) },
            text = { Text(stringResource(R.string.settings_webdav_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        vm.clearWebDavConfiguration { result ->
                            result.fold(
                                onSuccess = {
                                    password = ""
                                    serverUrl = ""
                                    username = ""
                                    remoteDirectory = "Wenku8Reader/backups"
                                    Toast.makeText(
                                        context,
                                        R.string.settings_webdav_clear_done,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_backup_failed, it.message ?: "未知错误"),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        }
                    },
                ) { Text(stringResource(R.string.settings_webdav_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_webdav_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_webdav_server_url)) },
            singleLine = true,
            enabled = !state.isTesting && !state.isBackingUp,
        )
        OutlinedTextField(
            value = remoteDirectory,
            onValueChange = { remoteDirectory = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_webdav_remote_directory)) },
            singleLine = true,
            enabled = !state.isTesting && !state.isBackingUp,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_webdav_username)) },
            singleLine = true,
            enabled = !state.isTesting && !state.isBackingUp,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_webdav_password)) },
            supportingText = if (state.passwordSaved) {
                { Text(stringResource(R.string.settings_webdav_password_saved)) }
            } else {
                null
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !state.isTesting && !state.isBackingUp,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isTesting && !state.isBackingUp,
            onClick = {
                vm.saveAndTestWebDav(serverUrl, remoteDirectory, username, password) { result ->
                    result.fold(
                        onSuccess = {
                            password = ""
                            Toast.makeText(
                                context,
                                R.string.settings_webdav_test_success,
                                Toast.LENGTH_SHORT,
                            ).show()
                            vm.refreshWebDavSnapshots { /* Results update the shared state. */ }
                        },
                        onFailure = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_backup_failed, it.message ?: "未知错误"),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            },
        ) {
            Text(
                stringResource(
                    if (state.isTesting) R.string.settings_webdav_testing
                    else R.string.settings_webdav_save_test,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_webdav_auto_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_webdav_auto_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.automaticBackupEnabled,
                enabled = !state.isTesting && !state.isBackingUp &&
                    (savedConfigurationReady || state.automaticBackupEnabled),
                onCheckedChange = { enabled ->
                    vm.setWebDavAutomaticBackup(enabled) { result ->
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    if (enabled) R.string.settings_webdav_auto_enabled
                                    else R.string.settings_webdav_auto_disabled,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onFailure = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_backup_failed, it.message ?: "未知错误"),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = savedConfigurationReady && !state.isTesting && !state.isBackingUp,
                onClick = {
                    vm.backupToWebDav { result ->
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.settings_webdav_upload_done,
                                        it.summary.bookCount,
                                        it.summary.progressCount,
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                                if (it.cleanupWarning != null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_webdav_cleanup_warning, it.cleanupWarning),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onFailure = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_backup_failed, it.message ?: "未知错误"),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                },
            ) {
                Text(stringResource(if (state.isBackingUp) R.string.settings_webdav_uploading else R.string.settings_webdav_upload_now))
            }
            TextButton(
                enabled = savedConfigurationReady && !state.isLoadingSnapshots,
                onClick = {
                    vm.refreshWebDavSnapshots { result ->
                        result.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_backup_failed, it.message ?: "未知错误"),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            ) {
                Text(stringResource(if (state.isLoadingSnapshots) R.string.settings_webdav_loading else R.string.settings_webdav_refresh))
            }
        }

        state.lastBackupAt?.let { timestamp ->
            Text(
                stringResource(
                    R.string.settings_webdav_last_success,
                    DateFormat.getDateTimeInstance().format(Date(timestamp)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.lastBackupError?.let { error ->
            Text(
                stringResource(R.string.settings_webdav_last_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.lastBackupWarning?.let { warning ->
            Text(
                stringResource(R.string.settings_webdav_cleanup_warning, warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.passwordSaved) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.settings_webdav_snapshots_title), style = MaterialTheme.typography.titleSmall)
            if (state.snapshots.isEmpty()) {
                Text(
                    stringResource(R.string.settings_webdav_snapshots_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.snapshots.forEach { snapshot ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.settings_webdav_snapshot_row,
                                DateFormat.getDateTimeInstance().format(Date(snapshot.timestampMillis)),
                                formatByteSize(snapshot.sizeBytes),
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            enabled = savedConfigurationReady && state.restoringFileName == null,
                            onClick = { pendingRestore = snapshot },
                        ) { Text(stringResource(R.string.settings_webdav_restore)) }
                    }
                }
            }
            TextButton(
                enabled = !state.isTesting && !state.isBackingUp && !state.isLoadingSnapshots,
                onClick = { showClearConfirm = true },
            ) { Text(stringResource(R.string.settings_webdav_clear), color = MaterialTheme.colorScheme.error) }
        }
    }
}
