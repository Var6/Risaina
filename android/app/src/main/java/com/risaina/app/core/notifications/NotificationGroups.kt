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
    /** Calendar reminders, posted on this phone at their time (ReminderNotifier). */
    const val REMINDER = "risaina_reminder"
    /** "Bea added … to your calendar". */
    const val CALENDAR = "risaina_calendar"

    val ALL = listOf(CHAT, PING, GAME, WIDGET, SYSTEM, REMINDER, CALENDAR)
}
