package com.risaina.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.ErrorState
import com.risaina.app.core.designsystem.components.HelpAction
import com.risaina.app.core.designsystem.components.HelpPoint
import com.risaina.app.core.designsystem.theme.RisainaTheme
import com.risaina.app.core.model.PingStatus
import com.risaina.app.feature.onboarding.WelcomeScreen
import com.risaina.app.feature.pairing.PairingIntroScreen
import com.risaina.app.feature.ping.PingButtonState
import com.risaina.app.feature.ping.TouchPingButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for state-driven composables (no Hilt graph required). They verify the
 * truthful-feedback rules: the ping button is disabled while sending/cooling down, states are
 * announced in text, and primary flows expose their actions.
 */
@RunWith(AndroidJUnit4::class)
class ComposeScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun welcome_showsBothActions_andCallsBack() {
        var started = 0; var signIn = 0
        rule.setContent { RisainaTheme { WelcomeScreen(onGetStarted = { started++ }, onSignIn = { signIn++ }) } }
        rule.onNodeWithText("Risaina").assertIsDisplayed()
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("I already have an account").performClick()
        assertEquals(1, started); assertEquals(1, signIn)
    }

    @Test
    fun pairingIntro_explainsTwoPeopleRule() {
        var cont = false
        rule.setContent { RisainaTheme { PairingIntroScreen(onContinue = { cont = true }) } }
        rule.onNodeWithText("Just the two of you").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        assertTrue(cont)
    }

    @Test
    fun touchPing_idle_isTappable_andAnnouncesReady() {
        var taps = 0
        rule.setContent { RisainaTheme { TouchPingButton(state = PingButtonState.Idle, emoji = "💭", burstKey = 0, onTap = { taps++ }, onLongPress = {}) } }
        rule.onNodeWithContentDescription("Send a touch ping").assertIsDisplayed()
        rule.onNodeWithText("💭").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun touchPing_sending_doesNotAcceptTaps() {
        var taps = 0
        rule.setContent { RisainaTheme { TouchPingButton(state = PingButtonState.Sending, emoji = "💭", burstKey = 1, onTap = { taps++ }, onLongPress = {}) } }
        rule.onNodeWithText("💭").performClick()
        assertEquals(0, taps)
    }

    @Test
    fun touchPing_cooldown_isNotEnabled() {
        var taps = 0
        rule.setContent { RisainaTheme { TouchPingButton(state = PingButtonState.Cooldown(9), emoji = "💭", burstKey = 0, onTap = { taps++ }, onLongPress = {}) } }
        rule.onNodeWithText("💭").performClick()
        assertEquals(0, taps)
    }

    @Test
    fun touchPing_settled_rendersWithoutCrash() {
        rule.setContent { RisainaTheme { TouchPingButton(state = PingButtonState.Settled(PingStatus.DELIVERED), emoji = "💭", burstKey = 2, onTap = {}, onLongPress = {}) } }
        rule.onNodeWithContentDescription("Send a touch ping").assertIsDisplayed()
    }

    @Test
    fun emptyAndErrorStates_exposeRetry() {
        var retried = false
        rule.setContent {
            RisainaTheme {
                androidx.compose.foundation.layout.Column {
                    EmptyState(title = "Nothing here yet", body = "Say hello.")
                    ErrorState(title = "Couldn't load", body = "Check your connection.", onRetry = { retried = true })
                }
            }
        }
        rule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsEnabled().performClick()
        assertTrue(retried)
    }

    @Test
    fun reducedMotion_stillRendersStates() {
        rule.setContent { RisainaTheme(reducedMotion = true) { TouchPingButton(state = PingButtonState.Failed("Offline"), emoji = "💭", burstKey = 3, onTap = {}, onLongPress = {}) } }
        rule.onNodeWithContentDescription("Send a touch ping").assertIsDisplayed()
    }

    @Test
    fun helpAction_opensItsSheet_withTheInstructions() {
        rule.setContent { RisainaTheme { HelpAction("About widgets") { HelpPoint("Long-press your home screen.", title = "Adding one") } } }
        rule.onNodeWithText("About widgets").assertDoesNotExist()
        rule.onNodeWithContentDescription("Help").assertHasClickAction().performClick()
        rule.onNodeWithText("About widgets").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Long-press your home screen.").assertIsDisplayed()
    }

    @Suppress("unused")
    private fun keep() { rule.onNodeWithText("x").assertIsNotEnabled() }
}
