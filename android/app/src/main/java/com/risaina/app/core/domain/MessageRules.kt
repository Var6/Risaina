package com.risaina.app.core.domain

import com.risaina.app.core.model.MessageType

object MessageRules {
    const val MAX_TEXT_LENGTH = 2000
    const val MAX_VOICE_MS = 180_000L
    const val MAX_VOICE_BYTES = 8L * 1024 * 1024
    const val MAX_PHOTO_BYTES = 10L * 1024 * 1024

    sealed interface Validation {
        data object Ok : Validation
        data class Invalid(val reason: String) : Validation
    }

    fun validate(type: MessageType, text: String?, mediaId: String?): Validation = when (type) {
        MessageType.TEXT, MessageType.EMOJI -> when {
            text.isNullOrBlank() -> Validation.Invalid("Write something first")
            text.length > MAX_TEXT_LENGTH -> Validation.Invalid("Messages can be up to $MAX_TEXT_LENGTH characters")
            else -> Validation.Ok
        }
        MessageType.PHOTO, MessageType.VOICE -> when {
            mediaId.isNullOrBlank() -> Validation.Invalid("Attachment is still uploading")
            else -> Validation.Ok
        }
        // Only ever received from a newer app; this build has nothing to send it with.
        MessageType.UNKNOWN -> Validation.Invalid("This version of Risaina can't send that")
    }

    fun validateVoice(durationMs: Long, sizeBytes: Long): Validation = when {
        durationMs <= 500 -> Validation.Invalid("That was too short to send")
        durationMs > MAX_VOICE_MS -> Validation.Invalid("Voice notes can be up to 3 minutes")
        sizeBytes > MAX_VOICE_BYTES -> Validation.Invalid("That recording is too large")
        else -> Validation.Ok
    }
}
