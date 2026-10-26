package com.hoshino.wenku8reader.data.local.webdav

import android.content.Context
import com.hoshino.wenku8reader.data.local.LibraryBackupManager
import com.hoshino.wenku8reader.data.local.LibraryBackupSummary
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class WebDavBackupState(
    val serverUrl: String = "",
    val remoteDirectory: String = "Wenku8Reader/backups",
    val username: String = "",
    val passwordSaved: Boolean = false,
    val automaticBackupEnabled: Boolean = false,
    val lastBackupAt: Long? = null,
    val lastBackupError: String? = null,
    val lastBackupWarning: String? = null,
    val isTesting: Boolean = false,
    val isBackingUp: Boolean = false,
    val isLoadingSnapshots: Boolean = false,
    val restoringFileName: String? = null,
    val snapshots: List<WebDavSnapshot> = emptyList(),
)

data class WebDavBackupResult(
    val summary: LibraryBackupSummary,
    val snapshot: WebDavSnapshot,
    val cleanupWarning: String? = null,
)

data class WebDavRestoreResult(
    val summary: LibraryBackupSummary,
    val snapshot: WebDavSnapshot,
)

/** Orchestrates the existing JSON backup codec, WebDAV transport, retention and WorkManager schedule. */
class WebDavBackupManager internal constructor(
    context: Context,
    private val backupManager: LibraryBackupManager,
    private val configStore: WebDavConfigStore,
    private val scheduler: WebDavBackupScheduler,
) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val snapshotCounter = AtomicLong(0L)
    private val _state = MutableStateFlow(configStore.read().toUiState())
    val state: StateFlow<WebDavBackupState> = _state.asStateFlow()

    /** Called once by AppContainer on startup. Does not enqueue the initial one-time upload again. */
    fun restoreSchedule() {
        try {
            scheduler.ensureDaily(configStore.read().automaticBackupEnabled)
        } catch (e: Exception) {
            configStore.recordBackupFailure(userFacingError(e))
            refreshConfiguration()
        }
    }

    suspend fun saveAndTest(
        serverUrl: String,
        remoteDirectory: String,
        username: String,
        password: String,
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.update { it.copy(isTesting = true) }
            val wasEnabled = configStore.read().automaticBackupEnabled
            if (wasEnabled) {
                configStore.setAutomaticBackupEnabled(false)
                scheduler.ensureDaily(false)
            }
            try {
                configStore.save(serverUrl, remoteDirectory, username, password)
                _state.update { it.copy(snapshots = emptyList()) }
                val client = createClient()
                client.testConnection()
                client.ensureRemoteDirectory()
                if (wasEnabled) {
                    configStore.setAutomaticBackupEnabled(true)
                    scheduler.enableDailyWithInitialBackup()
                }
                refreshConfiguration()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                configStore.setAutomaticBackupEnabled(false)
                scheduler.ensureDaily(false)
                configStore.recordBackupFailure(userFacingError(e))
                refreshConfiguration()
                throw e
            } finally {
                _state.update { it.copy(isTesting = false) }
            }
        }
    }

    suspend fun setAutomaticBackupEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (!enabled) {
                configStore.setAutomaticBackupEnabled(false)
                scheduler.ensureDaily(false)
                refreshConfiguration()
                return@withLock
            }

            _state.update { it.copy(isTesting = true) }
            try {
                val credentials = configStore.credentials()
                    ?: throw IllegalStateException("请先保存 WebDAV 配置并测试连接")
                val client = WebDavClient(credentials)
                client.testConnection()
                client.ensureRemoteDirectory()
                configStore.setAutomaticBackupEnabled(true)
                scheduler.enableDailyWithInitialBackup()
                refreshConfiguration()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                configStore.setAutomaticBackupEnabled(false)
                scheduler.ensureDaily(false)
                configStore.recordBackupFailure(userFacingError(e))
                refreshConfiguration()
                throw e
            } finally {
                _state.update { it.copy(isTesting = false) }
            }
        }
    }

    suspend fun clearConfiguration() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            scheduler.ensureDaily(false)
            configStore.clear()
            _state.value = WebDavBackupState()
        }
    }

    suspend fun backupNow(): WebDavBackupResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.update { it.copy(isBackingUp = true) }
            try {
                val result = performBackup()
                val snapshots = try {
                    createClient().listSnapshots().take(WebDavSnapshotRetention.KEEP_COUNT)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                refreshConfiguration()
                if (snapshots != null) _state.update { it.copy(snapshots = snapshots) }
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                configStore.recordBackupFailure(userFacingError(e))
                refreshConfiguration()
                throw e
            } finally {
                _state.update { it.copy(isBackingUp = false) }
            }
        }
    }

    /** Worker entry point. Retry only transient transport/server failures; permanent errors wait for the next day. */
    internal suspend fun backupForWorker(): WorkerBackupOutcome = try {
        backupNow()
        WorkerBackupOutcome.SUCCESS
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (WebDavWorkerRetryPolicy.shouldRetry(e)) WorkerBackupOutcome.RETRY
        else WorkerBackupOutcome.PERMANENT_FAILURE
    }

    suspend fun refreshSnapshots(): List<WebDavSnapshot> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.update { it.copy(isLoadingSnapshots = true) }
            try {
                val snapshots = createClient().listSnapshots().take(WebDavSnapshotRetention.KEEP_COUNT)
                _state.update { it.copy(snapshots = snapshots) }
                snapshots
            } finally {
                _state.update { it.copy(isLoadingSnapshots = false) }
            }
        }
    }

    suspend fun restore(snapshot: WebDavSnapshot): WebDavRestoreResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            require(WebDavClient.parseSnapshotTimestamp(snapshot.fileName) != null) { "远端备份文件名无效" }
            _state.update { it.copy(restoringFileName = snapshot.fileName) }
            val tempFile = createTempBackupFile()
            try {
                createClient().downloadSnapshot(snapshot, tempFile)
                val summary = tempFile.inputStream().use { backupManager.importFrom(it) }
                WebDavRestoreResult(summary, snapshot)
            } finally {
                tempFile.delete()
                _state.update { it.copy(restoringFileName = null) }
            }
        }
    }

    private suspend fun performBackup(): WebDavBackupResult {
        val client = createClient()
        val tempFile = createTempBackupFile()
        try {
            val summary = tempFile.outputStream().use { backupManager.exportTo(it) }
            val uploaded = client.uploadSnapshot(tempFile)
            var warning: String? = null
            try {
                val oldSnapshots = WebDavSnapshotRetention.toDelete(client.listSnapshots())
                oldSnapshots.forEach { client.deleteSnapshot(it.fileName) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                warning = "备份已上传，但清理旧版本失败：${userFacingError(e)}"
            }
            configStore.recordBackupSuccess(System.currentTimeMillis(), warning)
            return WebDavBackupResult(
                summary = summary,
                snapshot = uploaded,
                cleanupWarning = warning,
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun createClient(): WebDavClient =
        WebDavClient(configStore.credentials() ?: throw IllegalStateException("请先保存 WebDAV 配置"))

    private fun createTempBackupFile(): File {
        val directory = File(appContext.cacheDir, "webdav-backup").apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("无法创建 WebDAV 临时目录")
        return File.createTempFile("backup-${snapshotCounter.incrementAndGet()}-", ".json", directory)
    }

    private fun refreshConfiguration() {
        val configuration = configStore.read()
        _state.update {
            it.copy(
                serverUrl = configuration.serverUrl,
                remoteDirectory = configuration.remoteDirectory,
                username = configuration.username,
                passwordSaved = configuration.passwordSaved,
                automaticBackupEnabled = configuration.automaticBackupEnabled,
                lastBackupAt = configuration.lastBackupAt,
                lastBackupError = configuration.lastBackupError,
                lastBackupWarning = configuration.lastBackupWarning,
            )
        }
    }

    private fun userFacingError(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "网络或存储操作失败"
}

internal enum class WorkerBackupOutcome {
    SUCCESS,
    RETRY,
    PERMANENT_FAILURE,
}

internal object WebDavWorkerRetryPolicy {
    fun shouldRetry(error: Throwable): Boolean = when (error) {
        is WebDavException -> error.retryable
        is IOException -> true
        else -> false
    }
}

internal object WebDavSnapshotRetention {
    const val KEEP_COUNT = 3

    fun toDelete(snapshots: List<WebDavSnapshot>): List<WebDavSnapshot> =
        snapshots.sortedByDescending { it.timestampMillis }.drop(KEEP_COUNT)
}

private fun WebDavConfiguration.toUiState() = WebDavBackupState(
    serverUrl = serverUrl,
    remoteDirectory = remoteDirectory,
    username = username,
    passwordSaved = passwordSaved,
    automaticBackupEnabled = automaticBackupEnabled,
    lastBackupAt = lastBackupAt,
    lastBackupError = lastBackupError,
    lastBackupWarning = lastBackupWarning,
)
