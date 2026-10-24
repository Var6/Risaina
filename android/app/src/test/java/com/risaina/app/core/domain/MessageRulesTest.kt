package com.risaina.app.core.domain

import com.risaina.app.core.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRulesTest {
    @Test fun `text requires content within limit`() {
        assertEquals(MessageRules.Validation.Ok, MessageRules.validate(MessageType.TEXT, "hi", null))
        assertTrue(MessageRules.validate(MessageType.TEXT, "   ", null) is MessageRules.Validation.Invalid)
        assertTrue(MessageRules.validate(MessageType.TEXT, "x".repeat(2001), null) is MessageRules.Validation.Invalid)
    }

    @Test fun `media types require a media id`() {
        assertTrue(MessageRules.validate(MessageType.PHOTO, null, null) is MessageRules.Validation.Invalid)
        assertEquals(MessageRules.Validation.Ok, MessageRules.validate(MessageType.VOICE, null, "abc"))
    }

    @Test fun `voice limits`() {
        assertEquals(MessageRules.Validation.Ok, MessageRules.validateVoice(5_000, 100_000))
        assertTrue(MessageRules.validateVoice(300, 1_000) is MessageRules.Validation.Invalid)
        assertTrue(MessageRules.validateVoice(181_000, 1_000) is MessageRules.Validation.Invalid)
        assertTrue(MessageRules.validateVoice(5_000, 9L * 1024 * 1024) is MessageRules.Validation.Invalid)
    }
}
