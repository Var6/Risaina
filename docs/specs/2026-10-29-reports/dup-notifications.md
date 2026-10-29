# dup-notifications

## Summary
The backend sends each partner action to the partner's phone exactly once. I checked this against the live database on 2026-10-29. In the real couple each phone has exactly one device row, and no FCM token is shared between rows. Of 202 pushes sent in 30 days, none was retried and all went out within 2 s. No message or ping produced two events, and there were no SOS re-pushes. So the doubles are made on the phone. The quick alert and the detailed notification cannot cause them: they share one id (4242 for pings, messageId.hashCode() for messages), so the detailed one replaces the quick one in place. The cause I can confirm in code is the opt-in lock-screen card (LockScreenCard, id 7171). When it is on, it shows the same message text, ping, gift or widget update a second time, next to the normal notification. Two smaller causes also show up in the code. Chat notifications are never removed when a message is read, and every message in a burst gets its own notification. Also, AndroidX setSilent(true) quietly puts the detailed notification into a group called "silent", which interacts with Android 15+ auto-grouping and phone-maker bundling. Other cases (a quick alert coming back after it was swiped away, games and pings buzzing twice under one id, a panic SOS also posting the live-location notice, the "Catching up" notification on Android 11 and below) are possible but unconfirmed. Fixes on both ends are below. The backend changes are only hardening, and all of them are additive.

## Root causes
- [likely] Most likely cause, if the card is on: the lock-screen card repeats every text or emoji message, ping, gift and widget update, so the partner's phone shows two notifications for one action.
  - LockScreenCard.kt:40-58: an unseen gift becomes "A gift from X" and an unseen ping becomes "X reached out". Otherwise the card's line is s.latestNoteText. WidgetSnapshotUpdater.kt:119-131 builds latestNoteText from the partner's newest text or emoji chat message (messageDao.recent filtered to type text/emoji), unless the widget-board note is newer. LockScreenCard.kt:67-71 posts again whenever that content changes. WidgetSnapshotUpdater.kt:220 calls lockScreenCard.update on every recompute, and EventSyncManager.kt:163-168 recomputes after every sync, including the push handler's. The card is its own notification (id 7171, its own group 'risaina_lock_card', IMPORTANCE_DEFAULT, VISIBILITY_PUBLIC), separate from ping 4242, widget 4345 and the per-message ids. So one message gives 'X: hello' plus a card saying 'X ... hello', and one ping gives 'Touch from X' plus 'X reached out · tap to send one back'. The card is off by default (Models.kt:186) and the setting lives only on the phone (the backend Preferences type has no such field), so I could not confirm from the database that it is on. docs/PROGRESS.md:238 lists 'the lock card on a PIN-locked ColorOS lock screen' as a check on Raina's realme. Photo and voice messages and game moves do not change the card, so those would show only once.
- [verified] Chat notifications pile up. They are never removed when a message is read, and each message gets its own notification. A new message therefore sits beside an old one already read, and a quick burst shows as two or more notifications.
  - RisainaNotifier.kt:146-153 posts each message under messageNotificationId(m.id) (line 270), one notification per message. RisainaNotifier.kt:62-67 clearPartnerAlerts leaves chat in place on purpose. The only place chat notifications are cancelled is clearAllPartnerNotifications (lines 75-85), called only on sign-out, unlink and session expiry (AuthRepository.kt:128, CoupleRepository.kt:214). MessagesViewModel.kt:356-359 markRead cancels nothing; I grepped feature/messages and core/data. The live data has same-sender message bursts 1.6 s (emoji then text), 3.5 s, 6.5 s and 14.5 s apart, each with its own high-priority push. The comment at RisainaNotifier.kt:58-59 says messages stay 'until read or tapped', but nothing removes them on read.
