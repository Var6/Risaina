package com.risaina.app.feature.wake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.alarm.MemberStatus
import com.risaina.app.core.alarm.WakeAlarmController
import com.risaina.app.core.alarm.WakeAlarmRules
import com.risaina.app.core.alarm.WakeAlarmStore
import com.risaina.app.core.alarm.WakeAlarmSync
import com.risaina.app.core.alarm.WakeDeviceState
import com.risaina.app.core.alarm.WakeLocal
import com.risaina.app.core.alarm.WakePhase
import com.risaina.app.core.alarm.WakeReadiness
import com.risaina.app.core.alarm.WakeReadinessProbe
import com.risaina.app.core.alarm.WakeReadyReport
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.PingRepository
import com.risaina.app.core.data.PreferencesRepository
import com.risaina.app.core.data.WakeAlarmRepository
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.data.toDto
import com.risaina.app.core.model.Partner
import com.risaina.app.core.model.PingType
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.network.alarm
import com.risaina.app.core.safety.ReadinessCheck
import com.risaina.app.core.safety.SosReadinessProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** One alarm on the Wake screen, in this phone's words. */
data class WakeRowUi(
    val alarm: WakeAlarm,
    /** This phone's time of the next ring ("6:30 am"). */
    val time: String,
    val title: String,
    /** "Mon–Fri" on this phone's calendar, or "Once". */
    val schedule: String,
    /** "Tomorrow 6:30 am", or why nothing is coming. */
    val next: String?,
    val localLine: String?,
    val me: MemberStatus,
    val partner: MemberStatus,
    val myRing: Boolean,
    /** The next ring on this phone, which "Skip … on my phone" skips. */
    val skippable: Instant?,
    /** The earliest upcoming ring this person skipped, which "Undo skip" brings back. */
    val skipped: Instant?,
    val supported: Boolean,
)

/** The Home card: shown when at least one alarm exists. */
data class WakeHomeUi(val title: String, val line: String)

data class WakeUi(
    val rows: List<WakeRowUi> = emptyList(),
    val home: WakeHomeUi? = null,
    val me: String? = null,
    val partnerName: String = "Your partner",
    /** Null when the partner hides their local time. */
    val partnerZone: ZoneId? = null,
    val checks: List<ReadinessCheck> = emptyList(),
    val device: WakeReadyReport = WakeReadyReport(true, null),
    val busy: Boolean = false,
    /** False on a server older than alarms. */
    val available: Boolean? = null,
    val shareLocalTime: Boolean = true,
    /** I'm up and the partner is not yet: "Send a good-morning ping" (WakeHome.goodMorningFor). */
    val goodMorning: Boolean = false,
)

