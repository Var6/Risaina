package com.risaina.app.core.notifications

/**
 * One explicit group per kind of partner notification, the same fix as the SOS alarm, the live
 * location and the lock card. Without a group, Android 15+ and ColorOS bundle an app's notifications
 * into an automatic group of their own, and AndroidX's setSilent(true) put a notification into a
 * group called "silent" (NotificationCompat.GROUP_KEY_SILENT), outside that bundle: one item could
 * then look like two. Each group holds one notification, so none needs a summary.
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

    val ALL = listOf(CHAT, PING, GAME, WIDGET, SYSTEM, WAKE_ALARM, WAKE_STATUS, WAKE_MISSED, WAKE_SET, WAKE_PARTNER)
}
