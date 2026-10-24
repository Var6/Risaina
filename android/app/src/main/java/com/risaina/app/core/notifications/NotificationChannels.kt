package com.risaina.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

object NotificationChannels {
    const val PINGS = "pings"
    const val PINGS_QUIET = "pings_quiet"
    const val MESSAGES = "messages"
    const val MESSAGES_QUIET = "messages_quiet"
    const val RITUALS = "rituals"
    const val SYSTEM = "system"

    /** Idempotent. Channels are user-controllable after creation, so we only set sensible defaults. */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        nm.createNotificationChannel(
            NotificationChannel(PINGS, "Touch Pings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A small signal from your partner"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 40, 60, 40, 60, 90)
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio)
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(PINGS_QUIET, "Touch Pings (quiet hours)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Pings that arrive during your quiet hours, shown silently"
                enableVibration(false); setSound(null, null); setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New notes, photos and voice notes"
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(MESSAGES_QUIET, "Messages (quiet hours)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Messages that arrive during your quiet hours, shown silently"
                enableVibration(false); setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(RITUALS, "Rituals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Optional good-morning and goodnight reminders you set up"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SYSTEM, "Account", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Pairing and account changes"
            },
        )
    }
}
