package com.risaina.app.core.data

import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameEventViewDto
import com.risaina.app.core.network.QuizDto
import com.risaina.app.core.network.QuizEventDto
import com.risaina.app.core.network.QuizItemDto
import com.risaina.app.core.network.QuizProgressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A partner's single trivia answer is applied from the event instead of a re-fetch (keeps a
 * push-triggered sync short). Anything the event can't settle on its own must return null.
 */
class GamePartnerProgressTest {
    private val me = "me"
    private val partner = "bea"

    private fun local(status: String = "waiting", round: Int = 1, partnerAnswered: Int = 1, kind: String = GameKinds.TRIVIA) = GameDto(
        id = "g", coupleId = "c", kind = kind, createdBy = me, createdAt = "2026-10-26T10:00:00Z",
        updatedAt = "2026-10-26T10:00:00Z", status = status, round = round,
        quiz = QuizDto(
            source = "opentdb", total = 5, myAnswered = 2, partnerAnswered = partnerAnswered, myScore = 1,
            items = listOf(QuizItemDto(index = 0, text = "Q1", choices = listOf("a", "b", "c", "d"), myChoice = 1, correct = 1)),
        ),
    )

    private fun event(answered: Int, finished: Boolean = false, status: String = "waiting", round: Int = 1, kind: String = GameKinds.TRIVIA) = GameEventViewDto(
        id = "g", kind = kind, status = status, round = round, updatedAt = "2026-10-26T10:05:00Z",
        quiz = QuizEventDto(
            total = 5,
            progress = listOf(QuizProgressDto(me, 2, false), QuizProgressDto(partner, answered, finished)),
        ),
    )

    @Test fun `only the partner's count and updatedAt change`() {
        val before = local()
        val after = withPartnerProgress(before, event(answered = 2), partner)!!
        assertEquals(2, after.quiz!!.partnerAnswered)
        assertEquals("2026-10-26T10:05:00Z", after.updatedAt)
        // My answers, score and the questions I can see are untouched.
        assertEquals(before.quiz!!.copy(partnerAnswered = 2), after.quiz)
        assertEquals(before.copy(updatedAt = after.updatedAt, quiz = after.quiz), after)
    }

    @Test fun `an older event never moves progress backwards`() {
        val newer = local(partnerAnswered = 3)
        assertSame(newer, withPartnerProgress(newer, event(answered = 2), partner))
    }

    @Test fun `anything the event can't settle is re-fetched`() {
        assertNull("partner just finished", withPartnerProgress(local(), event(answered = 5, finished = true), partner))
        assertNull("another round", withPartnerProgress(local(round = 2), event(answered = 2), partner))
        assertNull("game moved on", withPartnerProgress(local(status = "draw"), event(answered = 2), partner))
        assertNull("event from a closed game", withPartnerProgress(local(), event(answered = 2, status = "cancelled"), partner))
        assertNull("not a trivia duel", withPartnerProgress(local(kind = GameKinds.THIS_OR_THAT), event(answered = 2, kind = GameKinds.THIS_OR_THAT), partner))
        assertNull("unknown actor", withPartnerProgress(local(), event(answered = 2), "someone-else"))
        assertNull("no quiz yet", withPartnerProgress(local().copy(quiz = null), event(answered = 2), partner))
    }
}
