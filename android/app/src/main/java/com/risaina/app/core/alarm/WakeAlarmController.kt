package com.risaina.app.core.alarm

import android.content.Context
import android.content.Intent
import com.risaina.app.core.common.AppVisibility
import com.risaina.app.core.model.WakeOutcome
import com.risaina.app.core.sos.SosAlarmController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ring on this phone: fire, snooze, "I'm up", the timeout, the partner being up, and the SOS
 * taking over. Every change happens under the store's lock, from the receiver, the alarm screen or
 * the sync, with no network (reports go through WakeReportWorker) and no database.
 *
 * A ring lasts 10 minutes. Nobody answers: it snoozes itself once and rings again; still nobody, it
 * stops, a quiet "missed" notice is posted and the partner is told. Snooze and "I'm up" act on this
 * phone only: the partner's alarm keeps ringing until they answer it themselves.
 */
@Singleton
class WakeAlarmController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: WakeAlarmStore,
    private val scheduler: WakeAlarmScheduler,
    private val notifier: WakeAlarmNotifier,
    private val probe: WakeReadinessProbe,
    private val sos: SosAlarmController,
) {
    /** The ring that is sounding now, for the alarm screen (it finishes when this turns null). */
    val ringing: Flow<WakeRing?> = store.state.map { s -> s.ring?.takeIf { it.phase == WakePhase.RINGING } }.distinctUntilChanged()

    /** One broadcast for WakeAlarmReceiver. */
    fun handle(intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val now = Instant.now()
        when (intent.action) {
            WakeActions.FIRE -> if (id == WakeAlarmRules.TEST_ID) fireTest(now) else fire(id, Instant.ofEpochMilli(intent.getLongExtra(WakeActions.EXTRA_OCCURRENCE, 0)), now)
            WakeActions.SNOOZE_FIRE -> snoozeFire(id, intent.getLongExtra(WakeActions.EXTRA_OCCURRENCE, 0), now)
            WakeActions.SNOOZE -> snooze(id, now)
            WakeActions.UP -> up(id, now)
            WakeActions.TIMEOUT -> timeout(id, intent.getLongExtra(WakeActions.EXTRA_STARTED_AT, 0), now)
            WakeActions.NOT_ON_MY_PHONE -> notOnMyPhone(id, now)
        }
    }

    /** An alarm's set ring arrived (or a lost one is caught up by [rearm]). The next ring is always set afterwards. */
    fun fire(alarmId: String, occurrence: Instant, now: Instant = Instant.now()) {
        var missedLabel: String? = null
        synchronized(store.lock) {
            val s = store.current()
            val alarm = s.alarm(alarmId)
            val member = s.me?.let { alarm?.member(it) }
            val decision = WakeAlarmRules.fireDecision(alarm, member, occurrence, now, s.handled[alarmId]?.let(Instant::ofEpochMilli))
            if (decision == FireDecision.RING || decision == FireDecision.MISSED) {
                store.update { it.copy(handled = it.handled + (alarmId to occurrence.toEpochMilli())) }
            }
            when (decision) {
                FireDecision.RING -> startRing(
                    WakeRing(alarmId, occurrence.toEpochMilli(), label = WakeAlarmRules.label(alarm!!), snoozeMinutes = alarm.snoozeMinutes, startedAt = now.toEpochMilli()),
                    now,
                )
                FireDecision.MISSED -> missedLabel = WakeAlarmRules.label(alarm!!)
                else -> Unit
            }
        }
        missedLabel?.let { label ->
            notifier.postMissed(label, timeText(occurrence), phoneWasOff = true)
            WakeReportWorker.enqueueWake(context, alarmId, occurrence, WakeOutcome.MISSED, now, 0)
        }
        rearm(now)
    }

    /**
     * "Test on this phone": the same ring, labelled a test, with nothing reported. Never while a
     * real ring is going or snoozed (it went off in the 10 seconds): the test would take its place,
     * and stopping the test would end the real ring with nothing set, posted or reported.
     */
    internal fun fireTest(now: Instant) = synchronized(store.lock) {
        if (realRing() != null) return@synchronized
        startRing(WakeRing(WakeAlarmRules.TEST_ID, now.toEpochMilli(), label = "Test alarm", startedAt = now.toEpochMilli(), test = true), now)
    }

    /** Under the lock: a real alarm's ring that is ringing or snoozed on this phone. */
    private fun realRing(): WakeRing? = store.current().ring?.takeIf { it.active && !it.test }

    /** Under the lock. A ring of another alarm still going is replaced: one ring at a time. */
    private fun startRing(ring: WakeRing, now: Instant) {
        store.current().ring?.takeIf { it.active && it.alarmId != ring.alarmId }?.let { scheduler.cancelRing(it.alarmId) }
        if (sos.state.value != null) {
            // An SOS is ringing: it always wins, and this alarm comes back after a snooze.
            store.update { it.copy(ring = ring) }
            snoozeLocked(ring, now, counted = false)
            return
        }
        store.update { it.copy(ring = ring) }
        notifier.cancelStatus()
        showRing(ring)
        val ringMs = if (ring.test) WakeAlarmRules.TEST_RING_MS else WakeAlarmRules.RING_MS
        scheduler.setTimeout(ring.alarmId, ring.occurrence, ring.startedAt, ring.startedAt + ringMs)
        // Risaina is on screen: a full-screen intent only shows a banner on a phone in use.
        if (AppVisibility.isVisible) runCatching { context.startActivity(WakeAlarmActivity.intent(context)) }
    }

    private fun showRing(ring: WakeRing) =
        notifier.postRing(ring, timeText(Instant.ofEpochMilli(ring.occurrence)), if (ring.test) null else partnerLine(ring))

    fun snooze(alarmId: String, now: Instant = Instant.now()) = synchronized(store.lock) {
        val ring = store.current().ring?.takeIf { it.alarmId == alarmId && it.phase == WakePhase.RINGING } ?: return@synchronized
        if (ring.test) finish(ring, WakePhase.UP, now) else snoozeLocked(ring, now, counted = true)
    }

    /** Under the lock. [counted]: the person chose it (reported with "I'm up"); an SOS pause or the auto snooze is not. */
    private fun snoozeLocked(ring: WakeRing, now: Instant, counted: Boolean, auto: Boolean = false) {
        val until = now.toEpochMilli() + ring.snoozeMinutes * 60_000L
        val next = ring.copy(
            phase = WakePhase.SNOOZED, until = until, snoozes = ring.snoozes + if (counted) 1 else 0,
            autoSnoozed = ring.autoSnoozed || auto,
        )
        store.update { it.copy(ring = next) }
        notifier.cancelRing()
        scheduler.cancelTimeout(ring.alarmId)
        scheduler.setSnooze(ring.alarmId, ring.occurrence, until)
        notifier.postSnoozed(next, timeText(Instant.ofEpochMilli(until)), partnerUpLine(next))
    }

    internal fun snoozeFire(alarmId: String, occurrence: Long, now: Instant) = synchronized(store.lock) {
        val s = store.current()
        val ring = s.ring?.takeIf { it.alarmId == alarmId && it.occurrence == occurrence && it.phase == WakePhase.SNOOZED } ?: return@synchronized
        if (!ring.test) {
            val alarm = s.alarm(alarmId)
            // Deleted, turned off, or "not on my phone" while it was snoozed: it ends quietly.
            if (alarm == null || !WakeAlarmRules.ringsFor(alarm, s.me?.let(alarm::member))) {
                finish(ring, WakePhase.MISSED, now, report = false)
                return@synchronized
            }
        }
        if (sos.state.value != null) {
            snoozeLocked(ring, now, counted = false)
            return@synchronized
        }
        val again = ring.copy(phase = WakePhase.RINGING, startedAt = now.toEpochMilli(), until = null)
        store.update { it.copy(ring = again) }
        notifier.cancelStatus()
        showRing(again)
        scheduler.setTimeout(alarmId, occurrence, again.startedAt, again.startedAt + WakeAlarmRules.RING_MS)
        if (AppVisibility.isVisible) runCatching { context.startActivity(WakeAlarmActivity.intent(context)) }
    }

    /** "I'm up": this phone stops, and the partner is told (never silenced). */
    fun up(alarmId: String, now: Instant = Instant.now()) = synchronized(store.lock) {
        val ring = store.current().ring?.takeIf { it.alarmId == alarmId && it.active } ?: return@synchronized
        finish(ring, WakePhase.UP, now)
    }

    internal fun timeout(alarmId: String, startedAt: Long, now: Instant) = synchronized(store.lock) {
        val ring = store.current().ring?.takeIf { it.alarmId == alarmId && it.phase == WakePhase.RINGING && it.startedAt == startedAt } ?: return@synchronized
        when {
            ring.test -> finish(ring, WakePhase.UP, now)
            // Opening the shade stops an insistent sound for good: the automatic snooze rings it again.
            !ring.autoSnoozed -> snoozeLocked(ring, now, counted = false, auto = true)
            else -> {
                finish(ring, WakePhase.MISSED, now)
                notifier.postMissed(ring.label, timeText(Instant.ofEpochMilli(ring.occurrence)), phoneWasOff = false)
            }
        }
    }

    /** Under the lock: the ring is over ([phase] UP or MISSED), and reported unless it was a test. */
    private fun finish(ring: WakeRing, phase: WakePhase, now: Instant, report: Boolean = true) {
        store.update { it.copy(ring = if (ring.test) null else ring.copy(phase = phase, doneAt = now.toEpochMilli(), until = null)) }
        notifier.cancelRing()
        notifier.cancelStatus()
        scheduler.cancelRing(ring.alarmId)
        if (report && !ring.test) {
            WakeReportWorker.enqueueWake(
                context, ring.alarmId, Instant.ofEpochMilli(ring.occurrence),
                if (phase == WakePhase.UP) WakeOutcome.UP else WakeOutcome.MISSED, now, ring.snoozes.coerceAtMost(20),
            )
        }
    }

    /**
     * The partner is up for [occurrence] (alarm.woke). The ringing alarm and the snoozed notice say
     * so; the ring keeps going. Returns true when this person is up already for the same ring, so
     * the caller posts the quiet "is up too".
     */
    fun partnerUp(alarmId: String, occurrence: Instant, at: Instant): Boolean = synchronized(store.lock) {
        val ring = store.current().ring?.takeIf { it.alarmId == alarmId && it.occurrence == occurrence.toEpochMilli() && !it.test }
            ?: return@synchronized false
        val updated = ring.copy(partnerUpAt = at.toEpochMilli())
        store.update { it.copy(ring = updated) }
        when (updated.phase) {
            WakePhase.RINGING -> showRing(updated)
            WakePhase.SNOOZED -> notifier.postSnoozed(updated, timeText(Instant.ofEpochMilli(updated.until ?: 0)), partnerUpLine(updated))
            else -> Unit
        }
        updated.phase == WakePhase.UP
    }

    /** The alarm was deleted: a ring of it ends quietly (nothing to report on a deleted alarm), and so does its notice. */
    fun alarmGone(alarmId: String) = synchronized(store.lock) {
        store.current().ring?.takeIf { it.alarmId == alarmId && it.active }?.let { finish(it, WakePhase.MISSED, Instant.now(), report = false) }
        notifier.cancelSet(alarmId)
    }

    /** SosAlarmService started ringing: a wake alarm sounding now steps aside (snoozed). */
    fun pauseForSos() = synchronized(store.lock) {
        val ring = store.current().ring?.takeIf { it.phase == WakePhase.RINGING } ?: return@synchronized
        if (ring.test) finish(ring, WakePhase.UP, Instant.now()) else snoozeLocked(ring, Instant.now(), counted = false)
    }

    /** "Not on my phone" from the partner's notice: off here at once, and on the server when it is reachable. */
    fun notOnMyPhone(alarmId: String, now: Instant = Instant.now()) {
        synchronized(store.lock) {
            store.setPendingRing(alarmId, false)
            store.current().ring?.takeIf { it.alarmId == alarmId && it.active }?.let { finish(it, WakePhase.MISSED, now, report = false) }
        }
        notifier.cancelSet(alarmId)
        WakeReportWorker.enqueueRing(context, alarmId, false)
        rearm(now)
    }

    /** This person's own switch on the Wake screen: applies here at once, reaches the server when it can. */
    fun setMyRing(alarmId: String, ring: Boolean) {
        if (!ring) return notOnMyPhone(alarmId)
        store.setPendingRing(alarmId, true)
        WakeReportWorker.enqueueRing(context, alarmId, true)
        rearm()
    }

    /**
     * "Test on this phone": rings in 10 seconds through the alarm clock and the full-screen path.
     * Returns the real ring that is ringing or snoozed instead, and sets nothing: the test waits
     * until that ring is over.
     */
    fun scheduleTest(now: Instant = Instant.now()): WakeRing? = synchronized(store.lock) {
        realRing()?.let { return@synchronized it }
        scheduler.setTest(now.toEpochMilli() + WakeAlarmRules.TEST_DELAY_MS)
        null
    }

    /**
     * Sets every alarm's next ring, posts "missed" for rings that passed unrung (phone off), rings
     * one that is due now, and queues a readiness report when something changed. Safe to call any
     * time: from the receivers, the sync, the app coming to the foreground and process start.
     */
    fun rearm(now: Instant = Instant.now()) {
        recoverRing(now)
        val result = scheduler.arm(now)
        for ((id, occurrence) in result.missed) {
            notifier.postMissed(store.current().alarm(id)?.let(WakeAlarmRules::label) ?: WakeAlarmRules.DEFAULT_LABEL, timeText(occurrence), phoneWasOff = true)
            WakeReportWorker.enqueueWake(context, id, occurrence, WakeOutcome.MISSED, now, 0)
        }
        // One ring at a time: when two lost rings are due at once (a restart between two alarms),
        // the latest rings and the earlier ones are missed. arm() has already moved them all on to
        // their next ring, so anything left out here would be lost without a word.
        val due = result.ringNow.sortedBy { it.second }
        due.dropLast(1).forEach { (id, occurrence) -> lost(id, occurrence, now) }
        // Rung at once; its fire() re-arms, and this ring is then handled, so this does not repeat.
        due.lastOrNull()?.let { (id, occurrence) -> fire(id, occurrence, now) }
        report()
    }

    /**
     * The ring in the store after a reboot or a force-stop, which drop its timeout or snooze alarm
     * and its notification: set again, rung again, or ended as missed (WakeAlarmRules.recovery).
     * Otherwise "Ringing now" or "Snoozed" would stay on Home with nothing left to ring.
     */
    private fun recoverRing(now: Instant) {
        var missed: WakeRing? = null
        synchronized(store.lock) {
            val ring = store.current().ring ?: return
            when (WakeAlarmRules.recovery(ring, now)) {
                RingRecovery.NONE -> Unit
                RingRecovery.KEEP_TIMEOUT ->
                    scheduler.setTimeout(ring.alarmId, ring.occurrence, ring.startedAt, ring.startedAt + if (ring.test) WakeAlarmRules.TEST_RING_MS else WakeAlarmRules.RING_MS)
                RingRecovery.KEEP_SNOOZE -> ring.until?.let { until ->
                    scheduler.setSnooze(ring.alarmId, ring.occurrence, until)
                    // A low, silent channel: posting it again never makes a sound.
                    notifier.postSnoozed(ring, timeText(Instant.ofEpochMilli(until)), partnerUpLine(ring))
                }
                RingRecovery.TIME_OUT -> timeout(ring.alarmId, ring.startedAt, now)
                RingRecovery.RING_AGAIN -> snoozeFire(ring.alarmId, ring.occurrence, now)
                RingRecovery.GIVE_UP -> {
                    finish(ring, WakePhase.MISSED, now)
                    if (!ring.test) missed = ring
                }
            }
        }
        missed?.let { notifier.postMissed(it.label, timeText(Instant.ofEpochMilli(it.occurrence)), phoneWasOff = true) }
    }

    /** A due ring that cannot ring now: handled here (it never rings later), noticed and reported missed. */
    private fun lost(alarmId: String, occurrence: Instant, now: Instant) {
        val label = synchronized(store.lock) {
            val s = store.current()
            if (s.handled[alarmId] == occurrence.toEpochMilli()) return
            store.update { it.copy(handled = it.handled + (alarmId to occurrence.toEpochMilli())) }
            s.alarm(alarmId)?.let(WakeAlarmRules::label) ?: WakeAlarmRules.DEFAULT_LABEL
        }
        notifier.postMissed(label, timeText(occurrence), phoneWasOff = true)
        WakeReportWorker.enqueueWake(context, alarmId, occurrence, WakeOutcome.MISSED, now, 0)
    }

    /** Queues POST /alarms/report when this phone's readiness or set rings differ from what the server has. */
    fun report() {
        val device = WakeReadiness.report(probe.read())
        val s = store.update { s ->
            val items = WakeAlarmScheduler.reportItems(s, device)
            if (items.any { (id, item) -> s.reported[id] != item }) s.copy(pendingReport = items) else s
        }
        if (s.pendingReport != null && s.me != null) WakeReportWorker.enqueueReport(context)
    }

    /** Sign-out or unlink: every set alarm, ring and notice goes, and the store is emptied. */
    fun clearAll() = synchronized(store.lock) {
        scheduler.cancelAll()
        notifier.cancelAll()
        store.clear()
        WakeReportWorker.cancelAll(context)
    }

    /** Under the lock: the partner's line for the ringing screen and notification. */
    private fun partnerLine(ring: WakeRing): String? {
        val s = store.current()
        val partner = s.alarm(ring.alarmId)?.members?.firstOrNull { it.userId != s.me }
        return WakeAlarmRules.ringPartnerLine(
            s.partnerName ?: "Your partner", partner, Instant.ofEpochMilli(ring.occurrence), ring.partnerUpAt?.let(Instant::ofEpochMilli), ZoneId.systemDefault(),
        )
    }

    /** Only "is up" for the snoozed notice ("ringing too" is old news by then). */
    private fun partnerUpLine(ring: WakeRing): String? {
        val s = store.current()
        val partner = s.alarm(ring.alarmId)?.members?.firstOrNull { it.userId != s.me }
        return WakeAlarmRules.partnerUpLine(
            s.partnerName ?: "Your partner", partner, Instant.ofEpochMilli(ring.occurrence), ring.partnerUpAt?.let(Instant::ofEpochMilli), ZoneId.systemDefault(),
        )
    }

    private fun timeText(at: Instant): String = WakeAlarmRules.timeText(at, ZoneId.systemDefault())
}
