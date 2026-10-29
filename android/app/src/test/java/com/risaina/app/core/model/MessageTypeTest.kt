package com.risaina.app.core.model

import com.risaina.app.core.data.toDomain
import com.risaina.app.core.database.MessageEntity
import com.risaina.app.core.domain.MessageRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message type this build does not know (sent by a newer app) used to throw in fromWire, which
 * stopped the chat, Home and the sync. It now reads as UNKNOWN and shows its text.
 */
class MessageTypeTest {
    private fun row(type: String, text: String?) = MessageEntity(
        id = "m", localKey = "k", coupleId = "c", senderId = "bea", recipientId = "me", type = type, text = text, mediaId = null,
        createdAt = 0, editedAt = null, deletedAt = null, readAt = null, sendState = "SENT", localMediaPath = null,
    )

    @Test fun `known types map as before, anything else is UNKNOWN`() {
        assertEquals(MessageType.TEXT, MessageType.fromWire("text"))
        assertEquals(MessageType.VOICE, MessageType.fromWire("voice"))
        assertEquals(MessageType.UNKNOWN, MessageType.fromWire("card"))
        assertEquals(MessageType.UNKNOWN, MessageType.fromWire(""))
    }

    @Test fun `an unknown type shows its text, or a hint to update when it has none`() {
        val withText = row("plan", "📅 Dinner · Sat 8 pm").toDomain()
        assertEquals(MessageType.UNKNOWN, withText.type)
        assertEquals("📅 Dinner · Sat 8 pm", withText.unknownTypeText())
        assertEquals(UNKNOWN_MESSAGE_TEXT, row("sticker", null).toDomain().unknownTypeText())
        assertEquals(UNKNOWN_MESSAGE_TEXT, row("sticker", "  ").toDomain().unknownTypeText())
    }

    @Test fun `this build never sends an unknown type`() {
        assertTrue(MessageRules.validate(MessageType.UNKNOWN, "hi", null) is MessageRules.Validation.Invalid)
    }
}
