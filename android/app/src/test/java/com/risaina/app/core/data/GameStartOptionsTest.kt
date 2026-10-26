package com.risaina.app.core.data

import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.QuizCategoryDto
import com.risaina.app.core.network.QuizDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Play again" must rebuild a body the server accepts: this one failed with 400 in the first design. */
class GameStartOptionsTest {
    private fun game(kind: String, prize: String, quiz: QuizDto? = null) = GameDto(
        id = "g", coupleId = "c", kind = kind, createdBy = "a", createdAt = "2026-10-26T10:00:00Z",
        updatedAt = "2026-10-26T10:00:00Z", status = "completed", prize = prize, quiz = quiz,
    )

    @Test fun `This or That reports prize none, which is not "just for fun" and is never sent back`() {
        val opts = GameStartOptions.from(game(GameKinds.THIS_OR_THAT, "none", QuizDto(source = "curated", deck = "us")))
        assertFalse(opts.justForFun)
        assertEquals("us", opts.deck)
        val req = opts.toRequest()
        assertNull(req.prize)
        assertEquals("us", req.deck)
        assertNull(req.category)
    }

    @Test fun `the default prize maps to no prize field at all`() {
        val req = GameStartOptions.from(game(GameKinds.TICTACTOE, "truth_or_dare")).toRequest()
        assertNull(req.prize)
        assertEquals(GameKinds.TICTACTOE, req.kind)
    }

    @Test fun `just for fun and the trivia options carry over`() {
        val opts = GameStartOptions.from(
            game(GameKinds.TRIVIA, "none", QuizDto(source = "opentdb", category = QuizCategoryDto(23, "History"), requestedCategoryId = 23, difficulty = "hard", deck = "ignored")),
        )
        assertTrue(opts.justForFun)
        val req = opts.toRequest()
        assertEquals("none", req.prize)
        assertEquals(23, req.category)
        assertEquals("hard", req.difficulty)
        assertNull(req.deck)
    }

    @Test fun `any category stays any, not the category it was served from`() {
        val opts = GameStartOptions.from(
            game(GameKinds.TRIVIA, "truth_or_dare", QuizDto(source = "opentdb", category = QuizCategoryDto(27, "Animals"), requestedCategoryId = null)),
        )
        assertNull(opts.category)
        assertNull(opts.toRequest().category)
    }

    @Test fun `rps keeps working against older servers`() {
        val req = GameStartOptions().toRequest()
        assertEquals("rps", req.kind)
        assertNull(req.prize); assertNull(req.category); assertNull(req.difficulty); assertNull(req.deck)
    }
}
