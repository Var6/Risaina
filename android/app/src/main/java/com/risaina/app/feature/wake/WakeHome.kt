package com.risaina.app.feature.wake

import com.risaina.app.core.alarm.MemberStatus
import com.risaina.app.core.alarm.WakeAlarmRules
import com.risaina.app.core.alarm.WakeLocal
import com.risaina.app.core.alarm.WakePhase
import com.risaina.app.core.alarm.WakeReadyReport
import com.risaina.app.core.alarm.WakeRing
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeOutcome
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The Home card: which alarm it is about, and one line on it. Pure (WakeHomeTest). */
object WakeHome {
    /** After "I'm up" (or a missed ring) the card says so for this long. */
    private val RECENT: Duration = Duration.ofHours(6)
    /** "Send a good-morning ping" is offered this long after "I'm up". */
    private val GOOD_MORNING: Duration = Duration.ofHours(2)

    /**
     * The alarm ringing, snoozed or just answered on this phone; otherwise the one that rings
     * soonest; otherwise any (it is off). Null when there is none.
     */
    fun pick(local: WakeLocal, now: Instant): WakeAlarm? {
        val alarms = local.domain()
        if (alarms.isEmpty()) return null
        local.ring?.takeIf { !it.test && (it.active || recent(it, now)) }?.let { r -> alarms.firstOrNull { it.id == r.alarmId }?.let { return it } }
        return alarms.mapNotNull { a -> WakeAlarmRules.nextOfRule(a, now)?.takeIf { a.enabled }?.let { a to it } }.minByOrNull { it.second }?.first
            ?: alarms.first()
    }

    /**
     * "Tomorrow 6:30 am · both phones ✓", "Raina is up ☀ · you're snoozed", "You're both up · 6:31
     * am / 6:34 am"…
     */
    fun line(alarm: WakeAlarm, local: WakeLocal, device: WakeReadyReport, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val me = local.me
        val partnerName = local.partnerName ?: "Your partner"
        val mine = me?.let(alarm::member)
        val partner = alarm.members.firstOrNull { it.userId != me }
        fun time(at: Instant) = WakeAlarmRules.timeText(at, zone, locale)
        local.ring?.takeIf { it.alarmId == alarm.id && !it.test }?.let { r ->
            val occurrence = Instant.ofEpochMilli(r.occurrence)
            val partnerUp = r.partnerUpAt?.let(Instant::ofEpochMilli)
                ?: partner?.lastWake?.takeIf { it.occurrence == occurrence && it.outcome == WakeOutcome.UP }?.at
            when (r.phase) {
                WakePhase.RINGING -> return if (partnerUp != null) "$partnerName is up ☀ · yours is ringing" else "Ringing now"
                WakePhase.SNOOZED -> return if (partnerUp != null) "$partnerName is up ☀ · you're snoozed"
                else "Snoozed until ${time(Instant.ofEpochMilli(r.until ?: r.startedAt))}"
                WakePhase.UP -> if (recent(r, now)) {
                    val mineAt = Instant.ofEpochMilli(r.doneAt ?: r.startedAt)
                    return if (partnerUp != null) "You're both up · ${time(mineAt)} / ${time(partnerUp)}" else "You're up · ${time(mineAt)}"
                }
                WakePhase.MISSED -> if (recent(r, now)) return "Missed at ${time(occurrence)}" + (partnerUp?.let { " · $partnerName is up ☀" } ?: "")
            }
        }
        if (!alarm.enabled) return "Off"
        if (!WakeAlarmRules.canSchedule(alarm)) return "Update Risaina to use this alarm"
        val next = WakeAlarmRules.next(alarm, mine, now) ?: WakeAlarmRules.nextOfRule(alarm, now) ?: return "Nothing coming up"
        val myStatus = WakeAlarmRules.myStatus(alarm, mine, device.ready, device.reason, local.armed[alarm.id]?.let(Instant::ofEpochMilli), now)
        val theirs = WakeAlarmRules.memberStatus(alarm, partner, now)
        val myOn = myStatus != MemberStatus.OFF
        val theirOn = theirs != MemberStatus.OFF
        val tail = when {
            !myOn && !theirOn -> "off on both phones"
            !myOn -> "only $partnerName's phone"
            !theirOn -> "your phone only"
            myStatus != MemberStatus.READY -> "check your phone"
            theirs == MemberStatus.READY -> "both phones ✓"
            theirs == MemberStatus.NEEDS_UPDATE -> "$partnerName needs the new Risaina"
            theirs == MemberStatus.NOT_SET_YET -> "not set on $partnerName's phone yet"
            else -> "not ready on $partnerName's phone"
        }
        return "${WakeAlarmRules.whenText(next, zone, now, locale)} · $tail"
    }

    /**
     * The ring the Wake screen offers "Send a good-morning ping" for: this person said "I'm up" in
     * the last [GOOD_MORNING] and the partner has not (yet) for the same ring. Once a ping went
     * from there for it, null. It reuses the good_morning ping and its push.
     */
    fun goodMorningFor(local: WakeLocal, now: Instant): WakeRing? {
        val r = local.ring?.takeIf { !it.test && it.phase == WakePhase.UP && !it.goodMorningSent } ?: return null
        val done = r.doneAt?.let(Instant::ofEpochMilli) ?: return null
        if (Duration.between(done, now) >= GOOD_MORNING) return null
        val alarm = local.alarm(r.alarmId) ?: return null
        val partner = alarm.members.firstOrNull { it.userId != local.me }
        val occurrence = Instant.ofEpochMilli(r.occurrence)
        val partnerUp = r.partnerUpAt != null || partner?.lastWake?.let { it.occurrence == occurrence && it.outcome == WakeOutcome.UP } == true
        return r.takeIf { !partnerUp }
    }

    private fun recent(r: WakeRing, now: Instant): Boolean =
        r.doneAt?.let { Duration.between(Instant.ofEpochMilli(it), now) < RECENT } == true
}