- [possible] On Android 15+ and on ColorOS, the way notifications are grouped can make one item look like two. RisainaNotifier sets no group of its own, but AndroidX setSilent(true) puts the detailed notification into group 'silent'. That one can then sit outside the system's automatic 'Risaina' bundle while the other partner notifications stay inside it.
  - RisainaNotifier.kt:318-348 post() never calls setGroup, and line 333 calls setSilent(silent). For pings and messages, silent=true whenever FastAlerts.follows is true (lines 129 and 149). I disassembled androidx.core 1.19.1 (NotificationCompatBuilder, from the gradle cache) with javap. On API 26+, when mSilent is set and no group key exists, it calls Notification.Builder.setGroup("silent") and setGroupAlertBehavior(GROUP_ALERT_SUMMARY); NotificationCompat.GROUP_KEY_SILENT = "silent". The repo already saw the Android 15+ automatic bundle 'Aggregate_AlertingSection' on the emulator: the SosAlert.kt comment near line 84 and commit 86936e9 (live location and lock card given their own groups). How SystemUI or ColorOS actually draws the summary plus a summary-less 'silent' group is my inference; I have not seen it on a device.
- [possible] The detailed notification comes back after the person swipes away the quick alert. It reappears silently, which looks like 'it came again'.
  - RisainaMessagingService.kt:79-81 posts the quick alert, then lines 84-86 run a sync of up to 4 s that posts the detailed notification under the same id (RisainaNotifier.kt:107, 126-153). With the quick alert dismissed, notify() makes a new notification. onlyAlertOnce only applies to an update of a notification still showing; silent=true stops the buzz but not the post. Nothing checks whether the quick alert is still showing. The window is the sync time, at most 4 s plus the SyncWorker.
- [possible] Separate actions that share one fixed notification id each buzz. The shade shows one notification but the phone buzzes twice.
  - Games all use GAME_NOTIFICATION_ID 5050, and onlyAlertOnce is true only for 'moved' (GameNotificationText.kt:24). The live data has game.updated pairs from the same partner: chosen then done 5-30 s apart (2), created then moved under 5 s (1), created then finished 30 s-2 min (1). Pings: notifyPingArrivedFast posts on 4242 without onlyAlertOnce (RisainaNotifier.kt:210-215). The data has 6 pairs of pings from the same sender less than 2 min apart. The backend's per-action event emission is correct (games/service.ts: one done() call per action).
- [possible] A panic SOS can show two notices on the partner's phone: 'X is sharing their live location... They may be in trouble' and the SOS alarm. If Do Not Disturb mutes alarms, a DND ringer notification is added as well.
  - SosSender.kt:63-99 starts the panic share and the SOS at the same time. A share started before the SOS exists goes out at high priority (policy.ts:86-101), and SafetyNotifier posts it as PARTNER_LOCATION_ID 9210. The later share linked to the SOS is not announced (SafetyEvents.kt:88), but nothing cancels the notice already showing. SosAlarmService.kt:166-169 posts DND_RING_ID 9112 next to alarm 9111 when DND blocks alarms; that part is by design. In the data, the real couple had 4 sos.raised and 1 location.started sent.
- [possible] On Android 11 and below only, every push also shows WorkManager's foreground notification 'Catching up' for the expedited SyncWorker, next to the real notification.
  - SyncWorker.kt:115 sets setExpedited, and getForegroundInfo (line 74) builds a 'Catching up' notification. On API 30 and below, WorkManager runs expedited work as a foreground service and shows it. It applies only if one phone runs Android 11 or older; the backend does not record the Android version, so I could not check.
- [verified] Ruled out by the live data: duplicate device rows or a shared token, so that one push reaches one phone twice.
  - The real couple has 1 device row per member (created 2.2 and 3.6 days ago, both acked today), tokensSharedByMoreThanOneRow=0 (backed by the unique index token_unique, indexes.ts:69-73), and no row shares a phone model with a row of another user. One leftover row belongs to a user outside any active couple (test account, never acked), so the outbox never pushes to it. The upsert drops any other row holding the same token (devices/repo.ts:16-19) and is keyed by (userId, deviceId) (lines 20-33). Logout deletes the row (auth/repo.ts:168). One gap remains: a reinstall or clear data makes a new deviceId (SessionStore.kt:109-114; datastore is excluded from backup by backup_rules.xml), and the old row is removed only when FCM answers UNREGISTERED (outbox.ts:155-158). The data shows those rows already gone. The two phone builds, matched by hashing versionName candidates, are 86936e9 and 84b7e0e. Neither changes the notifier, the messaging service or the sync code relative to HEAD.
