package com.risaina.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.risaina.app.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real activity with the Hilt graph. On a fresh install the Welcome screen shows,
 * sign-up validation blocks bad input before any network call, and the error is announced in text.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AuthFlowTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { hilt.inject() }

    @Test
    fun freshInstall_showsWelcome_thenSignUpValidation() {
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Get started")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("Create your account").assertIsDisplayed()
        rule.onNodeWithText("Your name").performTextInput("A")
        rule.onNodeWithText("Email").performTextInput("not-an-email")
        rule.onNodeWithText("Password").performTextInput("short")
        rule.onNodeWithText("Create account").performClick()
        rule.onNodeWithText("Tell us what to call you").assertIsDisplayed()
        rule.onNodeWithText("Enter a valid email").assertIsDisplayed()
        rule.onNodeWithText("Use at least 10 characters").assertIsDisplayed()
    }
}
