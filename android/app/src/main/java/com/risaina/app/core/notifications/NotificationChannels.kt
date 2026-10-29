package com.risaina.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

object NotificationChannels {
    /**
     * v2: a clear "heartbeat" buzz. The first channel used three 40–90 ms taps that many phones
     * barely render, so a ping felt like nothing. Channel vibration can't change after creation,
     * hence the new id (the old channel is deleted below).
     */
    const val PINGS = "pings_v2"
    private const val OLD_PINGS = "pings"
    /** Two heartbeats, about 0.6 s of vibration in total. */
    val HEARTBEAT = longArrayOf(0, 160, 110, 160, 380, 160, 110, 160)
    const val PINGS_QUIET = "pings_quiet"
    const val MESSAGES = "messages"
    const val MESSAGES_QUIET = "messages_quiet"
    const val RITUALS = "rituals"
    const val SYSTEM = "system"
    /** Calendar reminders: heads-up with sound, like an alarm the person asked for. */
    const val REMINDERS = "reminders"
    /** Reminders and calendar notices during quiet hours, shown silently. */
    const val REMINDERS_QUIET = "reminders_quiet"
    /** "Bea added … to your calendar". */
    const val CALENDAR_UPDATES = "calendar_updates"

    // safety stream: SOS alarm and live location channels
    /** The ringing SOS (foreground service + full-screen alert). Silent: the service plays the alarm itself. */
    const val SOS_ALARM = "sos_alarm_v2"
    /**
     * Extra ringer used only in a Do Not Disturb mode that blocks alarms (and only with DND access): the
     * alarm stream itself is muted there, but the ringtone stream is not while a channel bypasses DND.
     */
    const val SOS_RING_DND = "sos_ring_dnd"
    /** Missed SOS, "safe now", an SOS that could not be sent. */
    const val SOS_UPDATES = "sos_updates"
    /** "You are sharing your location" (the sharer's own ongoing notification). */
    const val LOCATION_SHARING = "location_sharing"
    const val PARTNER_LOCATION = "partner_location"
    const val PARTNER_LOCATION_QUIET = "partner_location_quiet"

    /** Idempotent. Channels are user-controllable after creation, so we only set sensible defaults. */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        runCatching { nm.deleteNotificationChannel(OLD_PINGS) }
        nm.createNotificationChannel(
            NotificationChannel(PINGS, "Touch Pings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A small signal from your partner"
                enableVibration(true)
                vibrationPattern = HEARTBEAT
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
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Calendar reminders you set, and the dates that matter to you both"
                enableVibration(true)
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS_QUIET, "Calendar (quiet hours)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Reminders and calendar changes during your quiet hours, shown silently"
                enableVibration(false); setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CALENDAR_UPDATES, "Calendar updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When your partner adds something to your calendar and asks to let you know"
            },
        )
        ensureSafety(context)
    }

    /**
     * Safety channels, created at app start so they exist before the first SOS (the readiness card can
     * then check them, and the user can allow "Override Do Not Disturb" on them in advance). Called
     * again whenever the app comes to the foreground: once Do Not Disturb access is granted, creating
     * an untouched channel again with setBypassDnd(true) turns the bypass on.
     */
    fun ensureSafety(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val dnd = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
        nm.createNotificationChannel(
            NotificationChannel(SOS_ALARM, "SOS from your partner", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The full-screen SOS alarm. The sound comes from the alarm itself, so it rings even on silent."
                setSound(null, null)
                enableVibration(false)
                if (dnd) setBypassDnd(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SOS_RING_DND, "SOS through Do Not Disturb", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings for an SOS when Do Not Disturb blocks alarms"
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                if (dnd) setBypassDnd(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SOS_UPDATES, "SOS updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A missed SOS, \"I'm safe now\", or an SOS that could not be sent"
            },
        )
        nm.createNotificationChannel(
            // DEFAULT (not LOW) with no sound: phones that hide icons for silent notifications still
            // show that sharing is on.
            NotificationChannel(LOCATION_SHARING, "Sharing your location", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown the whole time you share your live location"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(PARTNER_LOCATION, "Partner's live location", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When your partner starts sharing their location with you"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(PARTNER_LOCATION_QUIET, "Partner's live location (quiet hours)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The same, during your quiet hours, shown silently"
                setSound(null, null); enableVibration(false)
            },
        )
    }
}