- [verified] Ruled out: the outbox retrying, a lease expiring mid-send, or one action producing two events or two pushes.
  - 277 events in 30 days for active couples. Of the 202 sent, 202 went out under 2 s, 0 have push.attempts > 1, 0 were sent more than 30 s (the lease) after creation, and 0 are pending now. messageIdsInMoreThanOneMessageCreated=0, pingIdsInMoreThanOnePingCreated=0, identicalMessagesWithin30s=0, sosWithRepush=0. In code, a retry happens only when no token succeeded (outbox.ts:167-169), the lease is atomic (lines 100-110), and shutdown waits for a send in progress (server.ts:76-96). Each module emits one event per action. The app also removes repeats: a ping counts as new only if pingDao has no row yet (EventReducer.kt:83-89), a message only if byLocalKey finds nothing (lines 100-102), the cursor check is at line 77, and the quick alerts check the database under postLock (RisainaNotifier.kt:209, 235). A push delivered twice could at most buzz twice; it cannot make a second notification. Backend tests outbox, devices-account, push-policy and push-data-fields pass (78/78).

## Current state
BACKEND
- Events and outbox: EventPublisher.emit (backend/src/services/events/publisher.ts:37-88) inserts one event with an embedded push record {state: pending, attempts: 0, leaseUntil} and wakes the OutboxWorker.
- OutboxWorker (backend/src/workers/outbox.ts):
  - leases events atomically (100-110);
  - resolves the other member and applies pushPolicyFor plus the recipient's preferences (112-130);
  - sends one sendEachForMulticast to every deviceTokens row of the recipient (132-150, 189-192);
  - deletes UNREGISTERED tokens (155-158);
  - records sent / skipped / failed, or retries with exponential backoff only when successCount == 0 (167-169, 183-187, 194-240).
  - The payload carries type, eventId, coupleId, seq and pr, plus ref (the message or ping id) and sosId/at for SOS (243-273).
- Push policy (backend/src/services/push/policy.ts):
  - high: ping.created, message.created, couple.unlinked, location.started, widget.updated and game actions that notify;
  - high and ignoring preferences: sos.*;
  - normal, collapse key 'sync', TTL 24 h: everything else;
  - not pushed: game 'answered'.
- SosRepushWorker (backend/src/workers/sos-repush.ts) sends sos.raised again with sosId, at most 6 times over 10 minutes, until the phone confirms. The claim is atomic.
- Devices:
  - PUT /devices upserts on (userId, deviceId) and first deletes any row holding the same token for another user or deviceId (backend/src/modules/devices/repo.ts:9-34).
  - Indexes: token_unique, and userId_deviceId_unique (backend/src/db/indexes.ts:69-73).
  - Rows are deleted by logout (auth/repo.ts:168), account deletion (me/repo.ts:56) and FCM UNREGISTERED. Nothing else removes stale rows.
  - Delivery health fields lastPushSentAt / lastPushAckAt exist (db/types.ts:239-257), and GET /devices/health lists rows without tokens.

ANDROID
- RisainaMessagingService (core/notifications/RisainaMessagingService.kt:49-100), in order:
  - SOS first;
  - recordPush, then enqueue the expedited SyncWorker;
  - quick alert: notifyPingArrivedFast(ref) or notifyMessageArrivedFast(ref);
  - syncOnce(notify=true) with a 4 s budget.
