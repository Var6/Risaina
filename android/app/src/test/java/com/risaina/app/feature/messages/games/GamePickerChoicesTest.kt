package com.risaina.app.feature.messages.games

import androidx.compose.runtime.saveable.SaverScope
import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.data.GameStartOptions
import com.risaina.app.core.network.GameKindDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** The picker's last choices outlive the sheet: they must survive saving, and map to the right request. */
class GamePickerChoicesTest {
    private val trivia = GameKindDto(GameKinds.TRIVIA, competitive = true)
    private val thisOrThat = GameKindDto(GameKinds.THIS_OR_THAT, competitive = false)

    @Test fun `choices survive being saved and restored`() {
        val choices = GamePickerChoices(kind = GameKinds.TRIVIA, withPrize = false, category = 23, difficulty = "hard", deck = "us")
        val saved = with(GamePickerChoices.Saver) { SaverScope { true }.save(choices) }!!
        assertEquals(choices, GamePickerChoices.Saver.restore(saved))
    }

    @Test fun `trivia, history, hard, just for fun`() {
        val choices = GamePickerChoices(kind = GameKinds.TRIVIA, withPrize = false, category = 23, difficulty = "hard")
        assertEquals(
            GameStartOptions(kind = GameKinds.TRIVIA, justForFun = true, category = 23, difficulty = "hard"),
            choices.startOptions(trivia, legacy = false),
        )
    }

    @Test fun `any category, any difficulty and the mix deck are left out`() {
        assertEquals(GameStartOptions(kind = GameKinds.TRIVIA), GamePickerChoices(kind = GameKinds.TRIVIA).startOptions(trivia, legacy = false))
        assertEquals(GameStartOptions(kind = GameKinds.THIS_OR_THAT), GamePickerChoices(kind = GameKinds.THIS_OR_THAT).startOptions(thisOrThat, legacy = false))
    }

    @Test fun `no prize switch where there is no winner, nor on an older server`() {
        val off = GamePickerChoices(withPrize = false)
        assertEquals(false, off.startOptions(thisOrThat, legacy = false).justForFun)
        assertEquals(false, off.startOptions(GameKindDto(GameKinds.RPS), legacy = true).justForFun)
    }
}
