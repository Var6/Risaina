package com.risaina.app.core.sync

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.FAILED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import org.junit.Assert.assertEquals
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
}