@HiltViewModel
class WakeViewModel @Inject constructor(
    private val store: WakeAlarmStore,
    private val controller: WakeAlarmController,
    private val sync: WakeAlarmSync,
    private val repository: WakeAlarmRepository,
    private val probe: WakeReadinessProbe,
    private val fixes: SosReadinessProbe,
    private val json: Json,
    private val pings: PingRepository,
    coupleRepository: CoupleRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val effects = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = effects.receiveAsFlow()

    private val device = MutableStateFlow(probe.read())
    private val busy = MutableStateFlow(false)
    /** Labels like "Tomorrow" move on with the clock. */
    private val clock = flow { while (true) { emit(Instant.now()); delay(30_000) } }

    private val _editor = MutableStateFlow<WakeDraft?>(null)
    /** The alarm being set or changed, null when the editor is closed. */
    val editor: StateFlow<WakeDraft?> = _editor.asStateFlow()

    /**
     * For an edit: the alarm the editor started from (or last caught up with) and the draft it gave.
     * What this person changed is measured against it, and the PATCH goes at its version.
     */
    private var editorBase: Pair<WakeAlarm, WakeDraft>? = null

    val state: StateFlow<WakeUi> = combine(
        store.state, coupleRepository.partner, device, preferencesRepository.preferences.map { it.shareLocalTime },
        combine(busy, repository.available, clock) { b, a, now -> Triple(b, a, now) },
    ) { local, partner, d, share, (b, available, now) -> build(local, partner, d, share, b, available, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeUi())

    private fun build(local: WakeLocal, partner: Partner?, d: WakeDeviceState, share: Boolean, b: Boolean, available: Boolean?, now: Instant): WakeUi {
        val zone = ZoneId.systemDefault()
        val report = WakeReadiness.report(d)
        val partnerName = partner?.displayName ?: local.partnerName ?: "Your partner"
        val home = WakeHome.pick(local, now)?.let { WakeHomeUi(WakeAlarmRules.label(it), WakeHome.line(it, local.copy(partnerName = partnerName), report, now, zone)) }
        return WakeUi(
            rows = local.domain().map { row(it, local, report, partnerName, now, zone) },
            home = home, me = local.me, partnerName = partnerName,
            partnerZone = partner?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
            checks = WakeReadiness.evaluate(d), device = report, busy = b, available = available, shareLocalTime = share,
            goodMorning = WakeHome.goodMorningFor(local, now) != null,
        )
    }

    private fun row(a: WakeAlarm, local: WakeLocal, report: WakeReadyReport, partnerName: String, now: Instant, zone: ZoneId): WakeRowUi {
        val mine = local.me?.let(a::member)
        val partner = a.members.firstOrNull { it.userId != local.me }
        val myNext = WakeAlarmRules.next(a, mine, now)
        val ring = myNext ?: WakeAlarmRules.nextOfRule(a, now)
        val schedule = if (a.days.isEmpty()) "Once" else WakeAlarmRules.daysText(WakeAlarmRules.localDays(a, zone, now))
        return WakeRowUi(
            alarm = a,
            time = ring?.let { WakeAlarmRules.timeText(it, zone) } ?: WakeAlarmRules.timeText(a.time),
            title = WakeAlarmRules.label(a),
            schedule = schedule,
            next = when {
                !WakeAlarmRules.canSchedule(a) -> null
                !a.enabled -> "Off for both of you"
                ring == null -> "Done"
                else -> WakeAlarmRules.whenText(ring, zone, now)
            },
            localLine = ring?.let { WakeAlarmRules.localLine(a, it, zone, partnerName.takeIf { a.createdBy != local.me }) },
            me = WakeAlarmRules.myStatus(a, mine, report.ready, report.reason, local.armed[a.id]?.let(Instant::ofEpochMilli), now),
            partner = WakeAlarmRules.memberStatus(a, partner, now),
            myRing = mine?.ring ?: false,
            skippable = myNext,
            skipped = mine?.skips?.filter { it.isAfter(now) && WakeAlarmRules.isOccurrence(a, it) }?.minOrNull(),
            supported = WakeAlarmRules.canSchedule(a),
        )
    }

    /** Back on the screen: the phone's settings may have changed, and so may the alarms. */
    fun onResume() {
        device.value = probe.read()
        controller.report()
        refresh()
    }

    /**
     * GET /alarms again. The partner's phone reports the ring it set without an event (the server
     * stores it silently), so "Not set on their phone yet" only clears with a fresh copy.
     */
    fun refresh() {
        viewModelScope.launch { runCatching { sync.refresh() } }
    }

    /** A readiness row's button: the SOS card's fixes, and the two only Wake together has. */
    fun fix(check: ReadinessCheck) {
        when (check.key) {
            WakeReadiness.KEY_CHANNEL, WakeReadiness.KEY_SOUND, WakeReadiness.KEY_POP_UP -> probe.openAlarmChannel()
            WakeReadiness.KEY_VOLUME -> probe.showAlarmVolume()
            else -> check.fix?.let(fixes::open)
        }
    }

    /** "Alarm sound": the alarm channel's settings, where the tone can be changed. */
    fun openAlarmSound() = probe.openAlarmChannel()

    /**
     * I'm up and the partner is not yet: the existing good_morning ping (its own push), once per
     * ring from here.
     */
    fun sendGoodMorning() = work {
        val ring = WakeHome.goodMorningFor(store.current(), Instant.now()) ?: return@work
        val type = PingType.GOOD_MORNING
        when (val r = pings.send(type, type.defaultEmoji, type.defaultMessage)) {
            is AppResult.Success -> {
                store.update { s ->
                    s.copy(ring = s.ring?.takeIf { it.alarmId == ring.alarmId && it.occurrence == ring.occurrence }?.copy(goodMorningSent = true) ?: s.ring)
                }
                say("Good morning sent ☀")
            }
            is AppResult.Failure -> say(
                when (val e = r.error) {
                    is AppError.RateLimited -> "You just sent a ping. Give it ${e.retryAfterSeconds ?: 15}s"
                    is AppError.Offline -> "You're offline"
                    else -> e.message ?: "Couldn't send"
                },
            )
        }
    }

    fun openNew() {
        editorBase = null
        _editor.value = WakeDrafts.new(ZoneId.systemDefault(), Instant.now())
    }

    fun openEdit(alarm: WakeAlarm) {
        val me = store.current().me ?: return
        val d = WakeDrafts.of(alarm, me, Instant.now())
        editorBase = alarm to d
        _editor.value = d
    }

    fun edit(change: (WakeDraft) -> WakeDraft) = _editor.update { it?.let(change) }

    fun closeEditor() {
        _editor.value = null
        editorBase = null
    }

    fun save() {
        val d = _editor.value ?: return
        WakeDrafts.problem(d, Instant.now())?.let { say(it); return }
        work {
            if (d.id == null) {
                when (val r = repository.create(WakeDrafts.wireTime(d), d.zone, d.days.sorted(), WakeDrafts.onceAt(d), d.label, d.ringOnMyPhone, d.snoozeMinutes)) {
                    is AppResult.Success -> {
                        keep(r.value)
                        closeEditor()
                        say(if (d.ringOnMyPhone) "Set. It rings on both phones." else "Set. It rings on ${partnerName()}'s phone.")
                    }
                    is AppResult.Failure -> say(r.error.message ?: "That didn't save")
                }
                return@work
            }
            val me = store.current().me ?: ""
            val current = store.current().alarm(d.id) ?: run { closeEditor(); say("This alarm was deleted"); return@work }
            var (base, start) = editorBase ?: (current to WakeDrafts.of(current, me, Instant.now()))
            var draft = d
            // Catches the draft up with a newer copy. Only a member entry moved on (a readiness
            // report, a skip, the partner's ring switch): true, send it again. A shared field
            // changed: the draft keeps this person's changes, shows the partner's, and waits.
            fun catchUp(newer: WakeAlarm): Boolean {
                val rb = WakeDrafts.rebase(base, start, newer, draft, me, Instant.now())
                base = newer; start = rb.start; draft = rb.draft
                editorBase = base to start
                _editor.value = draft
                if (!rb.retry) say(WakeDrafts.conflictText(partnerName(), rb.changed, newer.enabled))
                return rb.retry
            }
            // An event already brought a newer copy while the sheet was open.
            if (current.version > base.version && !catchUp(current)) return@work
            var tries = 0
            while (true) {
                val patch = WakeDrafts.patch(base, draft) ?: break
                val r = repository.update(d.id, patch)
                if (r is AppResult.Success) { keep(r.value); break }
                val e = (r as AppResult.Failure).error
                if (e !is AppError.VersionConflict) { say(e.message ?: "That didn't save"); return@work }
                e.alarm(json)?.let { store.upsert(it, System.currentTimeMillis()); controller.rearm() }
                val newer = store.current().alarm(d.id) ?: run { closeEditor(); say("This alarm was deleted"); return@work }
                if (newer.version <= base.version || ++tries > MAX_SAVE_RETRIES) { say("That didn't save. Try again in a moment."); return@work }
                if (!catchUp(newer)) return@work
            }
            // This person's own switch: applies here at once, and reaches the server when it can.
            val myRing = store.current().alarm(d.id)?.member(me)?.ring ?: true
            if (draft.ringOnMyPhone != myRing) {
                controller.setMyRing(d.id, draft.ringOnMyPhone)
                if (!draft.ringOnMyPhone) say("It won't ring on your phone. ${partnerName()}'s still rings.")
            }
            closeEditor()
        }
    }

    /** On or off for both of you. A partner's change in between is taken and tried once more. */
    fun setEnabled(alarm: WakeAlarm, enabled: Boolean) = work {
        var r = repository.setEnabled(alarm, enabled)
        val e = (r as? AppResult.Failure)?.error
        if (e is AppError.VersionConflict) {
            val newer = e.alarm(json)?.toDomain()
            if (newer != null) r = repository.setEnabled(newer, enabled)
        }
        when (r) {
            is AppResult.Success -> keep(r.value)
            is AppResult.Failure -> say(r.error.message ?: "That didn't change")
        }
    }

    fun setSkip(alarm: WakeAlarm, occurrence: Instant, skip: Boolean) = work {
        when (val r = repository.setSkip(alarm.id, occurrence, skip)) {
            is AppResult.Success -> {
                keep(r.value)
                if (skip) say("Skipped on your phone. ${partnerName()}'s still rings.")
            }
            is AppResult.Failure -> say(r.error.message ?: "That didn't change")
        }
    }

    fun delete(alarm: WakeAlarm) = work {
        when (val r = repository.delete(alarm.id)) {
            is AppResult.Success -> {
                controller.alarmGone(alarm.id)
                store.remove(alarm.id, System.currentTimeMillis())
                controller.rearm()
                closeEditor()
                say("Deleted on both phones")
            }
            is AppResult.Failure -> say(r.error.message ?: "That didn't delete")
        }
    }

    fun test() {
        val busyWith = controller.scheduleTest()
        say(
            when (busyWith?.phase) {
                null -> "It rings in 10 seconds. Lock your phone to see it the way it rings in the morning."
                WakePhase.SNOOZED -> "Your alarm is snoozed. Try the test once you're up."
                else -> "Your alarm is ringing. Try the test once you're up."
            },
        )
    }

    /** The server's copy after this phone's own change, set at once. */
    private fun keep(alarm: WakeAlarm) {
        store.upsert(alarm.toDto(), System.currentTimeMillis())
        controller.rearm()
    }

    private fun partnerName(): String = state.value.partnerName

    private fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() } finally { busy.value = false }
        }
    }

    private fun say(message: String) { effects.trySend(message) }

    private companion object {
        /** Saves sent again by themselves after a conflict that only moved a member entry on. */
        const val MAX_SAVE_RETRIES = 2
    }
}
