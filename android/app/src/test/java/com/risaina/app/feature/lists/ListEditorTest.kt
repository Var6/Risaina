package com.risaina.app.feature.lists

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ListsRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.SharedList
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ListEditorTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun list(version: Int, title: String = "Shopping", items: List<ListItem> = emptyList(), by: String = "me") = SharedList(
        id = "L", title = title, kind = ListKind.CHECKLIST, body = "", items = items, pinned = false,
        version = version, createdBy = "me", updatedBy = by, updatedAt = Instant.EPOCH,
    )

    private fun row(key: Long, id: String?, text: String, done: Boolean = false) = EditRow(key, ListItem(id, text, done))

    // ---- what the editor does with a version coming back ------------------------------------------

    @Test fun `own save coming back is recognised although the server trimmed it`() {
        val before = list(1)
        val sent = ListEditorSync.sent(before, "Shopping ", "", listOf(ListItem(null, "Buy ", false), ListItem(null, "  ", false)), false)
        val echo = list(2, title = "Shopping", items = listOf(ListItem("a1", "Buy", false)))
        assertEquals(sent, ListEditorSync.contentOf(echo))
    }

    @Test fun `ids are adopted without touching what was typed`() {
        val rows = listOf(row(1, "x", "Eggs"), row(2, null, "Buy "), row(3, null, "Milk"))
        val server = listOf(ListItem("x", "Eggs", false), ListItem("b", "Buy", false), ListItem("m", "Milk", false))
        val out = ListEditorSync.adoptIds(rows, server)
        assertEquals(listOf(1L, 2L, 3L), out.map { it.key })
        assertEquals(listOf("x", "b", "m"), out.map { it.item.id })
        assertEquals("Buy ", out[1].item.text) // the trailing space survives, so "milk" can follow it
    }

    @Test fun `a row typed further since the save stays new`() {
        val out = ListEditorSync.adoptIds(listOf(row(1, null, "Buy mi")), listOf(ListItem("b", "Buy", false)))
        assertNull(out.single().item.id)
        assertEquals("Buy mi", out.single().item.text)
    }

    @Test fun `duplicates are matched in order`() {
        val out = ListEditorSync.adoptIds(listOf(row(1, null, "Milk"), row(2, null, "Milk")), listOf(ListItem("m1", "Milk", false), ListItem("m2", "Milk", false)))
        assertEquals(listOf("m1", "m2"), out.map { it.item.id })
    }

    @Test fun `a refill keeps the key of rows still there, so the one being typed in keeps focus`() {
        var next = 10L
        val rows = listOf(row(1, "a", "Eggs"), row(2, "b", "Milk"), row(3, null, "draft"))
        val server = listOf(ListItem("b", "Oat milk", false), ListItem("c", "Bread", false))
        val out = ListEditorSync.reseed(rows, server) { next++ }
        assertEquals(listOf(2L, 10L), out.map { it.key })
        assertEquals(listOf("Oat milk", "Bread"), out.map { it.item.text })
    }

    @Test fun `the version chain follows only this phone's own saves`() {
        val chain = SaveChain()
        assertEquals(1, chain.expected("L", 1))
        chain.record("L", 1, 2)
        chain.record("L", 2, 3)
        assertEquals(3, chain.expected("L", 1))
        assertEquals(3, chain.expected("L", 2))
        assertEquals(5, chain.expected("L", 5)) // a version someone else made: no shortcut
        assertEquals(1, chain.expected("other", 1))
    }

    // ---- the view model's saves ----------------------------------------------------------------

    private fun viewModel(repo: ListsRepository): ListsViewModel {
        every { repo.all } returns flowOf(emptyList())
        coEvery { repo.refresh() } returns AppResult.Success(Unit)
        val session = mockk<SessionStore> { every { session } returns flowOf(null) }
        val couple = mockk<CoupleRepository> { every { partner } returns flowOf(null) }
        return ListsViewModel(repo, session, couple)
    }

    @Test fun `typing while a save is on its way never cancels it, and the next save builds on it`() = runTest(dispatcher) {
        val repo = mockk<ListsRepository>()
        val vm = viewModel(repo)
        val sent = mutableListOf<Pair<Int, String>>()
        val firstReply = CompletableDeferred<Unit>()
        coEvery { repo.save(any(), any(), any(), any(), any()) } coAnswers {
            val l = firstArg<SharedList>()
            sent += l.version to secondArg<String>()
            if (sent.size == 1) firstReply.await()
            AppResult.Success(l.copy(version = l.version + 1))
        }
        val v1 = list(1)
        vm.schedule(v1, "Sh", "", emptyList(), false)
        advanceTimeBy(701); runCurrent()
        assertEquals(listOf(1 to "Sh"), sent) // on its way, the reply not back yet

        // Still typing; the editor has not seen version 2 yet, so it still passes version 1.
        vm.schedule(v1, "Shop", "", emptyList(), false)
        advanceTimeBy(701); runCurrent()
        assertEquals(1, sent.size) // waits for the first instead of cancelling it

        firstReply.complete(Unit)
        runCurrent()
        assertEquals(listOf(1 to "Sh", 2 to "Shop"), sent)
    }

    @Test fun `on a slow server both echoes are recognised as own saves, in order`() = runTest(dispatcher) {
        val repo = mockk<ListsRepository>()
        val vm = viewModel(repo)
        val firstReply = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { repo.save(any(), any(), any(), any(), any()) } coAnswers {
            calls++
            if (calls == 1) firstReply.await()
            val l = firstArg<SharedList>()
            AppResult.Success(l.copy(version = l.version + 1))
        }
        val v1 = list(1)
        vm.schedule(v1, "Sh", "", emptyList(), false)
        advanceTimeBy(701); runCurrent()
        vm.schedule(v1, "Shop", "", emptyList(), false)
        advanceTimeBy(701); runCurrent()
        firstReply.complete(Unit)
        runCurrent()
        // Save 2 went out before save 1's echo reached the editor: that echo is still ours.
        assertTrue(vm.takeOwnEcho(list(2, title = "Sh")))
        assertTrue(vm.takeOwnEcho(list(3, title = "Shop")))
        assertFalse(vm.takeOwnEcho(list(4, title = "Shopping", by = "her")))
    }

    @Test fun `own echo is recognised once, a partner's change never`() = runTest(dispatcher) {
        val repo = mockk<ListsRepository>()
        val vm = viewModel(repo)
        coEvery { repo.save(any(), any(), any(), any(), any()) } answers { AppResult.Success(firstArg<SharedList>().copy(version = 2)) }
        vm.schedule(list(1), "Shopping", "", listOf(ListItem(null, "Buy ", false)), false, immediate = true)
        runCurrent()
        val echo = list(2, items = listOf(ListItem("b", "Buy", false)))
        assertTrue(vm.takeOwnEcho(echo))
        assertFalse(vm.takeOwnEcho(echo)) // asked once per version
        assertFalse(vm.takeOwnEcho(list(3, items = listOf(ListItem("b", "Buy oat milk", false)), by = "her")))
    }
}
