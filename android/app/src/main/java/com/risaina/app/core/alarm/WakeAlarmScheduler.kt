package com.risaina.app.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The broadcasts of Wake together. Each alarm has its own FIRE, SNOOZE_FIRE and TIMEOUT, told apart
 * by the data URI risaina://wake/<alarm id> (an Intent's identity ignores extras), so one alarm
 * never replaces another's, and none can clash with the SOS retry (request code 9120, its own intent).
 */
object WakeActions {
    const val FIRE = "com.risaina.app.wake.FIRE"
    const val SNOOZE_FIRE = "com.risaina.app.wake.SNOOZE_FIRE"
    const val TIMEOUT = "com.risaina.app.wake.TIMEOUT"
    /** From the ring notification, its swipe, the snoozed notice and the alarm screen. */
    const val SNOOZE = "com.risaina.app.wake.SNOOZE"
    const val UP = "com.risaina.app.wake.UP"
    /** "Not on my phone" on the partner's notice. */
    const val NOT_ON_MY_PHONE = "com.risaina.app.wake.NOT_ON_MY_PHONE"
    const val EXTRA_OCCURRENCE = "occurrence"
    const val EXTRA_STARTED_AT = "startedAt"

    fun uri(alarmId: String): Uri = Uri.parse("risaina://wake/$alarmId")
}

/** One AlarmManager alarm: what it does, for which alarm and ring. */
data class WakeTarget(val action: String, val alarmId: String, val occurrence: Long = 0, val startedAt: Long = 0)

enum class WakeAlarmKind {
    /** setAlarmClock: exact in Doze, shown as the phone's next alarm. */
    ALARM_CLOCK,
    /** setExactAndAllowWhileIdle: exact, not shown (the ring's timeout). */
    EXACT,
}

/** AlarmManager, behind an interface so the arming is unit-tested (WakeAlarmSchedulerTest). */
interface WakeAlarmClock {
    fun canExact(): Boolean
    /** Without the exact-alarm permission it falls back to setAndAllowWhileIdle, which can be minutes late. */
    fun set(target: WakeTarget, atMillis: Long, kind: WakeAlarmKind)
    fun cancel(action: String, alarmId: String)
}

/**
 * Sets this phone's next ring of every alarm, and nothing else: only the next occurrence is ever
 * set, recomputed after each ring, on every change, app start, sync, boot, time or zone change and
 * when "Alarms & reminders" is granted. No network and no database, so the receivers can call it.
 */