- RisainaNotifier (core/notifications/RisainaNotifier.kt):
  - Quick and detailed notifications share an id: ping 4242 for every ping; for messages, messageId.hashCode(), one per message.
  - A postLock plus a database check stops the quick alert from overwriting the detailed one. The detailed post uses onlyAlertOnce, and setSilent(true) when FastAlerts.follows (2-minute window).
  - Fixed ids: game 5050, widget 4345, unlink 9001, test 4344.
  - post() sets no group; setSilent adds group 'silent' (verified in androidx.core 1.19.1 bytecode).
  - clearPartnerAlerts (on app open) clears ping, game and widget, not chat. Chat is cleared only on sign-out or unlink.
- Sync:
  - EventSyncManager / SingleFlight / runPagedSync run one sync at a time and treat apply plus announce as one step that cannot be cancelled.
  - EventReducer skips events at or below the cursor. A ping or message counts as new only if it is not in Room yet.
  - The socket path (applySocketEvent) never posts notifications, and runs only while the app's own screens are visible (RisainaApp.kt:39-60).
- LockScreenCard (opt-in, id 7171, own group, ongoing, silent): updated after every widget snapshot recompute. It announces an unseen gift or ping; otherwise its line is the newest of the widget-board note and the partner's newest text or emoji chat message.
- SOS:
  - SosAlarmService foreground notification 9111 with a full-screen intent, own group;
  - DND ringer 9112 when DND blocks alarms;
  - respond 9116, missed 9113, safe 9114, not sent 9115.
  - SafetyNotifier 'is sharing' is 9210.
- SyncWorker.getForegroundInfo makes a 'Catching up' notification, used for expedited work on API 30 and below.

LIVE DATA (read-only; diagnostic scripts deleted afterwards)
- 2 active couples. couple0 is the real one; couple1 looks like a test couple (one member has no token, so 15 messages were skipped for no_tokens).
- couple0 has one device row per member, on builds 86936e9 and 84b7e0e. Both acked a push today.
- No retries, no duplicate events, no SOS re-pushes in 30 days.

## Design
Goal: one partner action gives exactly one visible notification and one buzz on the partner's phone. Most of the fix is on the phone; the backend changes are additive hardening and diagnostics.

ANDROID

A1. The lock-screen card never repeats something that has its own notification (main fix)
- Add a pure function LockCardContent.from(s: WidgetSnapshot, showing: ShowingAlerts). ShowingAlerts is (pingShowing, widgetShowing, messageShowing), read in LockScreenCard.update from NotificationManager.activeNotifications:
  - ping: id 4242;
  - widget: id 4345;
  - message: any active notification on MESSAGES or MESSAGES_QUIET.
- Rules:
  - (a) giftUnseen or pingUnseen announces only when the ping notification is not showing. That covers notifications off, the PINGS channel blocked, or the notification swiped away, which is what the card is for.
  - (b) The calm line uses the widget board only: board note, else mood, else the default text. It never uses a chat message.
    - Add boardLine and boardAtMillis to WidgetSnapshot, written by WidgetSnapshotUpdater from `board`. Keep latestNoteText for the home-screen widgets, which are not notifications.
  - (c) The card key is unchanged when only a chat message arrives, so shouldPost returns false and the card is not posted again.
- Ordering: runPagedSync posts notifications as events apply, before recompute (EventSyncManager.kt:150-168). So when the card updates, the new ping or message notification is already active. The push handler's quick alert is posted even earlier.
- When the person opens the app, the snapshot marks items seen and the card goes calm, as it does today. When the person dismisses the ping notification, the card does not start announcing until the next recompute, which is acceptable.

A2. One conversation notification for chat, cleared when read
- Replace one notification per message with a single MessagingStyle notification, MESSAGES_ID = 4343. It is rebuilt every time from Room: the partner's messages with readAt == null and deletedAt == null, the newest 6.
  - Private previews show 'New message' lines.
  - Type labels as today: photo, view-once, voice.
