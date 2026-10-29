package com.risaina.app.feature.messages

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.SharedList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** To-dos from the chat, one at a time, on a server slow to answer (a cold start). */
class TodoQueueTest {
    /** Room and the server in one: a list made by one call is seen by the next. Each call takes a while. */
    private class FakeLists {
        val lists = mutableListOf<SharedList>()
        val appended = mutableListOf<Pair<String, String>>()

        suspend fun ensure(): AppResult<SharedList> {
            delay(20_000) // GET /lists
            lists.firstOrNull()?.let { return AppResult.Success(it) }
            delay(10_000) // POST /lists
            val made = SharedList("l${lists.size + 1}", "To-dos", ListKind.CHECKLIST, "", emptyList(), true, 1, "me", null, Instant.EPOCH)
            lists += made
            return AppResult.Success(made)
        }

        suspend fun append(listId: String, texts: List<String>): AppResult<List<String>> {
            delay(5_000)
            texts.forEach { appended += listId to it }
            return AppResult.Success(texts.map { "i${appended.size}" })
        }
    }

    @Test fun `two to-dos in a row make one list and both go in`() = runTest {
        val fake = FakeLists()
        val queue = TodoQueue(fake::ensure, fake::append)
        val a = async { queue.add("Buy milk") }
        val b = async { queue.add("Call the plumber") }
        assertTrue(a.await() is AppResult.Success)
        assertTrue(b.await() is AppResult.Success)
        assertEquals(1, fake.lists.size)
        assertEquals(listOf("l1" to "Buy milk", "l1" to "Call the plumber"), fake.appended)
    }

    @Test fun `the same words tapped again while on their way go in once`() = runTest {
        val fake = FakeLists()
        val queue = TodoQueue(fake::ensure, fake::append)
        val a = async { queue.add("Buy milk") }
        val again = async { queue.add(" Buy milk ") }
        assertTrue(a.await() is AppResult.Success)
        assertNull(again.await())
        assertEquals(listOf("l1" to "Buy milk"), fake.appended)
        // Once in, the same words can be added again: that is a person's choice, not a double tap.
        assertTrue(queue.add("Buy milk") is AppResult.Success)
        assertEquals(2, fake.appended.size)
    }

    @Test fun `opening the list waits for a to-do on its way and finds the same list`() = runTest {
        val fake = FakeLists()
        val queue = TodoQueue(fake::ensure, fake::append)
        val a = async { queue.add("Buy milk") }
        val open = async { queue.list() }
        a.await()
        assertEquals("l1", (open.await() as AppResult.Success).value.id)
        assertEquals(1, fake.lists.size)
    }

    @Test fun `a failure is passed on and the next to-do still goes`() = runTest {
        var down = true
        val fake = FakeLists()
        val queue = TodoQueue({ if (down) AppResult.Failure(com.risaina.app.core.common.AppError.Offline()) else fake.ensure() }, fake::append)
        assertTrue(queue.add("Buy milk") is AppResult.Failure)
        down = false
        assertTrue(queue.add("Buy milk") is AppResult.Success)
        assertEquals(listOf("l1" to "Buy milk"), fake.appended)
    }
}
