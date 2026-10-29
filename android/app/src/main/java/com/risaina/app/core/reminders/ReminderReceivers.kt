package com.risaina.app.core.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.risaina.app.core.database.CalendarDao
import com.risaina.app.core.datastore.SessionStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun scheduler(): ReminderScheduler
    fun calendarDao(): CalendarDao
    fun sessionStore(): SessionStore
    fun notifier(): ReminderNotifier
}

private fun Context.reminders(): ReminderEntryPoint = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)

/** Receivers finish their work here, inside the few seconds goAsync() allows. */
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Room only, well within the ~10 s a receiver gets. If it ever runs out, the next app start or push catches up. */
private const val BUDGET_MS = 8_000L

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val result = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(BUDGET_MS) { block() }
        } catch (_: Throwable) {
        } finally {
            result.finish()
        }
    }
}

/** The one reminder alarm went off: post what is due, set the next. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        val scheduler = context.reminders().scheduler()
        runAsync { if (withTimeoutOrNull(BUDGET_MS - 500) { scheduler.run() } == null) scheduler.request() }
    }
}

/**
 * Sets the alarm again when Android may have dropped or moved it: after a reboot or an update (alarms
 * do not survive either), when the clock or the time zone changes (floating and all-day reminders
 * follow the phone's clock), and when "Alarms & reminders" is allowed or taken back. Not exported:
 * these come from the system only.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val scheduler = context.reminders().scheduler()
        runAsync { scheduler.run() }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/**
 * "Done" on a to-do's reminder: ticked in Room at once (the notification goes, the reminders stop),
 * then sent by CalendarDoneWorker when there is a network. The server's reply replaces the local tick.
 */
class CalendarDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(EXTRA_ENTRY) ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: ReminderNotifier.TAG
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val deps = context.reminders()
        deps.notifier().cancel(tag, id)
        runAsync {
            val me = deps.sessionStore().current()?.userId
            deps.calendarDao().markDone(entryId, done = true, doneAt = System.currentTimeMillis(), doneBy = me)
            CalendarDoneWorker.enqueue(context, entryId)
            deps.scheduler().run()
        }
    }

    companion object {
        private const val ACTION = "com.risaina.app.CALENDAR_DONE"
        private const val EXTRA_ENTRY = "entryId"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_ID = "notificationId"

        fun pendingIntent(context: Context, entryId: String, tag: String, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, CalendarDoneReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_ENTRY, entryId).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