- A pure ConversationLines.build(unread, pendingQuickRefs, previews) makes the lines.
- The quick alert adds a placeholder line 'Sent you a message' for a ref that is not yet in Room, and alerts. The detailed post rebuilds from Room with setOnlyAlertOnce(true), so the placeholder becomes the real text in the same notification.
- A new message that had no quick alert still alerts: track the newest message id that has alerted, in DeliveryDiagnostics prefs.
- Remove or update the notification when:
  - MessagesViewModel.markRead and MessageRepository.markRead run, via notifier.refreshConversation();
  - the chat screen opens;
  - a message.deleted is applied;
  - the app's own message.read arrives from another device.
- Migration: on the first post, cancel any active notification on the MESSAGES channels whose id is not MESSAGES_ID. Those are leftovers from the per-message ids.
- Smaller interim fix if the conversation notification is too much for one round: keep the per-message ids, but cancel messageNotificationId(id) in markRead and on message.deleted, and cancel every MESSAGES-channel notification when the chat screen opens.

A3. The detailed notification after a quick alert: update or skip, never come back
- Add a pure FastAlerts.detailedAction(followsFast, showing): UPDATE when followsFast && showing, SKIP when followsFast && !showing, ALERT otherwise.
  - UPDATE posts with onlyAlertOnce=true and without setSilent. setSilent is what drops the notification into the AndroidX 'silent' group.
  - SKIP posts nothing. The person already swiped or tapped the quick alert.
- `showing` comes from activeNotifications for that id.
- Drop the `silent` parameter from RisainaNotifier.post for partner notifications.

A4. Explicit groups so Android 15+ and ColorOS do not build their own bundles
- Every partner notification gets a stable group of its own, the same pattern commit 86936e9 used for the live-location and lock-card notifications:
  - risaina_chat (one notification after A2);
  - risaina_ping;
  - risaina_game;
  - risaina_widget;
  - risaina_system (unlink, test).
- Each group holds one notification, so no summary is needed.
- Check on the API 36/37 emulator: `adb shell dumpsys notification --noredact | grep -E "pkg=com.risaina.app|groupKey|Aggregate"` should show no autogroup for com.risaina.app with a ping, a message and a game notification showing at once.

A5. Drop a push the phone has already handled
- RisainaMessagingService keeps a small list of the last 64 handled eventIds in SharedPreferences, written with apply(): HandledPushes.firstTime(eventId).
- A repeat (FCM redelivery, a stale second token, an outbox resend) skips the quick alert. It still records the ack and queues the sync, which costs nothing. The SOS already has its own de-dupe by sosId and is left alone.
- No backend change: every push already carries eventId (outbox.ts:141).

A6. Panic SOS
- In SafetyEvents location.started from the partner: when session.reason == 'panic' and session.sosId != null, call notifier.cancelPartnerSharing(). The alarm already covers the emergency, and policy.ts:86-101 sends the linked share at normal priority for exactly this reason.
- Also cancel it in onRaised when the alarm rings for a panic SOS.
- The DND ringer stays: it is the only way to make sound through DND.

A7. Android 11 and below
- Build the push SyncWorker request through a pure function pushRequestFor(sdkInt): expedited only on API 31 and up.
  - On API 30 and below, a plain one-time request with no foreground notification.
  - The push handler already syncs inline, so the worker is only a safety net there.

A8. Games (optional, lowest priority)
- alertOnce(action) is also true when notification 5050 is showing for the same game id and was posted less than 30 s ago. Track the last game id and time in the notifier's prefs. Actions from the partner on the same game in quick succession (chosen then done) then update without a second buzz.

A9. Delivery health card
- Show 'N phones registered on your account' from GET /devices/health `mine.size`, an existing field, and warn when N > 1, with a 'Remove the others' action that calls DELETE /devices/:deviceId (existing).
- No backend change.

BACKEND (only adds; nothing existing is removed or changes meaning)

