package com.risaina.app.core.safety

import android.content.Context
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.location.LocationClient
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.notifications.Haptics
import com.risaina.app.core.sos.SosRetryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends an SOS or a panic alert and remembers how it went. It runs in the app's own scope, not a
 * screen's: closing the screen, or the screen being recreated, must never cancel an emergency.
 *
 * Panic order: live location starts at once (the GPS warms up while the alert is on its way, and the
 * server accepts panic sharing before the SOS exists), the SOS goes out at the same time with an
 * idempotency key, and the two are linked once the SOS id is known. If the SOS cannot be sent, the
 * phone keeps trying in the background (WorkManager, 30 minutes) and the screen offers to share a
 * maps link through SMS or WhatsApp instead.
 */
@Singleton
class SosSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sosRepository: SosRepository,
    private val liveController: LiveLocationController,
    private val locationClient: LocationClient,
    private val haptics: Haptics,
) {
    sealed interface Attempt {
        data object Idle : Attempt
        data class Sending(val panic: Boolean) : Attempt
        data class Sent(val sosId: String, val panic: Boolean, val sharing: Boolean) : Attempt
        data class NotSent(val reason: String, val shareText: String, val retrying: Boolean, val sharing: Boolean, val panic: Boolean) : Attempt
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _attempt = MutableStateFlow<Attempt>(Attempt.Idle)
    val attempt: StateFlow<Attempt> = _attempt

    /**
     * Call from a visible screen only: location sharing may start only while Risaina is on screen.
     * A panic alert without a message says "I'm in trouble".
     */
    fun send(message: String?, panic: Boolean, shareLocation: Boolean) {
        if (_attempt.value is Attempt.Sending) return
        _attempt.value = Attempt.Sending(panic)
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: if (panic) DEFAULT_PANIC_MESSAGE else null
        scope.launch {
            val key = UUID.randomUUID().toString()
            val createdAt = System.currentTimeMillis()
            val sharing = if (shareLocation) {
                async { liveController.start(panic = true, minutes = LiveLocationRules.PANIC_MINUTES) is LiveLocationController.StartResult.Started }
            } else null
            // 75 s covers a server waking up from sleep; the HTTP timeout usually answers sooner.
            val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { sosRepository.send(text, panic, key) } ?: AppResult.Failure(AppError.Timeout())
            val shares = sharing?.await() == true
            when (result) {
                is AppResult.Success -> {
                    haptics.confirm()
                    if (shares) liveController.linkSos(result.value.id)
                    _attempt.value = Attempt.Sent(result.value.id, panic, shares)
                }
                is AppResult.Failure -> {
                    // Rate limited means the partner already got several alarms in the last minutes:
                    // ringing them again later would only confuse, so that one is not retried.
                    val retry = result.error.isRetryable && result.error !is AppError.RateLimited
                    if (retry) SosRetryWorker.enqueue(context, text, panic, key, createdAt)
                    haptics.error()
                    val last = runCatching { locationClient.lastLocation() }.getOrNull()
                    _attempt.value = Attempt.NotSent(
                        reason = reasonFor(result.error, retry),
                        shareText = LiveLocationRules.troubleText(text, last?.latitude, last?.longitude),
                        retrying = retry, sharing = shares, panic = panic,
                    )
                }
            }
        }
    }

    /** Back to the form (after the result was read). */
    fun reset() {
        if (_attempt.value !is Attempt.Sending) _attempt.value = Attempt.Idle
    }

    /**
     * "I'm safe now": stops the partner's alarm, and stops the location shared for an emergency. The
     * sharing stops on this phone even if the server cannot be reached (stopping is the safe side).
     */
    suspend fun safeNow(sosId: String): AppResult<SosDto> {
        val plan = liveController.currentPlan()
        val r = sosRepository.resolve(sosId)
        if (plan != null && (plan.panic || plan.sosId == sosId)) liveController.stop("stopped")
        return r
    }

    companion object {
        const val DEFAULT_PANIC_MESSAGE = "I'm in trouble"
        const val SEND_TIMEOUT_MS = 75_000L

        fun reasonFor(e: AppError, retrying: Boolean): String {
            val base = when (e) {
                is AppError.Offline -> "No internet. Your alert couldn't be sent yet."
                is AppError.Timeout, is AppError.Server -> "The server didn't answer. Your alert couldn't be sent yet."
                is AppError.RateLimited -> "You've sent several SOS alerts in the last few minutes, so this one wasn't sent."
                is AppError.Unauthorized -> "You're signed out. Your alert couldn't be sent."
                else -> e.message ?: "Your alert couldn't be sent."
            }
            return if (retrying) "$base Risaina keeps trying for 30 minutes." else base
        }
    }
}
