package com.risaina.app.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameNotificationTextTest {
    private val kinds = listOf("rps", "tictactoe", "trivia", "this_or_that")

    @Test fun `silent actions post nothing, for every kind`() {
        for (kind in kinds) for (action in listOf("answered", "cancelled", "ended")) {
            assertNull("$kind/$action", GameNotificationText.body(kind, action))
        }
    }

    @Test fun `created, per kind`() {
        assertEquals("challenged you to Stone Paper Scissors Thread ✊✋✌️🧵", GameNotificationText.body("rps", "created"))
        assertEquals("challenged you to tic-tac-toe ❌⭕ You go first", GameNotificationText.body("tictactoe", "created"))
        assertEquals("started a trivia duel: 5 questions on History 🧠", GameNotificationText.body("trivia", "created", "History"))
        assertEquals("started a trivia duel: 5 questions 🧠", GameNotificationText.body("trivia", "created", null))
        assertEquals("started This or That 💞 7 quick picks, answered blind", GameNotificationText.body("this_or_that", "created"))
        assertNotNull(GameNotificationText.body("chess", "created"))
    }

    @Test fun `turns and results`() {
        assertEquals("made their move. Your turn!", GameNotificationText.body("rps", "moved"))
        assertEquals("played. Your turn ❌⭕", GameNotificationText.body("tictactoe", "moved"))
        assertEquals("finished their 5 questions. Your turn 🧠", GameNotificationText.body("trivia", "finished"))
        assertEquals("made all 7 picks. Your turn 💞", GameNotificationText.body("this_or_that", "finished"))
        assertEquals("got three in a row 🏆 Open the chat to see", GameNotificationText.body("tictactoe", "revealed"))
        assertEquals("finished too. The scores are in 🧠", GameNotificationText.body("trivia", "revealed"))
        assertEquals("finished too. It's a tie 🤝 Rematch?", GameNotificationText.body("trivia", "draw"))
        assertEquals("finished too. See how in sync you are 💞", GameNotificationText.body("this_or_that", "matched"))
        assertEquals("wants a rematch: 5 new questions 🧠", GameNotificationText.body("trivia", "rematch"))
        assertEquals("wants a rematch", GameNotificationText.body("tictactoe", "rematch"))
    }

    @Test fun `the truth or dare texts are the same for every competitive kind`() {
        for (kind in listOf("rps", "tictactoe", "trivia")) {
            assertEquals("sealed a truth and a dare for you 😈 Pick one", GameNotificationText.body(kind, "challenged"))
            assertEquals("says it's done. Confirm it?", GameNotificationText.body(kind, "done"))
            assertEquals("confirmed it ✓ You're off the hook", GameNotificationText.body(kind, "confirmed"))
            assertEquals("says not quite. Try again 😄", GameNotificationText.body(kind, "rejected"))
        }
    }

    @Test fun `every text reads after a first name, with no em-dashes`() {
        val actions = listOf("created", "moved", "finished", "revealed", "draw", "matched", "rematch", "challenged", "chosen", "done", "confirmed", "rejected")
        for (kind in kinds) for (action in actions) {
            val text = GameNotificationText.body(kind, action) ?: continue
            assertFalse("$kind/$action", text.contains('—'))
            assertTrue("$kind/$action starts lowercase so 'Bea …' reads well", text.first().isLowerCase())
        }
    }

    @Test fun `stale - a closed game drops 'your turn' texts but keeps final news`() {
        assertTrue(GameNotificationText.isStale("moved", "cancelled"))
        assertTrue(GameNotificationText.isStale("created", "cancelled"))
        assertFalse(GameNotificationText.isStale("cancelled", "cancelled"))
        assertTrue(GameNotificationText.isStale("finished", "completed")) // I finished too in the meantime
        assertTrue(GameNotificationText.isStale("draw", "completed")) // the draw was called off
        assertFalse(GameNotificationText.isStale("confirmed", "completed"))
        assertFalse(GameNotificationText.isStale("revealed", "completed")) // a "just for fun" win
        assertFalse(GameNotificationText.isStale("matched", "completed"))
        assertFalse(GameNotificationText.isStale("moved", "waiting"))
        assertFalse(GameNotificationText.isStale("moved", null))
    }

    @Test fun `quick moves update without ringing again`() {
        assertTrue(GameNotificationText.alertOnce("moved"))
        assertFalse(GameNotificationText.alertOnce("created"))
        assertFalse(GameNotificationText.alertOnce("finished"))
    }

    // --- the same game twice in a few seconds ---------------------------------------------------

    private val now = 1_000_000L

    @Test fun `a second action on the same game within 30 s while showing alerts once`() {
        // "chosen" then "done" 10 s apart: one buzz, the text updates in place.
        assertTrue(GameNotificationText.alertOnce("done", "g1", lastGameId = "g1", lastPostedAt = now - 10_000, showing = true, now = now))
        assertTrue(GameNotificationText.alertOnce("finished", "g1", "g1", now - 29_999, showing = true, now = now))
    }

    @Test fun `another game, a later action or a dismissed notification alerts`() {
        assertFalse(GameNotificationText.alertOnce("created", "g2", lastGameId = "g1", lastPostedAt = now - 5_000, showing = true, now = now))
        assertFalse(GameNotificationText.alertOnce("done", "g1", "g1", now - GameNotificationText.SAME_GAME_WINDOW_MS, showing = true, now = now))
        assertFalse(GameNotificationText.alertOnce("done", "g1", "g1", now - 5_000, showing = false, now = now))
        assertFalse(GameNotificationText.alertOnce("done", "g1", lastGameId = null, lastPostedAt = 0, showing = true, now = now))
        // A clock that jumped back never mutes one.
        assertFalse(GameNotificationText.alertOnce("done", "g1", "g1", now + 60_000, showing = true, now = now))
    }

    @Test fun `tic-tac-toe moves stay quiet whatever came before`() {
        assertTrue(GameNotificationText.alertOnce("moved", "g2", lastGameId = "g1", lastPostedAt = 0, showing = false, now = now))
    }
}