B1. Prune dead duplicate device rows in CleanupWorker
- Delete a deviceTokens row only when all of these hold:
  - it was created more than 3 days ago;
  - lastPushSentAt is set and newer than lastPushAckAt, or lastPushAckAt is missing;
  - more than 3 days have passed since its lastPushAckAt (or since createdAt);
  - another row of the same user has lastPushAckAt within the last 24 h.
- It never deletes a user's only row, and never deletes rows of builds that do not ack while no sibling acks. App builds that do not ack only lose a row when the same user also has a phone that acks.
- The app registers again on every MainActivity create (MainActivity.kt:38), so a wrong prune heals itself.
- No new collection; only deletes.

B2. Optional: record the Android version
- Accept an optional `sdkInt` (int 21-99) on PUT /devices, store it as an optional field, and return `sdkInt` (nullable) in DeviceHealth.
- DeviceUpsertBody is .strict(), so this backend change must deploy before an app that sends the field. That is the normal order, since main auto-deploys.
- It answers the Android 11 question for A7 without asking the couple.

B3. Optional outbox hardening
- Store a random push.leaseId at lease time, and make record() match { _id, 'push.leaseId': leaseId }. A worker whose lease expired then cannot overwrite a newer result.
- The live data shows the problem never happened, so this is defensive only.
- The real protection against a push arriving twice is A5 on the phone.

EDGE CASES AND COMPATIBILITY
- Older app builds (v75 in review, the current debug APK) keep working unchanged against the backend changes: B1 only deletes rows under strict conditions, and B2 and B3 are additive.
- The new app needs no new push fields.
- Quiet hours: the conversation notification keeps choosing the QUIET channel as today. A notification posted again on another channel, when quiet hours start between two messages, is handled by posting under the same id.
- Notifications off: no partner notifications, so the lock card announces as the fallback.
- An SOS ringing over the lock screen does not count as the app being visible (AppVisibility), unchanged.

## File plan
- android/app/src/main/java/com/risaina/app/core/notifications/LockScreenCard.kt: LockCardContent.from takes ShowingAlerts. A gift or ping is announced only when ping notification 4242 is not showing. The calm line comes from the widget board only (new snapshot fields), never from chat. update() reads activeNotifications once and passes ShowingAlerts in.
- android/app/src/main/java/com/risaina/app/core/datastore/WidgetSnapshotStore.kt: Add boardLine and boardAtMillis to WidgetSnapshot and to the stored preferences keys.
- android/app/src/main/java/com/risaina/app/core/sync/WidgetSnapshotUpdater.kt: Write boardLine (board note, else mood) and boardAtMillis. Keep latestNoteText for the home-screen widgets.
- android/app/src/main/java/com/risaina/app/core/notifications/RisainaNotifier.kt: One MessagingStyle conversation notification (MESSAGES_ID), rebuilt from unread partner messages, plus refreshConversation() and cancel on read or delete. Quick alerts add a placeholder line to it. FastAlerts.detailedAction picks UPDATE (onlyAlertOnce, no setSilent), SKIP or ALERT. Explicit group per kind. Optional: game alert-once when the same game was notified within 30 s. Migration: cancel leftover per-message ids.
- android/app/src/main/java/com/risaina/app/core/notifications/DeliveryDiagnostics.kt: FastAlerts.detailedAction (pure). HandledPushes ring buffer of the last 64 eventIds (pure core plus a SharedPreferences wrapper). Remember the last message id that alerted and the last game notification (id, time).
- android/app/src/main/java/com/risaina/app/core/notifications/RisainaMessagingService.kt: If HandledPushes.firstTime(eventId) is false, skip the quick alert but still record the ack and queue the sync.
- android/app/src/main/java/com/risaina/app/core/notifications/ConversationLines.kt: New pure builder: lines for the conversation notification from unread messages, pending quick refs and the previews preference.
- android/app/src/main/java/com/risaina/app/feature/messages/MessagesViewModel.kt: markRead and opening the chat screen call notifier.refreshConversation(), which removes the conversation notification or rebuilds it from what is still unread.
- android/app/src/main/java/com/risaina/app/core/data/MessageRepository.kt: After markRead succeeds locally, refresh the conversation notification, so a read from Home or a widget also clears it.
- android/app/src/main/java/com/risaina/app/core/sync/EventReducer.kt: message.deleted and message.read sent by this user from another device call refreshConversation. This runs as a deferred effect after apply, not inside the apply lock's network-free section, and is local only.
- android/app/src/main/java/com/risaina/app/core/safety/SafetyEvents.kt: location.started from the partner with reason panic and an sosId cancels PARTNER_LOCATION_ID. onRaised for a panic SOS that rings cancels it too.
- android/app/src/main/java/com/risaina/app/core/sync/SyncWorker.kt: pushRequestFor(sdkInt): expedited only on API 31 and up, so Android 11 and below never show 'Catching up' on each push.
- android/app/src/main/java/com/risaina/app/feature/delivery/DeliveryHealthCard.kt: Show how many phones are registered (health.mine.size). When more than one, warn and offer to remove the others with the existing DELETE /devices/:deviceId.
- backend/src/workers/cleanup.ts: Add pruneDeadDuplicateDevices(): deletes deviceTokens rows that are pushed but have not acked for over 3 days, only while another row of the same user acked in the last 24 h. Log the count only.
- backend/src/modules/devices/schemas.ts: Optional: sdkInt (z.int().min(21).max(99).optional()) on DeviceUpsertBody, and sdkInt nullable on DeviceHealth.
- backend/src/modules/devices/repo.ts: Optional: store sdkInt when present ($set only when defined).
- backend/src/db/types.ts: Optional fields: DeviceTokenDoc.sdkInt?: number | null; EventPush.leaseId?: string | null (for B3).
- backend/src/workers/outbox.ts: Optional B3: set push.leaseId in lease() and match it in record(), so a worker whose lease expired cannot overwrite the result.
- docs/API.md: Document the optional sdkInt on PUT /devices and GET /devices/health, and the device-row pruning rule.
- docs/PROGRESS.md: Record the findings, the lock-card and conversation-notification behaviour changes, and the device checks below.

