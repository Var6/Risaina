package com.risaina.app.core.notifications

import androidx.core.app.NotificationCompat

/**
 * One explicit group per kind of partner notification, the same fix as the SOS alarm, the live
 * location and the lock card. Without a group, Android 15+ and ColorOS bundle an app's notifications
 * into an automatic group of their own, and AndroidX's setSilent(true) put a notification into a
 * group called "silent" (NotificationCompat.GROUP_KEY_SILENT), outside that bundle: one item could
 * then look like two. Each group holds one notification (the safety notices seldom more), so none
 * needs a summary.
 *
 * A new kind of notification gets a new group here. Pure, so NotificationGroupsTest checks that
 * no two kinds share one and none is "silent".
 */
object NotificationGroups {
    /** The one chat conversation notification (RisainaNotifier.MESSAGES_ID). */
    const val CHAT = "risaina_chat"
    const val PING = "risaina_ping"
    const val GAME = "risaina_game"
    const val WIDGET = "risaina_widget"
    /** Unlink and the test push. */
    const val SYSTEM = "risaina_system"
    /**
     * The partner's "is sharing their live location" (SafetyNotifier) and the notices around an SOS
     * (SosAlert: missed, safe now, tap to respond, not sent). Not the ringing alarm, which keeps
     * SosAlert.SOS_GROUP.
     */
    const val SAFETY = "risaina_safety"

    /** Calendar reminders, posted on this phone at their time (ReminderNotifier). */
    const val REMINDER = "risaina_reminder"
    /** "Bea added … to your calendar". */
    const val CALENDAR = "risaina_calendar"

    /** Wake together: the ringing alarm, like the SOS alarm's own group, so its full-screen launch is never bundled. */
    const val WAKE_ALARM = "risaina_wake_alarm"
    /** "Snoozed until 6:40" (ongoing). */
    const val WAKE_STATUS = "risaina_wake_status"
    /** "Missed: Wake together 6:30". */
    const val WAKE_MISSED = "risaina_wake_missed"
    /** "Raina set Wake together for 6:30 am…", with "Not on my phone". */
    const val WAKE_SET = "risaina_wake_set"
    /** "Raina is up too · 6:34". */
    const val WAKE_PARTNER = "risaina_wake_partner"

    val ALL = listOf(
        CHAT, PING, GAME, WIDGET, SYSTEM, SAFETY, REMINDER, CALENDAR,
        WAKE_ALARM, WAKE_STATUS, WAKE_MISSED, WAKE_SET, WAKE_PARTNER,
    )

    /**
     * For a post that must not sound, buzz or pop up even when it creates the notification.
     * onlyAlertOnce only quiets an update of one already in the shade, and setSilent is what moved a
     * notification into the "silent" group. GROUP_ALERT_SUMMARY mutes a notification that is not a
     * summary and leaves it in its own group; none of these groups has a summary to alert instead.
     */
    fun alertBehavior(muted: Boolean): Int =
        if (muted) NotificationCompat.GROUP_ALERT_SUMMARY else NotificationCompat.GROUP_ALERT_ALL
}
