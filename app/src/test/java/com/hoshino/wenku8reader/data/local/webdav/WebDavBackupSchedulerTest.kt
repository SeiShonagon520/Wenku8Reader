package com.hoshino.wenku8reader.data.local.webdav

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBackupSchedulerTest {

    @Test
    fun `enabling schedules one immediate upload and one daily connected-network job`() {
        val queue = RecordingQueue()
        val scheduler = WebDavBackupScheduler(queue)

        scheduler.enableDailyWithInitialBackup()

        assertEquals(2, queue.events.size)
        assertEquals("periodic:${WebDavBackupScheduler.DAILY_WORK_NAME}", queue.events[0])
        assertEquals("initial:${WebDavBackupScheduler.INITIAL_WORK_NAME}", queue.events[1])
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, queue.periodicPolicy)
        assertEquals(ExistingWorkPolicy.KEEP, queue.oneTimePolicy)
        assertEquals(TimeUnitMillis.DAY, queue.periodicRequest?.workSpec?.intervalDuration)
        assertEquals(NetworkType.CONNECTED, queue.periodicRequest?.workSpec?.constraints?.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, queue.oneTimeRequest?.workSpec?.constraints?.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, queue.periodicRequest?.workSpec?.backoffPolicy)
        assertTrue(queue.periodicRequest?.workSpec?.input?.keyValueMap?.isEmpty() == true)
        assertTrue(queue.oneTimeRequest?.workSpec?.input?.keyValueMap?.isEmpty() == true)
    }

    @Test
    fun `startup keeps one periodic job and disabling cancels both unique jobs`() {
        val queue = RecordingQueue()
        val scheduler = WebDavBackupScheduler(queue)

        scheduler.ensureDaily(enabled = true)
        assertEquals(listOf("periodic:${WebDavBackupScheduler.DAILY_WORK_NAME}"), queue.events)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, queue.periodicPolicy)

        queue.events.clear()
        scheduler.ensureDaily(enabled = false)

        assertEquals(
            listOf(
                "cancel:${WebDavBackupScheduler.DAILY_WORK_NAME}",
                "cancel:${WebDavBackupScheduler.INITIAL_WORK_NAME}",
            ),
            queue.events,
        )
        assertTrue(queue.cancelledNames.contains(WebDavBackupScheduler.DAILY_WORK_NAME))
    }

    private class RecordingQueue : WebDavWorkQueue {
        val events = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()
        var periodicPolicy: ExistingPeriodicWorkPolicy? = null
        var oneTimePolicy: ExistingWorkPolicy? = null
        var periodicRequest: PeriodicWorkRequest? = null
        var oneTimeRequest: OneTimeWorkRequest? = null

        override fun enqueuePeriodic(
            name: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            events += "periodic:$name"
            periodicPolicy = policy
            periodicRequest = request
        }

        override fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            events += "initial:$name"
            oneTimePolicy = policy
            oneTimeRequest = request
        }

        override fun cancelUnique(name: String) {
            events += "cancel:$name"
            cancelledNames += name
        }
    }

    private object TimeUnitMillis {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