## Tests
- Android LockCardContentTest: an unseen ping while ping notification 4242 is showing gives the calm card, without 'reached out'.
- Android LockCardContentTest: an unseen ping or gift while no ping notification is showing still announces (fallback for notifications off or swiped).
- Android LockCardContentTest: a new text message from the partner leaves the card key unchanged (calm line from the board only), so shouldPost is false and the card is not posted again.
- Android LockCardContentTest: a widget-board note change while widget notification 4345 is showing still updates the calm line (board-owned) but never as an announcement.
- Android FastAlertsTest: detailedAction(followsFast=true, showing=true) gives UPDATE; (true, false) gives SKIP; (false, any) gives ALERT.
- Android NotifierPostTest (Robolectric, or a pure builder check): the notification built for UPDATE has FLAG_ONLY_ALERT_ONCE and no group 'silent' (Notification.getGroup() != "silent"), and every partner kind carries its own explicit group key.
- Android ConversationLinesTest: two unread messages give two lines in one notification. A pending quick ref not yet in Room gives one placeholder line, and once synced the same notification shows the real text. Previews off gives 'New message'. View-once photo and voice labels.
- Android ConversationLinesTest: after markRead of every message, build returns empty, so the notifier cancels MESSAGES_ID. Deleting a message removes its line.
- Android HandledPushesTest: the first eventId is new, the same one again is not, and the 65th distinct id pushes out the oldest. A blank or missing eventId always counts as new.
- Android SafetyEventsTest: location.started from the partner with reason panic and an sosId cancels PARTNER_LOCATION_ID; a manual share still announces.
- Android SyncWorkerEnqueueTest: pushRequestFor(30) is not expedited; pushRequestFor(31) and pushRequestFor(36) are expedited with RUN_AS_NON_EXPEDITED_WORK_REQUEST.
- Android GameNotificationTextTest (if A8 is done): the same game's second action within 30 s while 5050 is showing alerts once; a different game, or one more than 30 s later, alerts.
- Backend test/devices-prune.test.ts (new): two rows for one user, one acked an hour ago and one pushed for 4 days without an ack, gives the stale one deleted. A user's only row, never acked, is kept. Two rows where neither acks are both kept. A stale row younger than 3 days is kept. Rows of other users are untouched.
- Backend test/outbox.test.ts (new case): with tokens [live, failing-retryable], one success marks the event sent with attempts 1 and the next runOnce sends nothing (a retry never re-sends to a token that already got the push).
- Backend test/outbox.test.ts (B3, if done): two workers lease the same event after the lease expires, and record() from the stale lease leaves the newer state alone.
- Backend test/delivery-health.test.ts (B2, if done): PUT /devices with sdkInt stores it and GET /devices/health returns it; without sdkInt it returns null; an unknown extra field is still rejected (strict body unchanged otherwise).
- Device check (manual, both phones): with the lock card on, send a text, a ping, a gift and a widget update with Risaina closed. Before the fix you see two notifications each; after it, one. Use `adb shell dumpsys notification --noredact | grep -A3 'pkg=com.risaina.app'` to list ids and groups.
- Device check (Android 16 emulator and the realme): with a ping, a chat burst and a game notification showing, dumpsys shows no 'Aggregate' autogroup for com.risaina.app and one notification per kind.

