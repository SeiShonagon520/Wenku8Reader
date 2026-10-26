package com.hoshino.wenku8reader.data.local.webdav

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hoshino.wenku8reader.Wenku8Application

/** Periodic upload worker; it obtains credentials from the app's encrypted local store. */
@Keep
class WebDavBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? Wenku8Application ?: return Result.failure()
        return when (app.container.webDavBackupManager.backupForWorker()) {
            WorkerBackupOutcome.SUCCESS -> Result.success()
            WorkerBackupOutcome.RETRY -> Result.retry()
            // Permanent errors are recorded in settings; periodic work will try again next day.
            WorkerBackupOutcome.PERMANENT_FAILURE -> Result.success()
        }
    }
}