@Singleton
class WakeAlarmScheduler @Inject constructor(
    private val store: WakeAlarmStore,
    private val clock: WakeAlarmClock,
) {
    /** Rings that were due and never came ([ArmPlan.ringNow]), and ones too late to ring ([ArmPlan.missed]). */
    data class ArmResult(val ringNow: List<Pair<String, Instant>> = emptyList(), val missed: List<Pair<String, Instant>> = emptyList())

    fun canExact(): Boolean = clock.canExact()

    fun arm(now: Instant): ArmResult = synchronized(store.lock) {
        val s = store.current()
        val armed = HashMap<String, Long>()
        val handled = HashMap(s.handled)
        val ringNow = mutableListOf<Pair<String, Instant>>()
        val missed = mutableListOf<Pair<String, Instant>>()
        for (alarm in s.domain()) {
            val member = s.me?.let(alarm::member)
            val plan = WakeAlarmRules.plan(alarm, member, now, s.armed[alarm.id]?.let(Instant::ofEpochMilli), s.handled[alarm.id]?.let(Instant::ofEpochMilli))
            plan.ringNow?.let { ringNow += alarm.id to it }
            plan.missed?.let { missed += alarm.id to it; handled[alarm.id] = it.toEpochMilli() }
            val next = plan.next
            if (next != null) {
                clock.set(WakeTarget(WakeActions.FIRE, alarm.id, next.toEpochMilli()), next.toEpochMilli(), WakeAlarmKind.ALARM_CLOCK)
                armed[alarm.id] = next.toEpochMilli()
            } else {
                clock.cancel(WakeActions.FIRE, alarm.id)
            }
        }
        // Deleted alarms, or ones this phone no longer knows: their set ring goes too.
        (s.armed.keys - armed.keys).forEach { clock.cancel(WakeActions.FIRE, it) }
        store.update { it.copy(armed = armed, handled = handled) }
        ArmResult(ringNow, missed)
    }

    fun setSnooze(alarmId: String, occurrence: Long, atMillis: Long) =
        clock.set(WakeTarget(WakeActions.SNOOZE_FIRE, alarmId, occurrence), atMillis, WakeAlarmKind.ALARM_CLOCK)

    fun setTimeout(alarmId: String, occurrence: Long, startedAt: Long, atMillis: Long) =
        clock.set(WakeTarget(WakeActions.TIMEOUT, alarmId, occurrence, startedAt), atMillis, WakeAlarmKind.EXACT)

    fun cancelTimeout(alarmId: String) = clock.cancel(WakeActions.TIMEOUT, alarmId)

    /** The snooze and the timeout of a ring that is over. */
    fun cancelRing(alarmId: String) {
        clock.cancel(WakeActions.SNOOZE_FIRE, alarmId)
        clock.cancel(WakeActions.TIMEOUT, alarmId)
    }

    /** "Test on this phone": the real path (alarm clock, receiver, full-screen ring) in [WakeAlarmRules.TEST_DELAY_MS]. */
    fun setTest(atMillis: Long) =
        clock.set(WakeTarget(WakeActions.FIRE, WakeAlarmRules.TEST_ID, atMillis), atMillis, WakeAlarmKind.ALARM_CLOCK)

    /** Sign-out or unlink: every alarm this phone set, for every alarm it knows and the test. */
    fun cancelAll() = synchronized(store.lock) {
        val s = store.current()
        val ids = s.armed.keys + s.alarms.map { it.id } + listOfNotNull(s.ring?.alarmId) + WakeAlarmRules.TEST_ID
        for (id in ids) {
            clock.cancel(WakeActions.FIRE, id)
            cancelRing(id)
        }
    }

    companion object {
        /**
         * What this phone tells the server about each alarm: its readiness, and the ring it has set.
         * A mode this build cannot ring is reported as not ready. Pure (WakeAlarmSchedulerTest).
         */
        fun reportItems(local: WakeLocal, device: WakeReadyReport): Map<String, ReportedItem> =
            local.domain().associate { a ->
                a.id to if (!WakeAlarmRules.canSchedule(a)) ReportedItem(false, WakeReadiness.UPDATE_NEEDED, null)
                else ReportedItem(device.ready, device.reason, local.armed[a.id])
            }
    }
}

/** [WakeAlarmClock] on AlarmManager. */
@Singleton
class AndroidWakeAlarmClock @Inject constructor(@ApplicationContext private val context: Context) : WakeAlarmClock {
    private val am get() = context.getSystemService(AlarmManager::class.java)

    override fun canExact(): Boolean = WakeReadinessProbe.canExact(context)

    override fun set(target: WakeTarget, atMillis: Long, kind: WakeAlarmKind) {
        val op = PendingIntent.getBroadcast(context, 0, intent(target.action, target.alarmId).apply {
            putExtra(WakeActions.EXTRA_OCCURRENCE, target.occurrence)
            putExtra(WakeActions.EXTRA_STARTED_AT, target.startedAt)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            when {
                !canExact() -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
                kind == WakeAlarmKind.ALARM_CLOCK -> am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showWakeScreen()), op)
                else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
            }
        } catch (e: SecurityException) {
            // "Alarms & reminders" was revoked between the check and the call.
            Log.w(TAG, "Exact alarm refused, setting an inexact one: ${e.javaClass.simpleName}")
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op) }
        }
    }

    override fun cancel(action: String, alarmId: String) {
        val op = PendingIntent.getBroadcast(context, 0, intent(action, alarmId), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        am.cancel(op)
        op.cancel()
    }

    private fun intent(action: String, alarmId: String): Intent =
        Intent(context, WakeAlarmReceiver::class.java).setAction(action).setData(WakeActions.uri(alarmId))

    /** Tapping the phone's "next alarm" (status bar, quick settings) opens the Wake screen. */
    private fun showWakeScreen(): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_SHOW,
        Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/wake")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "RisainaWake"
        const val REQUEST_SHOW = 9330
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WakeAlarmModule {
    @Binds abstract fun alarmClock(impl: AndroidWakeAlarmClock): WakeAlarmClock
}
