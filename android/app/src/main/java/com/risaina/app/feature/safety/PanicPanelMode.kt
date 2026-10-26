package com.risaina.app.feature.safety

import com.risaina.app.core.network.SosDto
import com.risaina.app.core.safety.SosSender
import com.risaina.app.core.sos.SosSenderStatus
import java.time.Instant

/**
 * What the panic panel shows for the send attempt. The attempt lives in an app-wide singleton and
 * outlives the screen that made it, so an alert sent hours ago must never take the place of the
 * hold button: a sent alert takes over the panel only while "I'm safe now" is still on offer.
 */
internal sealed interface PanicPanelMode {
    /** The alert just sent, still unresolved and less than 2 hours old. */
    data class Sent(val sos: SosDto) : PanicPanelMode

    /** It could not be sent: share by SMS, call, or go back to the form. */
    data class NotSent(val attempt: SosSender.Attempt.NotSent) : PanicPanelMode

    /** The hold button, disabled while an alert is on its way. */
    data class Form(val sending: Boolean) : PanicPanelMode

    companion object {
        fun of(attempt: SosSender.Attempt, sos: List<SosDto>, now: Instant): PanicPanelMode = when (attempt) {
            is SosSender.Attempt.Sent -> sos.firstOrNull { it.id == attempt.sosId }
                ?.takeIf { SosSenderStatus.canSaySafe(it.createdAt, it.resolvedAt, now) }
                ?.let(::Sent)
                // Resolved, older than 2 hours or no longer listed (signed out): the form, which
                // still shows any SOS of mine that is active above the hold button.
                ?: Form(sending = false)
            is SosSender.Attempt.NotSent -> NotSent(attempt)
            is SosSender.Attempt.Sending -> Form(sending = true)
            SosSender.Attempt.Idle -> Form(sending = false)
        }
    }
}
