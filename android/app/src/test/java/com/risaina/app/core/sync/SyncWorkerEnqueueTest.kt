package com.risaina.app.core.sync

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.FAILED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a push does about the push-sync work chain (SyncWorker.enqueueFromPush). */
class SyncWorkerEnqueueTest {
    @Test fun nothingQueuedStartsAChain() {
        assertEquals(PushEnqueue.APPEND, pushEnqueue(emptyList()))
        assertEquals(PushEnqueue.APPEND, pushEnqueue(listOf(SUCCEEDED to 0, FAILED to 3, CANCELLED to 1)))
    }

    @Test fun aFreshRequestThatHasNotStartedCoversABurst() {
        assertEquals(PushEnqueue.SKIP, pushEnqueue(listOf(ENQUEUED to 0)))
    }

    @Test fun aRunningSyncGetsOneFreshSyncBehindIt() {
        // It may have fetched before this push's event existed.
        assertEquals(PushEnqueue.APPEND, pushEnqueue(listOf(RUNNING to 0)))
        // ...and only one: a second push finds that one waiting.
        assertEquals(PushEnqueue.SKIP, pushEnqueue(listOf(RUNNING to 0, BLOCKED to 0)))
    }

    @Test fun aSyncWaitingOutARetryIsReplacedByAnExpeditedOne() {
        // WorkManager reschedules a backed-off request as a plain delayed job that ColorOS can hold
        // for hours; receipts and widget photos would wait behind it.
        assertEquals(PushEnqueue.REPLACE, pushEnqueue(listOf(ENQUEUED to 1)))
        assertEquals(PushEnqueue.REPLACE, pushEnqueue(listOf(ENQUEUED to 3, BLOCKED to 0)))
    }

    // --- the request itself ------------------------------------------------------------------

    @Test fun android11AndBelowGetAPlainRequestWithNoCatchingUpNotification() {
        for (sdk in listOf(26, 29, 30)) {
            val spec = SyncWorker.pushRequestFor(sdk).workSpec
            assertFalse("API $sdk", spec.expedited)
            assertEquals(SyncWorker::class.java.name, spec.workerClassName)
            assertTrue(spec.input.getBoolean(SyncWorker.KEY_NOTIFY, false))
            assertEquals(androidx.work.NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        }
    }

    @Test fun android12AndUpRunItExpeditedOrAsNormalWorkOutOfQuota() {
        for (sdk in listOf(31, 36)) {
            val spec = SyncWorker.pushRequestFor(sdk).workSpec
            assertTrue("API $sdk", spec.expedited)
            assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
            assertTrue(spec.input.getBoolean(SyncWorker.KEY_NOTIFY, false))
        }
    }
}
