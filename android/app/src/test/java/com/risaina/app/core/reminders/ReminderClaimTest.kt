package com.risaina.app.core.reminders

import com.risaina.app.core.domain.Reminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** A reminder whose key is claimed is always posted, however the run ends. */
class ReminderClaimTest {
    private fun r(key: String) = Reminder(key, Instant.EPOCH, 0, LocalDate.of(2026, 10, 29), allDay = false)

    private val claimed = mutableSetOf<String>()
    private val posted = mutableListOf<String>()
    private suspend fun claim(key: String) = claimed.add(key)
    /** Posting reads the preferences first: a suspension a cancel could land in. */
    private suspend fun post(r: Reminder) { delay(1_000); posted += r.key }

    @Test fun `every due reminder is claimed and posted once`() = runTest {
        claimed += "b" // posted by an earlier run
        ReminderScheduler.claimAndPost(listOf(r("a"), r("b"), r("c")), ::claim, ::post)
        assertEquals(listOf("a", "c"), posted)
    }

    @Test fun `a run cancelled while posting still posts what it claimed, and leaves the rest for the next run`() = runTest {
        val job = launch { ReminderScheduler.claimAndPost(listOf(r("a"), r("b")), ::claim, ::post) }
        advanceTimeBy(500)
        runCurrent()
        // A newer request, or the receiver's time running out, cancels the run in the middle of posting "a".
        job.cancel()
        job.join()
        assertEquals(listOf("a"), posted)
        assertEquals(setOf("a"), claimed)
        // The next run posts "b"; "a" is not posted twice.
        ReminderScheduler.claimAndPost(listOf(r("a"), r("b")), ::claim, ::post)
        assertEquals(listOf("a", "b"), posted)
    }

    @Test fun `a receiver's time running out mid-post still posts`() = runTest {
        val done = withTimeoutOrNull(500) { ReminderScheduler.claimAndPost(listOf(r("a")), ::claim, ::post) }
        assertNull(done)
        assertEquals(listOf("a"), posted)
    }
}
