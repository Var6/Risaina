package com.risaina.app.core.sos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.risaina.app.core.notifications.SosAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Why the alarm service was asked to ring. */
enum class RingSource { PUSH, EVENT, ALARM_CLOCK, TEST }

/**
 * One request to ring. Every field but the source may be unknown on the push fast path. [test] is
 * its own field, not derived from the source: the alarm-clock retry re-sends a request with source
 * ALARM_CLOCK, and a test that went that way must still ring as a 5-second test, not a real SOS.
 */
data class RingRequest(
    val sosId: String? = null,
    val seq: Long? = null,
    val partnerName: String? = null,
    val message: String? = null,
    val panic: Boolean = false,
    val source: RingSource = RingSource.EVENT,
    val pushPriority: String? = null,
    val pushOriginalPriority: String? = null,
    val latencyMs: Long? = null,
    val test: Boolean = false,
)

/** The alarm that is ringing right now (one at a time; a second SOS joins it). */
data class RingState(
    val sosIds: Set<String>,
    val seqs: Set<Long>,
    val partnerName: String,
    val message: String?,
    val panic: Boolean,
    val test: Boolean,
    val startedAtMs: Long,
    val untilMs: Long,
    /** "Show previews": when false the message stays hidden on a locked phone. */
    val previews: Boolean,
) {
    fun matches(sosId: String?, seq: Long?): Boolean = (sosId != null && sosId in sosIds) || (seq != null && seq in seqs)
}

/**
 * In-process entry point to the SOS alarm. The push handler and the event reducer call [ring]; when
 * the alarm service is already running in this process it is updated directly (calling
 * startForegroundService again from a background sync would be refused). When the system refuses to
 * start the service (the FCM exemption lapsed, or the push was deprioritised), an exact alarm-clock
 * alarm retries the start a second later, which Android allows; only if that is impossible too does
 * it fall back to the notification-only alarm.
 */
@Singleton
class SosAlarmController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localState: SosLocalState,
    private val sosAlert: SosAlert,
) {
    enum class Outcome { STARTED, UPDATED, ALARM_CLOCK, NOTIFICATION, SKIPPED }

    private val _state = MutableStateFlow<RingState?>(null)
    /** Non-null while an SOS (or a test) is ringing. The alarm screen finishes when this turns null. */
    val state: StateFlow<RingState?> = _state

    @Volatile private var service: SosAlarmService? = null

    internal fun attach(s: SosAlarmService) { service = s }
    internal fun detach(s: SosAlarmService) { if (service === s) service = null }
    internal fun publish(s: RingState?) { _state.value = s }

    fun isRinging(sosId: String?, seq: Long?): Boolean = _state.value?.matches(sosId, seq) == true

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The service's ringing state, its MediaPlayer and its timeout live on the main thread; the push
     * handler and the sync call in from worker threads. Every change to a running alarm goes there.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun ring(req: RingRequest): Outcome {
        if (service != null && _state.value != null) {
            // Checked again on the main thread: the alarm may have timed out in between, and then
            // this SOS needs its own start (or the fallback), not a merge into a stopping service.
            onMain {
                val running = service
                if (running != null && _state.value != null) running.merge(req) else start(req)
            }
            return Outcome.UPDATED
        }
        return start(req)
    }

    private fun start(req: RingRequest): Outcome = try {
        ContextCompat.startForegroundService(context, SosAlarmService.ringIntent(context, req))
        Outcome.STARTED
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+), IllegalStateException, SecurityException
        Log.w(TAG, "SOS alarm service start refused: ${e.javaClass.simpleName}")
        fallback(req)
    }

    /** A 5-second test of the whole ring path on this phone (no receipts, nothing sent). */
    fun ringTest(): Outcome = ring(RingRequest(partnerName = "your partner", source = RingSource.TEST, test = true))

    /** The service could not go to the foreground itself: retry through an alarm clock, else notify. */
    internal fun fallback(req: RingRequest): Outcome {
        if (req.source != RingSource.ALARM_CLOCK && scheduleAlarmClock(req)) return Outcome.ALARM_CLOCK
        // A test says it is a test (no insistent "SOS from your partner" for something nobody sent).
        val shown = sosAlert.showFallback(req.partnerName ?: "your partner", req.message, req.panic, previews = false, sosId = req.sosId, test = req.test)
        // Only what was really shown counts as rung: with notifications off nothing was, and then the
        // sender must not read "ringing", the server must keep re-pushing, and a later high-priority
        // re-push (which may start the alarm service) must not be skipped as already handled.
        if (!req.test && shown) {
            localState.markRang(req.sosId, req.seq)
            SosReceiptWorker.enqueueDelivered(context, req, SosDiagnostics.capture(context, fgsStarted = false))
        }
        return if (shown) Outcome.NOTIFICATION else Outcome.SKIPPED
    }

    /**
     * AlarmManager.setAlarmClock may start a foreground service from the background. It needs the
     * exact-alarm permission: SCHEDULE_EXACT_ALARM, which on Android 14+ the person grants in "Alarms &
     * reminders" (the SOS readiness card asks for it).
     */
    private fun scheduleAlarmClock(req: RingRequest): Boolean = try {
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            false
        } else {
            val start = PendingIntent.getForegroundService(
                context, REQUEST_ALARM_CLOCK, SosAlarmService.ringIntent(context, req.copy(source = RingSource.ALARM_CLOCK)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val show = PendingIntent.getActivity(
                context, REQUEST_ALARM_CLOCK, SosAlarmActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 1_000, show), start)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "SOS alarm-clock retry not possible: ${e.javaClass.simpleName}")
        false
    }

    /** "I'm on it" from the alarm screen or the in-app SOS screen. */
    fun acknowledge() = onMain { service?.acknowledge() }

    /**
     * "I'm on it" for one SOS from inside the app. If it is ringing, the alarm acknowledges it (and
     * stops); otherwise it is recorded here and the receipt is sent with retries, so a re-push or a
     * replay never rings it again and the sender learns it was seen even after an offline tap.
     */
    fun acknowledgeById(sosId: String) {
        if (isRinging(sosId, null)) {
            acknowledge()
            return
        }
        localState.markAcked(sosId, null)
        SosReceiptWorker.enqueueAck(context, setOf(sosId), emptySet())
        sosAlert.clearResponded(alarmRinging = _state.value != null)
    }

    /**
     * Acknowledged on another device or by the in-app screen, or the sender is safe: that SOS stops
     * ringing without being acknowledged again. Another SOS ringing with it that is still open keeps
     * ringing.
     */
    fun stopFor(sosId: String?, seq: Long?) = onMain {
        if (isRinging(sosId, seq)) service?.dropHandled(sosId, seq)
    }

    /** Sign-out or unlink: nothing may keep ringing for a partner who is no longer there. */
    fun stopAll() = onMain {
        service?.stopRinging(SosAlarmService.StopReason.HANDLED)
        sosAlert.clear()
    }

    private companion object {
        const val TAG = "RisainaSos"
        const val REQUEST_ALARM_CLOCK = 9120
    }
}