## Risks
- Changing the lock card is a product decision. After A1 the card stops echoing new chat text and stays calm while a ping or gift notification is showing. If the card's purpose is to show the newest message on the lock screen, the alternative is to keep the card and mark message, ping and widget notifications VISIBILITY_SECRET while the card is on, which hides them from the lock screen only.
- The conversation notification (A2) changes ids, so per-message notifications posted by older builds would sit next to it. The migration must cancel active MESSAGES-channel notifications whose id is not MESSAGES_ID. MessagingStyle needs a Person for the partner and must respect previews off and VISIBILITY_PRIVATE.
- A2 relies on readAt being set locally when messages are read. Messages seen on Home or in a widget without being marked read keep the notification, which matches today's behaviour.
- On Android 16, single-notification groups without a summary may still be grouped together by the system. Check A4 on the API 36/37 emulator and on ColorOS before relying on it.
- A3's SKIP path depends on activeNotifications, which can fail or lag on some phone makers. If it throws, treat the notification as showing (UPDATE), never SKIP, so a notification is never lost.
- B1 deletes push rows. A wrong rule would silence a real phone until the app is next opened. The rule requires a sibling row that acks and three days without an ack, and only deletes, so it is reversible by opening the app.
- B2 adds a field to a .strict() request body. The backend must deploy before any app build sends sdkInt (main auto-deploys, so merge the backend first). Older servers would answer 400.
- The FCM handler has only a few seconds. A5's SharedPreferences read and apply() write must stay cheap and must never block the SOS path, which runs first.

## Open questions
- Is the opt-in lock-screen card turned on, and on which phone (Settings, Lock-screen card)? A screenshot of the shade or lock screen after one ping or one message would settle it in seconds. If the second notification says '... reached out · tap to send one back', or repeats the message under the partner's name, it is the card.
- Does 'twice' mean two notifications at the same time, the same notification coming back after it was swiped, or two buzzes on one notification? Each points to a different cause: the card or old chat notifications, A3, or games and pings sharing one id.
- Which actions show it: text messages, pings, gifts, widget updates, games, SOS? Photo and voice messages and game moves never touch the card, so if those also show twice, the card is not the cause.
- What Android version is Raina's realme on? On Android 11 or below, every push also shows the 'Catching up' notification (A7). B2 would make this visible in Delivery health.
- Should the lock card keep showing new items at all once a normal notification exists (A1 as proposed), or should it hide the normal notifications from the lock screen instead?
- Is changing chat to one conversation notification (A2) acceptable, or should this round only cancel per-message notifications on read and when the chat opens?
