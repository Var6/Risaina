package com.risaina.app.core.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.risaina.app.core.common.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WakeAlarmEntryPoint {
    fun controller(): WakeAlarmController
    @ApplicationScope fun scope(): CoroutineScope
}

/**
 * Every Wake together broadcast: the set ring (FIRE), a snooze ending, the ring's timeout, and the
 * buttons (Snooze, I'm up, Not on my phone). Not exported: only this app's own PendingIntents reach
 * it. The work is local and quick but runs off the main thread, inside goAsync.
 */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WakeAlarmEntryPoint::class.java)
        val pending = goAsync()
        entry.scope().launch {
            try {
                entry.controller().handle(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Wake alarm broadcast failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RisainaWake"
    }
}

/**
 * Sets the next rings again when Android dropped or moved them: after a reboot (alarms do not
 * survive one) and an app update, when the clock or the time zone changes, and when "Alarms &
 * reminders" is granted. Reads only the SharedPreferences store; no network. BOOT_COMPLETED comes
 * after the first unlock, so a phone that restarts overnight and stays locked misses its alarm.
 */
class WakeAlarmRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WakeAlarmEntryPoint::class.java)
        val pending = goAsync()
        entry.scope().launch {
            try {
                entry.controller().rearm()
            } catch (e: Exception) {
                Log.w(TAG, "Wake alarm re-arm failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RisainaWake"
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
