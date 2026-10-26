package com.hoshino.wenku8reader.data.local.webdav

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequest
import androidx.work.OneTimeWorkRequest
import java.util.concurrent.TimeUnit

/** Persistent, unique daily upload schedule. Credentials are never placed in WorkManager Data. */
internal class WebDavBackupScheduler(
    private val queue: WebDavWorkQueue,
) {
    constructor(context: Context) : this(AndroidWebDavWorkQueue(WorkManager.getInstance(context.applicationContext)))

    fun ensureDaily(enabled: Boolean) {
        if (enabled) {
            queue.enqueuePeriodic(
                DAILY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest(),
            )
        } else {
            disable()
        }
    }

    fun enableDailyWithInitialBackup() {
        queue.enqueuePeriodic(
            DAILY_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(),
        )
        queue.enqueueOneTime(
            INITIAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            initialRequest(),
        )
    }

    fun disable() {
        queue.cancelUnique(DAILY_WORK_NAME)
        queue.cancelUnique(INITIAL_WORK_NAME)
    }

    internal companion object {
        const val DAILY_WORK_NAME = "wenku8_webdav_daily_backup"
        const val INITIAL_WORK_NAME = "wenku8_webdav_initial_backup"
        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<WebDavBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(NETWORK_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        fun initialRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WebDavBackupWorker>()
                .setConstraints(NETWORK_CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}

internal interface WebDavWorkQueue {
    fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest)
    fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
    fun cancelUnique(name: String)
}

private class AndroidWebDavWorkQueue(private val workManager: WorkManager) : WebDavWorkQueue {
    override fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        workManager.enqueueUniquePeriodicWork(name, policy, request)
    }

    override fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancelUnique(name: String) {
        workManager.cancelUniqueWork(name)
    }
}
