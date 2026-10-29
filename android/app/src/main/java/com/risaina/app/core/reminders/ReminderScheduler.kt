package com.risaina.app.core.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.database.CalendarDao
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.MilestoneDao
import com.risaina.app.core.database.ReminderLogDao
import com.risaina.app.core.database.ReminderLogEntity
import com.risaina.app.core.datastore.ReminderSettingsStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.CalendarOccasions
import com.risaina.app.core.domain.ReminderInput
import com.risaina.app.core.domain.ReminderPlanner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Asks for the reminders to be worked out again from what is stored now: debounced, local, no network. */
fun interface ReminderRearm {
    fun request()
}

/**
 * Calendar reminders on this phone (calendar.md §6). There is only ever ONE pending AlarmManager
 * alarm: the next reminder due. When it goes off ([run]), everything due is posted (each key claimed
 * in reminder_log first, so it posts once however many paths run), and the alarm is set for the
 * next one. A single chain stays far from the per-app alarm limits, and posting every due reminder
 * at once makes the 9-minute idle rate limit of *AllowWhileIdle alarms irrelevant.
 *
 * Exact when the person allowed "Alarms & reminders" (or below Android 12), otherwise inexact (up to
 * about an hour late). Never setAlarmClock (it shows an alarm icon and the "next alarm" on the lock
 * screen), never a full-screen intent, never a foreground service: a reminder is a plain notification.
 *
 * [run] happens at app start, after a sync or a calendar write that changed something ([request]),
 * at the end of each SyncWorker run, on boot, after an update, a clock or time-zone change and when
 * the exact-alarm permission changes (ReminderRescheduleReceiver). Every push and app open starts the
 * process, so an alarm a phone maker's battery manager dropped is set again soon.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarDao: CalendarDao,
    private val coupleDao: CoupleDao,
    private val milestoneDao: MilestoneDao,
    private val sessionStore: SessionStore,
    private val settingsStore: ReminderSettingsStore,
    private val logDao: ReminderLogDao,
    private val notifier: ReminderNotifier,
    private val time: TimeProvider,
) : ReminderRearm {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val lock = Any()
    private var pending: Job? = null

    /** From RisainaApp.onCreate: catch up now, then follow settings, my birthday, sign-out and unlink. */
    fun start() {
        scope.launch { runQuietly() }
        scope.launch {
            // "since" changes on the first run: only the person's choices count as a change here.
            settingsStore.settings.map { it.copy(since = null) }.distinctUntilChanged().drop(1).collect { request() }
        }
        scope.launch { sessionStore.profile.map { it?.birthday }.distinctUntilChanged().drop(1).collect { request() } }
        scope.launch {
            var last: Pair<Boolean, String?>? = null
            combine(sessionStore.session.map { it != null }, sessionStore.activeCoupleId) { signedIn, couple -> signedIn to couple }
                .distinctUntilChanged()
                .collect { now ->
                    val before = last
                    last = now
                    // Signed out, or the couple ended: the calendar is gone from Room (clearAllContent)
                    // and its notifications from the shade (RisainaNotifier.clearAllPartnerNotifications).
                    if (before != null && before.first && before.second != null && (!now.first || now.second != before.second)) {
                        cancelAlarm()
                        CalendarDoneWorker.cancelAll(context)
                        runCatching { settingsStore.forgetStart() }
                    }
                }
        }
    }

    override fun request() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(DEBOUNCE_MS)
                runQuietly()
            }
        }
    }

    private suspend fun runQuietly() {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /** Posts what is due and sets the alarm for the next reminder. Room and DataStore only: a few ms. */
    suspend fun run() = mutex.withLock {
        val session = sessionStore.current()
        if (session == null) {
            cancelAlarm()
            return@withLock
        }
        val now = time.now()
        settingsStore.startIfNeeded(now)
        val settings = settingsStore.current()
        val partner = coupleDao.partner()?.toDomain()
        val partnerZone = partner?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val occasions = CalendarOccasions.derive(
            coupleDao.couple()?.toDomain(), session.userId, sessionStore.profile.first()?.birthday, partner,
            milestoneDao.all().first().map { it.toDomain() },
        )
        val entries = calendarDao.all().first().mapNotNull { it.toDomain() }
        val plan = ReminderPlanner.plan(ReminderInput(entries, occasions, settings, session.userId, time.zone(), now, partnerZone))
        for (r in plan.due) {
            // Claimed even when nothing can be posted (notifications off): it is not owed later.
            if (logDao.insertOrIgnore(ReminderLogEntity(r.key, now.toEpochMilli())) != -1L) notifier.post(r, partner, partnerZone)
        }
        logDao.prune(now.minus(LOG_KEEP).toEpochMilli())
        plan.next?.let(::arm) ?: cancelAlarm()
    }

    /** Whether reminders arrive on time: "Alarms & reminders" allowed, or not needed below Android 12. */
    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < 31 ||
        runCatching { context.getSystemService(AlarmManager::class.java)!!.canScheduleExactAlarms() }.getOrDefault(false)

    private fun arm(at: Instant) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = maxOf(at.toEpochMilli(), System.currentTimeMillis() + 1_000)
        val pi = alarmIntent()
        try {
            if (ReminderPlanner.useExact(Build.VERSION.SDK_INT, exactAlarmsAllowed())) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (_: SecurityException) {
            // The permission was taken back between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    fun cancelAlarm() {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent()) }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_REMIND = "com.risaina.app.REMIND"
        private const val REQUEST_CODE = 6060
        private const val DEBOUNCE_MS = 2_000L
        /** Long enough that a key is never claimed again while its reminder could still be due. */
        private val LOG_KEEP: Duration = Duration.ofDays(45)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RemindersModule {
    @Binds abstract fun rearm(scheduler: ReminderScheduler): ReminderRearm
}
