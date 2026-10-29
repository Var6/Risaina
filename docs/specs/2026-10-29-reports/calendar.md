# calendar

## Summary
I recommend a new shared couple calendar stored in a new `calendarEntries` collection, and not an extension of milestones. The live `milestones` validator has a strict `kind` enum (anniversary/meeting/custom) that the Atlas user cannot widen without collMod (validators.ts:330-345, 610-623). The milestone shape also has no time, repeat, reminders or source link. Milestones stay as the "Our story" timeline. The calendar shows them read-only, together with three items worked out on the phone from data the app already has: the anniversary (couple.relationshipStartDate), both birthdays (user/partner `birthday`) and the next visit (couple.nextMeetingDate). That way no date is stored twice. Today this derivation is copied in TimelineViewModel.kt:43-60 and HomeViewModel.kt:188-190, and it should move into one domain function. The app has no scheduled local notifications yet. AlarmManager is used only for the SOS alarm-clock retry (SosAlarmController.kt:138-155), and the RITUALS channel is declared but never used. Reminders should be local on both phones: one "next due reminder" AlarmManager alarm using `setExactAndAllowWhileIdle` when exact alarms are allowed, and `setAndAllowWhileIdle` otherwise. It is set again when the app starts, after sync, on boot, on time or time-zone change and when the exact-alarm permission changes. A Room log of fired reminders stops a reminder showing twice. This needs no new permission: SCHEDULE_EXACT_ALARM is already declared, and RECEIVE_BOOT_COMPLETED is already in the merged manifest through WorkManager (verified in the release merged manifest, line 62). On the backend, three new event types (`calendar.created/updated/deleted`) use the existing default silent (normal) push. A push goes out at high priority only when the creator asked to tell the partner, and the new app then posts a visible "added to your calendar" notification. For backward compatibility, ship a "tolerant reader" commit first that makes `Event.type` a plain string in the GET /events response. Otherwise a rollback would make GET /events fail on stored calendar events. The shipped app (v75) already ignores unknown event types (EventReducer.kt:80-166), and the push-policy unit tests pass (59/59, run here).

## Root causes
- [verified] There is no calendar, to-do with a date, or reminder feature today. The closest thing is milestones, which hold only title + date (YYYY-MM-DD) + kind ∈ {anniversary, meeting, custom}, with no time, repeat, reminders or source link.
  - backend/src/db/types.ts:274-287 (MilestoneDoc), backend/src/modules/milestones/schemas.ts:4-18, android core/model/Models.kt:140-153, feature/timeline/TimelineScreen.kt:172-204 (chips Yearly/Visit/Once).
- [verified] Milestones cannot be extended with new kinds such as 'event' or 'todo'. The live validator has a strict kind enum, and the Atlas DB user cannot run collMod, so a new collection (created at boot with createCollection, which readWrite allows) is the only safe home.
  - backend/src/db/validators.ts:330-345 (kind: { enum: [...MILESTONE_KINDS] }), :610-623 (collMod refused → validatorWarnings, keep old validator); docs/PROGRESS.md:135; the same reasoning was used for games (docs/API.md:526-534).
- [verified] The data for the default reminders already exists and is synced: couple.relationshipStartDate (YYYY-MM-DD), the user's and partner's birthday ('YYYY-MM-DD' or '--MM-DD'), the partner's timeZone (null unless they share local time) and couple.nextMeetingDate. The derivation is copied in several places with slightly different Feb-29 handling.
  - backend/src/modules/shared/schemas.ts:111-129,151-158; android TimelineViewModel.kt:43-60 (uses CountdownCalculator.nextAnniversary, Countdown.kt:29-33, withYear then plusYears, so Feb 29 → Feb 28 even in leap years), HomeViewModel.kt:188-190 and Occasions.kt:39-59 (re-clamps each year).
- [verified] The app schedules no local notifications today. AlarmManager is used only for the SOS alarm-clock retry, WorkManager only for sync, SOS, location and device registration, and the RITUALS channel ('Optional good-morning and goodnight reminders') is declared but unused.
  - grep for AlarmManager/setExact/PeriodicWorkRequest: SosAlarmController.kt:134-155, SyncWorker.kt:104-146, StopSharingWorker.kt:53-58, SosRetryWorker/SosReceiptWorker, DeviceTokenRegistrar.kt:78-82; NotificationChannels.kt:22,72-76 with no other RITUALS reference.
- [verified] No new permission or Play declaration is needed for local reminders. SCHEDULE_EXACT_ALARM is declared and there is already a UI path to grant it. RECEIVE_BOOT_COMPLETED is already in the shipped merged manifest (it comes from WorkManager), and WorkManager's RescheduleReceiver shows that a non-exported receiver gets BOOT_COMPLETED.
  - android/app/src/main/AndroidManifest.xml:22-31; core/safety/SosReadiness.kt:167,184 (canScheduleExactAlarms, ACTION_REQUEST_SCHEDULE_EXACT_ALARM); app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml:62 (RECEIVE_BOOT_COMPLETED) and :416-421 (RescheduleReceiver exported=false with BOOT_COMPLETED).
- [verified] New event types are safe for the shipped app (v75). The reducer ignores unknown types and still moves its cursor forward, the push handler only syncs for unknown types, and the server pushes unknown types silently at normal priority by default.
  - android core/sync/EventReducer.kt:80-166 (no else branch; setLastEventSeq at :166); core/notifications/RisainaMessagingService.kt:48-90; backend/src/services/push/policy.ts:99-112; backend/test/push-policy.test.ts row 'something.new' → normal (ran: 59/59 passed).
- [likely] GET /events checks every event's type against z.enum(EVENT_TYPES) when it builds the response. After a backend rollback, any stored calendar.* event would make GET /events fail (500) for that couple. The games stream hit the same class of problem and fixed it with a tolerant reader.
  - backend/src/modules/shared/schemas.ts:618-630 (Event.type: EventType enum), modules/events/schemas.ts:9 (EventsResponse uses Event), comment at shared/schemas.ts:483-486 about enum-in-response → 500.
- [possible] The live `events` collection may still have an old strict type enum. In that case new calendar.* events would only be delivered live over the WebSocket: not stored, not replayed, not pushed. The client must therefore also refresh GET /calendar on its own schedule.
  - backend/src/services/events/publisher.ts:62-80 (code 121 → live-only), db/index.ts:21-27 (boot log), docs/PROGRESS.md:94 and docs/GO_LIVE.md Step 0 (dbAdmin not granted at the time). Against this: SOS pushes were verified against the live server (PROGRESS.md 'Google Play readiness'), which suggests newer types are stored.
- [verified] Adding Room tables needs a real migration. The database is at version 5 with fallbackToDestructiveMigration, which would wipe the outbox of unsent messages and the caches. Schemas 1-5 are exported, so a Room AutoMigration 5→6 is possible.
  - android core/database/RisainaDatabase.kt:15-47; android/app/schemas/.../1.json…5.json; app/build.gradle.kts:129-130 (room.schemaDirectory); room 2.8.5 and room-testing present (libs.versions.toml:14, build.gradle.kts:220).
- [likely] Due dates must not be stored on list items. PATCH /lists replaces the whole items array, so an older app that edits a list would silently drop any new item field. Calendar entries should link to list items instead.
  - backend/src/modules/lists/schemas.ts:33-58 ('Replaces the whole items array'; PatchItem allows only id/text/done); android ListsScreens.kt:180-197 sends rows as a whole.
- [verified] The chat has no message action menu. Long-press only offers delete, and only for your own sent messages. The composer holds Photo, 🎲, the text field and Mic/Send, which leaves little width for more buttons.
  - android feature/messages/MessagesScreen.kt:233 (onLongPress only if mine && SENT), :380-405 (Composer).
- [verified] The countdown widget shows nextMeetingDate, otherwise the nearest future milestone. It has no other source of dates.
  - android core/sync/WidgetSnapshotUpdater.kt:161-170.

## Current state
BACKEND (Fastify/Zod/Mongo)
- Milestones: backend/src/modules/milestones/{routes,service,repo,schemas}.ts provide POST/GET/PATCH/DELETE /milestones. The doc is {coupleId, createdBy, title ≤120, date YYYY-MM-DD, kind anniversary|meeting|custom, visibility 'shared'} (db/types.ts:274-287), capped at 500 per couple (repo.ts:5). Events are milestone.created/updated {milestone} and milestone.deleted {milestoneId}. Validator: db/validators.ts:330-345 (strict kind enum). Index: coupleId_date (db/indexes.ts:87).
- Couple: relationshipStartDate (YYYY-MM-DD), nextMeetingDate (Date, ISO on the wire) and nextMeetingLabel (db/types.ts:124-146; shared/schemas.ts:151-173), set with PATCH /couple (modules/couples/schemas.ts:39-40, service.ts:38-40). It emits couple.updated.
- Users: birthday ('YYYY-MM-DD' or '--MM-DD'), timeZone (IANA) and shareLocalTime. The partner sees timeZone only when shared (shared/schemas.ts:97-131; lib/time.ts isValidBirthday/isValidTimeZone; ProfileTimeZone rejects fixed offsets, schemas.ts:49-54).
- Events/outbox: EventPublisher.emit allocates a seq, inserts the event, publishes over WS and wakes the outbox. It falls back to live-only on validator code 121 (services/events/publisher.ts). EVENT_TYPES is at db/types.ts:289-319, and the events validator uses a dotted pattern (validators.ts:574-600). Push policy is services/push/policy.ts: HIGH only for visible types, BYPASS for sos.raised/resolved, NORMAL (collapseKey 'sync', TTL 24 h) for everything else including unknown types. The outbox is workers/outbox.ts. backend/test/push-policy.test.ts requires every EVENT_TYPE to appear in its table (:177-180) and every HIGH type to be in CLIENT_NOTIFIES (:102-120).
- Lists (notes/checklists) are the model to copy for a shared editable document: version + expectedVersion, a per-user write rate limiter (modules/lists/routes.ts:15-34, env RATE_LIMIT_LIST_WRITES_PER_MIN default 120), and item ids that are 12-char server strings.
- Unlink purge: modules/couples/purge.ts:76-90 deletes per-collection by coupleId. Export: modules/me/service.ts:155-193. Legal copy: public/legal/privacy.html:101,131 and delete-account.html:107 mention "dates and countdowns".

ANDROID
- Timeline: feature/timeline/TimelineScreen.kt and TimelineViewModel.kt. It has the together hero, next meeting, a "Coming up" list built from meeting, anniversary, both birthdays and milestones, "Already happened", and the dialogs. It is reachable from the Home quick tile "Timeline" (HomeScreen.kt:360) and from Settings.
- Domain: core/domain/Occasions.kt (Birthday parsing, nextBirthday with Feb 29→28, anniversary, partnerClock with "tomorrow for them") and Countdown.kt.
- Home: hero chips for the meeting countdown, anniversary days, partner birthday within 60 days and own birthday today (HomeScreen.kt:205-216). There are 8 quick tiles in 2 rows (HomeScreen.kt:354-368), and HomeNav is at HomeScreen.kt:84-101.
- Room is at v5: milestones table (Entities.kt:75-85, Daos.kt:142-158), lists, couple, partner and outbox. clearAllContent runs on sign-out/unlink (RisainaDatabase.kt:34-37).
- Sync: EventReducer.kt (string `when`, unknown types ignored), EventSyncManager.kt (socket while visible, paged sync, widget recompute), SyncWorker.kt (push-triggered, on-open and hourly periodic with a network constraint), and RisainaApp.onCreate (channels, foreground watcher, safety).
- Notifications: NotificationChannels.kt has pings, messages (and their quiet variants), rituals (unused), system, SOS and location. RisainaNotifier.kt posts from Incoming items, honours the master switch, preview privacy and quiet hours (QuietHoursPolicy), uses VISIBILITY_PRIVATE plus a public version, and clears partner channels on purge (clearedOnPurge, tested in NotifierPurgeTest).
- Alarms: only SosAlarmController.scheduleAlarmClock (needs canScheduleExactAlarms on API 31+). SosReadiness already links to ACTION_REQUEST_SCHEDULE_EXACT_ALARM.
- Messages: the composer has photo, 🎲 game picker, text and mic/send. Long-press means delete-own-message only. Games are merged into the chat timeline from their own collection.
- Lists: ListDetailScreen edits the note body (≤10k) or checklist rows (Checkbox, TextField, remove).
- Widgets: the countdown (and "Us") widget reads nextMeetingDate, otherwise the next milestone (WidgetSnapshotUpdater.kt:161-170).
- Navigation: type-safe routes (navigation/Routes.kt), with deep links risaina://app/... (RisainaNavHost.kt).
- Manifest: SCHEDULE_EXACT_ALARM, USE_FULL_SCREEN_INTENT, specialUse FGS (SOS) and location FGS. RECEIVE_BOOT_COMPLETED is present in the merged manifest via WorkManager.

## Design
1. DECISION: NEW COLLECTION, MILESTONES STAY THE TIMELINE
- Add a `calendarEntries` collection for everything the couple schedules: events and to-dos with a date.
- Do not migrate or copy milestones, the anniversary, birthdays or the next visit into it. The calendar READS them:
  - anniversary-kind milestones become yearly items;
  - future meeting/custom milestones become one-off items;
  - all of them are read-only in the calendar, and tapping one opens the existing milestone/meeting dialogs.
- Skip a milestone of kind anniversary whose date equals relationshipStartDate, so the anniversary never shows twice.
- Reasons:
  - The live milestones `kind` enum cannot change (no collMod).
  - Milestones have no time, repeat, reminders, done state or source link.
  - Old apps render milestones with fixed meanings (Yearly/Visit/Once).

2. DATA MODEL
Mongo `calendarEntries` (CalendarEntryDoc, db/types.ts):
- `_id`, `coupleId`, `createdBy`, `updatedBy`
- `kind`: 'event' | 'todo'
- `title`: 1–120
- `notes`: ≤2000, or null
- `emoji`: 1–2 graphemes, or null
- `date`: 'YYYY-MM-DD'. The first or only occurrence; for a to-do, the due date.
- `endDate`: 'YYYY-MM-DD' or null. All-day only; ≥ date and ≤ date + 366 (trips and visits).
- `time`: 'HH:mm' or null. Null means all-day.
- `durationMinutes`: 1–10080 or null. Timed only.
- `timeZone`: IANA or null. Only with time; see section 9. It is the anchor zone; null means "each phone's own clock".
- `repeat`: 'none' | 'weekly' | 'monthly' | 'yearly'
- `repeatUntil`: 'YYYY-MM-DD' or null
- `reminders`: int[]. Minutes before, 0..40320, at most 4, unique, sorted. For all-day entries they count back from each phone's local "all-day reminder time" on the date, so 0 = on the day at 09:00 and 1440 = the day before at 09:00.
- `remindUserIds`: ObjectId[]. A subset of members, default both. Only these phones remind. This doubles as the to-do assignee.
- `pinned`: bool. Shows the countdown on Home and is the widget fallback.
- `doneAt`, `doneBy`: to-do only.
- `source`: { kind: 'message' | 'list' | 'list_item', messageId?, listId?, itemId? } or null. Only ids, never copied text, so deleting a message removes its text everywhere; the client shows "from a deleted message".
- `clientKey`: UUID v4, or null. For idempotent create.
- `version`: int ≥ 1, +1 per change.
- `createdAt`, `updatedAt`
- Hard delete, as milestones use.

Validator (created with createCollection at boot):
- Required: coupleId, createdBy, updatedBy, kind, title, date, repeat, reminders, remindUserIds, version, createdAt, updatedAt.
- `kind` and `repeat` are bsonType string with a pattern `^[a-z_]{1,20}$`, NOT enums, so a future value never needs collMod. Zod remains the allow-list.
- No additionalProperties restriction, so optional fields can be added later.

Indexes:
- {coupleId:1, date:1}
- unique partial {coupleId:1, clientKey:1} where clientKey is a string
- {coupleId:1, 'source.messageId':1}, partial, for "is this message in the calendar"

DTO `CalendarEntry` (shared/schemas.ts):
- Every field above, with ids as strings.
- `kind` and `repeat` are plain z.string() in RESPONSES (tolerant reader, as games kinds are) and enums in requests.
- `done: boolean` is derived from doneAt.
- Cap of 1000 entries per couple (409 conflict).

3. ENDPOINTS (all Bearer + requireCouple; writes share a per-user limiter `calendar:${userId}`, new env RATE_LIMIT_CALENDAR_WRITES_PER_MIN default 120)
- GET /calendar → 200 {items: CalendarEntry[]}. All entries, sorted by date; small enough to replace the local table on refresh.
- POST /calendar → 201 {entry}. A repeated clientKey returns 200 with the existing entry.
  - Body: {kind, title, notes?, emoji?, date, endDate?, time?, durationMinutes?, timeZone?, repeat?='none', repeatUntil?, reminders?=[], remindUserIds?=both, pinned?=false, source?, clientKey?, notifyPartner?=false}.
  - Validation, 400 on failure:
    - timeZone requires time, and is checked with ProfileTimeZone (IANA only);
    - endDate requires all-day; durationMinutes requires time;
    - a to-do must have repeat 'none';
    - repeatUntil must be ≥ date;
    - remindUserIds must be couple members;
    - source.messageId must exist in this couple (any type except view-once), and source.listId must be a non-deleted list of the couple (else 404).
- PATCH /calendar/:id → {entry}.
  - Same fields, partial, plus done?: boolean (to-do only; 400 'not_a_todo' otherwise), expectedVersion? and notifyPartner?.
  - A mismatched expectedVersion returns 409 version_conflict with details.entry, as lists do.
  - Without expectedVersion it uses a compare-and-set retry loop (lists/service.ts pattern).
- DELETE /calendar/:id → 204.
- Additive changes elsewhere:
  - GET /me/export gains `calendar: CalendarEntry[]`, counted in `truncated`.
  - CouplePurger.purgeData deletes calendarEntries by coupleId (purge.ts:77-90). This also covers account deletion and the cleanup worker's re-run.
- No server-side occurrence computation: the server never needs to know when a repeat falls.

4. EVENTS AND PUSH POLICY
- New EVENT_TYPES:
  - `calendar.created` {entry, notify}
  - `calendar.updated` {entry, notify}. Also used for done/undone.
  - `calendar.deleted` {entryId}
- `notify` is true only when the request carried notifyPartner: true. The app defaults it to on for a new entry and for an edit that moves date or time.
- policy.ts: `if ((type==='calendar.created'||type==='calendar.updated') && payload?.notify===true) return HIGH`. Everything else falls to the NORMAL default (silent "sync now", collapse 'sync', 24 h TTL).
  - Recipient notifications off → quietPolicy already turns HIGH into NORMAL.
  - No bypass.
  - No `ref` field is needed; the sync is fast.
- The new app turns a notifying event from the partner into Incoming.CalendarShared and posts "Raina added 'Dinner with her parents' · Fri 14 Nov, 8:00 pm" (or "moved … to …") on a new "Calendar updates" channel (DEFAULT). During quiet hours it posts on MESSAGES_QUIET, as game notices do.
  - preview off: "Raina added something to your calendar".
  - Done toggles and deletes stay silent.
- Tests:
  - push-policy.test.ts: rows for all three types, both payload variants, and 'calendar.created'/'calendar.updated' added to CLIENT_NOTIFIES.
  - profile-lists.test.ts already checks the events pattern against EVENT_TYPES; 'calendar.created' matches it.

5. DEFAULT REMINDERS, DERIVED AND NEVER STORED
One pure function, `core/domain/CalendarOccasions.derive(couple, me, partner, milestones)`, returns virtual items with stable keys. Timeline, Home, Calendar and the reminder planner all use it, which replaces the copies in TimelineViewModel.kt:45-59 and HomeViewModel.kt:188-190.
- `occ:anniversary`: yearly from relationshipStartDate. Title "Our {n}th anniversary".
- `occ:birthday:{partnerId}`: yearly from partner.birthday ('--MM-DD' means no age). "Raina's birthday · turning 27".
- `occ:birthday:{myId}`: "Your birthday".
- `occ:meeting`: one-off from nextMeetingDate, labelled with nextMeetingLabel.
- `occ:milestone:{id}`: yearly for kind anniversary, one-off for future meeting/custom.
- Feb 29 follows Occasions.nextBirthday: Feb 28 in non-leap years, then back to Feb 29.

Reminder settings for occasions are personal and kept per device in DataStore (ReminderSettingsStore), because only this phone acts on them. They are not synced and not stored on the server.
- anniversary: ON, 7 days before + on the day
- partner birthday: ON, 7 days + 1 day + on the day. Optional "also when it turns midnight where they are", offered only when partner.timeZone is shared; default off.
- my birthday: OFF
- next visit: OFF (1 day before when on)
- yearly milestones: OFF
- all-day reminder time: 09:00
- master "Calendar reminders": ON

Editing an occasion goes back to its owner field, so nothing is duplicated:
- the anniversary opens the existing date dialog → PATCH /couple;
- my birthday opens Profile;
- the partner's birthday is read-only.

6. LOCAL REMINDER SCHEDULING ON BOTH PHONES (Doze-safe, no new restricted permission)
Pure planner: core/domain/ReminderPlanner.kt.
- Inputs: entries (Room), occasions, settings, my userId, device zone, now.
- Output: the reminders due now, and the next fire instant within a 400-day horizon.
- Occurrence keys: "{entryId|occ key}|{occurrence date}|{offset}".

Rules:
- Skip entries where remindUserIds doesn't include me, done to-dos, and occurrences after repeatUntil.
- Skip a reminder whose fire time is earlier than the entry's updatedAt, so creating an entry 10 minutes ahead does not fire a stale "1 hour before".
- Skip a reminder whose fire time is earlier than `remindersSince`, the first-enable time in DataStore, so a new install does not get a burst of old reminders.
- Catch-up when the phone was off or the alarm was late: post if now − fireAt ≤ 6 h, or if it is an all-day "on the day" reminder and it is still that local day before 23:00. Otherwise drop it silently.

Engine: core/reminders/ReminderScheduler.
- Keeps exactly ONE pending alarm, the next due reminder: requestCode constant, PendingIntent.getBroadcast to ReminderAlarmReceiver, FLAG_IMMUTABLE|FLAG_UPDATE_CURRENT. That gives a single chain, far from the per-app alarm cap.
- API < 31: setExactAndAllowWhileIdle, which needs no permission there.
- API ≥ 31 with canScheduleExactAlarms(): setExactAndAllowWhileIdle, wrapped in try/catch for SecurityException.
- Otherwise: setAndAllowWhileIdle. It is inexact; AOSP caps one-shot inexact alarms at about 1 hour late on Android 12+ (inferred from AlarmManagerService.maxTriggerTime, to be checked on a device).
- *AllowWhileIdle alarms fire in Doze but are rate-limited to roughly one per 9 minutes per app. The chain posts every due reminder in one go, so that limit does not matter.
- Never use setAlarmClock: it shows an alarm icon and the next alarm on the lock screen.
- Never use a full-screen intent or a foreground service for reminders.

ReminderAlarmReceiver (@AndroidEntryPoint, exported=false):
- goAsync().
- Under a process-wide Mutex: run the planner, claim each key with `INSERT OR IGNORE` into reminder_log (a key is only posted when the insert succeeds, which gives exactly-once per phone across alarm, worker and app start), post the notifications, schedule the next alarm.
- Room only, no network, within about 8 s.

Re-arm triggers, debounced 2 s, cheap and local:
- RisainaApp.onCreate. Every FCM push and app open starts the process, so OEM swipe-kills heal on the next push.
- After a sync that applied calendar.*, couple.updated, partner.updated or a profile change.
- After CalendarRepository writes and refresh.
- At the end of SyncWorker (hourly).
- ReminderRescheduleReceiver (exported=false) for BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED and AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED. TIME_SET and TIMEZONE_CHANGED are among Android's implicit-broadcast exemptions; the rest are explicit.
- Declare RECEIVE_BOOT_COMPLETED explicitly in the app manifest for clarity. It is already present via WorkManager, so this changes nothing for Play.

Notifications:
- New channels:
  - `reminders` "Reminders": HIGH, sound, heads-up.
  - `reminders_quiet` "Reminders (quiet hours)": LOW, silent. Used when QuietHoursPolicy says quiet, e.g. a partner-made event that falls in my night.
  - `calendar_updates` "Calendar updates": DEFAULT.
- They respect the in-app master notificationsEnabled switch, the new "Calendar reminders" switch and preview privacy ("Reminder" / "Tap to see").
- VISIBILITY_PRIVATE with a public version "Risaina · Reminder".
- Their own group key, so Android auto-grouping cannot hide them; this was learned with the location and lock card notifications.
- Notification id = key.hashCode(), tag 'reminder'.
- Text examples: "Tomorrow · Raina's birthday 🎂 (turning 27)", "In 1 hour · Call Raina · 9:00 pm her time (2:30 pm for you)".
- Tapping opens risaina://app/calendar/{entryId}?on=YYYY-MM-DD.
- To-do action "Done": a BroadcastReceiver marks Room optimistically and enqueues CalendarDoneWorker (network constraint) for PATCH {done: true}.
- Sign-out, unlink and account deletion: cancel the alarm, cancel the reminder/calendar notifications (add the three channels to RisainaNotifier.PARTNER_CHANNELS / clearedOnPurge), clear the calendar_entries and reminder_log tables.

Exact-alarm prompt:
- When the user saves a timed reminder and exact alarms are not allowed, show the inline hint "Reminders can arrive up to an hour late. Allow 'Alarms & reminders' for on-time reminders", with a button reusing ReadinessFix.EXACT_ALARMS.
- The same status line appears in the Calendar reminder settings.

7. ANDROID DATA LAYER AND SYNC
- Dtos.kt/RisainaApi.kt: CalendarEntryDto (kind/repeat as String), CalendarSourceDto, CreateCalendarRequest, PatchCalendarRequest, responses; calendar(), createCalendar(), patchCalendar(), deleteCalendar().
- Room v6 with `autoMigrations = [AutoMigration(from = 5, to = 6)]` (two new tables only; keep the destructive fallback for older versions):
  - CalendarEntryEntity: flat; reminders and remindUserIds as comma strings; sourceKind/sourceMessageId/sourceListId/sourceItemId columns; indices date and sourceMessageId.
  - ReminderLogEntity(key PK, firedAt). Pruned after 45 days.
- CalendarDao: all() Flow, byId, forMessages(ids) Flow, forList(listId) Flow, upsert, replaceAll (transaction), delete, clear.
- CalendarRepository:
  - refresh() calls GET /calendar and replaceAll. A 404 from an older server means the feature is unavailable; hide the entry points.
  - create/update/delete/setDone: write Room from the response, map 409 version_conflict to reload plus a "Raina changed this meanwhile" snackbar, and request a re-arm.
  - Writes need a connection, as lists do; clientKey makes retries safe.
- EventReducer:
  - "calendar.created"/"calendar.updated" → upsert entity;
  - "calendar.deleted" → delete;
  - notify == true from the partner, not a replay → Incoming.CalendarShared;
  - any calendar/couple/partner event → deferred re-arm request (local, after notifications, as widget work is).
- Refresh triggers, which also cover a stale events validator: Calendar screen open, app foreground (with the existing on-open SyncWorker), and once per hourly SyncWorker run (one small GET).

8. UI
Navigation:
- Routes `Calendar`, `CalendarDay(date)` (optional) and `CalendarEntry(id: String? = null, title/date/time/sourceKind/sourceMessageId/sourceListId/sourceItemId prefill: String? = null)`.
- Deep links risaina://app/calendar and risaina://app/calendar/{id}.
- The Timeline stays as "Our story", reachable from the Calendar top bar and from Settings.

Calendar screen (feature/calendar/CalendarScreen.kt + CalendarViewModel.kt):
- Top bar: Today, Reminder settings (sheet), "Our story" (Timeline). FAB "+".
- Two tabs, Upcoming (default) and Month; the choice is remembered in SavedState.
- Upcoming: sections Overdue to-dos, Today, Tomorrow, This week, This month, Later (12 months; each repeating item appears once with "every year").
  - Row: emoji or colour dot, title, local date/time, a second line with the partner's local time when zones differ, "added by Raina", countdown ("in 12 days"), a bell icon when it reminds me, and a checkbox for to-dos.
  - Occasions use a heart dot and open their owner dialogs.
- Month: header with month name and arrows plus a HorizontalPager swipe; a 7-column grid using WeekFields.of(Locale.getDefault()).firstDayOfWeek; up to 3 dots per day (occasion, event, to-do); today ringed; the selected day's agenda below.
  - Occurrences are expanded only for the visible 6-week window (≤1000 entries, cheap). No calendar library; a custom grid fits the design system.

Editor (CalendarEntryScreen, full screen):
- Event / To-do segmented control
- Title, emoji
- Date, with an end date for all-day multi-day
- All-day switch; time; optional duration
- Time zone row, only when timed and the partner shares a different zone: "My time" / "Raina's time" / "Each our own clock". It shows a live conversion such as "9:00 pm Raina's time = 2:30 am Sat for you ⚠", with a warning when either side falls between 23:00 and 07:00.
- Repeat: none / weekly / monthly / yearly, plus until
- Reminder chips. Timed: at time, 10 min, 1 h, 1 day, 1 week. All-day: on the day, 1 day, 3 days, 1 week.
- "Remind": both / me / Raina (for to-dos: "Who does it").
- Pin to Home, notes
- "Let Raina know" switch
- Source chip ("From chat: '…'" or "From note 'Trip'") that jumps back
- Delete

Home:
- The QuickRow "Timeline" tile becomes "Calendar" (CalendarIcon already in feature/common/Icons.kt:58).
- The hero chips (meeting, anniversary, birthday) become tappable and open Calendar → Upcoming.
- A new "Coming up" card under the hero shows stored entries in the next 7 days plus to-dos due or overdue, at most 3, with checkboxes. Occasions are left out because the chips already show them. It is hidden when empty.
- Pinned entries add a chip "12 days · Goa trip".
- Optional: WidgetSnapshotUpdater falls back to the next pinned entry after nextMeetingDate and before milestones.

Messages:
- Long-press on any TEXT message (mine or Raina's) opens a sheet: "Add to calendar", "Make it a to-do", "Copy", "Delete" (only mine and SENT, keeping the current dialog). The editor is prefilled with the message text as title and the date/time from DateMentions, with source {message}.
- Composer: replace the 🎲 button with a "+" that opens a small sheet: 🎲 Play a game · 📅 Add to calendar · ✅ Add a to-do. It uses the draft text when there is one, and keeps the composer width unchanged.
- When the draft contains a detectable date, a suggestion chip appears above the input: "📅 Fri 14 Nov, 8:00 pm · Add to calendar".
- Under any bubble that has calendar entries (CalendarDao.forMessages), a small chip "📅 In your calendar · Fri 14 Nov" opens the entry. Both partners see it because entries are shared. This gives in-chat visibility without a new message type; the messages validator could not take one.
- "Make it a to-do" with no due date adds the text to a pinned shared checklist "To-dos" (created if missing) through the existing lists API. With a due date it creates a calendar entry of kind todo.

Lists and notes, the "pick dates from notes" request:
- core/domain/DateMentions.kt is a pure, unit-tested parser. It returns spans with LocalDate and an optional LocalTime and covers:
  - ISO, dd/mm(/yyyy) or mm/dd according to the locale (en-IN → day first);
  - "14 Nov", "14th November", "Nov 14";
  - today, tomorrow, day after tomorrow, weekday names, "next Fri";
  - times "8pm", "8:30 pm", "20:30".
- Suggestions only, never automatic creation. Fractions like "1/2" are rejected (plausible day and month ranges, nearest future year).
- Note body: under the text field, a "Dates in this note" chip row (debounced): "📅 Sat 14 Nov · Add". Title = the sentence or line around the date (≤120). Source {list, listId}. A chip shows "✓ Added" when an entry from this list on that date exists.
- Checklist rows: a small CalendarIcon appears when the row has a date, or always via long-press. Source {list_item, listId, itemId}.
- Linked entries show "From 'Trip to Goa'" and jump back.

Settings → Notifications: a Calendar reminders section with the master switch, the occasion toggles and offsets, the all-day time, and the exact-alarm status button.

9. TIME ZONES FOR A LONG-DISTANCE COUPLE
- All-day entries and occasions are floating dates. Both phones show the same date, and each phone reminds at its own local all-day time (09:00).
  - Optional extra for the partner's birthday: "when it turns midnight for them", computed at 00:00 in partner.timeZone.
- Timed entries store wall-clock date + time plus an anchor IANA zone, NOT a UTC instant. A weekly "Sunday 9 pm my time" call then stays at 9 pm across DST in its anchor zone.
  - The instant is ZonedDateTime.of(occurrenceDate, time, anchorZone). A DST-gap time is resolved forward by java.time.
  - Each phone converts the instant to its own zone, so the same event can fall on different local dates for the two of you; that is correct.
  - The row shows the second clock: "8:00 pm (11:30 am for Raina)".
- timeZone null ("each our own clock") means a floating time: 09:00 on each phone in its own zone. Useful for to-dos and habits.
- The default anchor is the creator's device zone. "Raina's time" is offered only when the partner shares local time (Partner.timeZone non-null); otherwise the row is hidden.
- When the device zone changes, the TIMEZONE_CHANGED receiver re-arms. Anchored instants don't move; floating and all-day reminders follow the new zone.
- The server stores and validates the zone (IANA only, ProfileTimeZone) and never computes instants.

10. EDGE CASES
- Monthly on the 29th–31st: occurrence n is computed as start.plusMonths(n), which clamps to the month's last day and does not drift. Do NOT step from the previous occurrence.
- Yearly on Feb 29: Feb 28 in non-leap years.
- Weekly: plusWeeks(n).
- repeatUntil is inclusive.
- To-dos: no repeat. A done to-do drops its reminders. Overdue to-dos stay in Upcoming until done.
- Multi-day all-day entries remind relative to the start date.
- A source message deleted or older than the local cache: the chip says "From chat" with no text. A deleted list: the chip is hidden.
- Concurrent edits by both partners: PATCH with expectedVersion → 409 → reload and re-apply.
- Unlinking purges entries on the server and wipes them locally.
- Re-pairing starts an empty calendar.
- Notifications off at system level: nothing is posted, but the alarm chain still keeps the log consistent.
- If the Atlas events validator is stale, calendar.* reach the partner only live or on the next GET /calendar refresh. The hourly worker and app-open refresh bound that delay.

11. BACKWARD COMPATIBILITY AND ROLLOUT
- Commit A (backend, deploy alone): `Event.type` in the response schema becomes z.string(), with the same values on the wire, so a later rollback cannot break GET /events. The same tolerant-reader approach as games.
- Commit B (backend), additive only:
  - new collection, validator and indexes;
  - /calendar routes;
  - EVENT_TYPES += calendar.*;
  - policy rule;
  - purge, export and API.md;
  - privacy/delete-account copy: "calendar events, to-dos and reminders".
- v75 and the current debug APK are unaffected: they never call /calendar, ignore calendar.* events and only sync on their NORMAL pushes. No existing field changes meaning.
- Commit C (Android): installed on both phones together. A HIGH push only happens when a new-app user ticks "Let Raina know", so an old-app partner never gets an invisible high push unless the couple runs mixed builds.
- Check the Render boot log for "events collection validator predates these event types" after deploy.

## File plan
- backend/src/modules/shared/schemas.ts: Commit A: make Event.type in responses a plain z.string() with a description saying clients must tolerate unknown types; wire values are unchanged. Commit B: add the CalendarSource and CalendarEntry DTO schemas (kind/repeat as plain strings in responses) and export the CalendarEntryDto type.
- backend/src/db/types.ts: Add CALENDAR_KINDS ['event','todo'], CALENDAR_REPEATS ['none','weekly','monthly','yearly'] and the CalendarEntryDoc and CalendarSource interfaces. Append 'calendar.created','calendar.updated','calendar.deleted' to EVENT_TYPES.
- backend/src/db/collections.ts: Add calendarEntries: 'calendarEntries' to COLLECTION_NAMES and Collections.
- backend/src/db/validators.ts: Add a calendarEntries $jsonSchema: required core fields, kind and repeat as patterned strings (not enums), ranges on reminders and title, optional nullable fields, no additionalProperties.
- backend/src/db/indexes.ts: Add {coupleId,date}, unique partial {coupleId,clientKey} and partial {coupleId,'source.messageId'}.
- backend/src/modules/calendar/schemas.ts: New: Create and Patch bodies with cross-field refinements (timeZone needs time, endDate only all-day, to-dos cannot repeat, repeatUntil ≥ date, reminders ≤4 unique within 0..40320, done only on PATCH), plus responses.
- backend/src/modules/calendar/repo.ts: New: insert, findInCouple, byClientKey, list (sorted by date, cap 1000), count, compare-and-set patch on version, delete.
- backend/src/modules/calendar/service.ts: New: create (idempotent on clientKey, cap, source ownership checks against messages and lists of the couple, remindUserIds ⊆ memberIds), list, update (expectedVersion → 409 version_conflict with details.entry, CAS retry loop, done/doneBy/doneAt), delete. Emits calendar.created/updated {entry, notify} and calendar.deleted {entryId}.
- backend/src/modules/calendar/routes.ts: New: GET/POST /calendar and PATCH/DELETE /calendar/:id, behind authenticate + requireCouple and a per-user write limiter (createRateLimit, as lists use), with OpenAPI tag 'Calendar'.
- backend/src/app.ts: Register calendarRoutes next to milestoneRoutes.
- backend/src/config/env.ts: Add RATE_LIMIT_CALENDAR_WRITES_PER_MIN (intFromEnv default 120) → rateLimit.calendarWritesPerMinute.
- backend/src/services/push/policy.ts: calendar.created/updated with payload.notify === true → HIGH; everything else keeps the NORMAL default. Update the doc comment.
- backend/src/modules/shared/dto.ts: Add toCalendarEntryDto.
- backend/src/modules/couples/purge.ts: Add this.db.calendarEntries.deleteMany(byCouple) to purgeData's Promise.all.
- backend/src/modules/me/{service,repo,schemas}.ts: Export: add calendar: CalendarEntry[] (bounded by EXPORT_LIMIT) and count it in truncated.
- backend/public/legal/privacy.html, backend/public/legal/delete-account.html: Name 'calendar events, to-dos and reminders' next to 'dates and countdowns', and say that reminder schedules stay on the phone.
- backend/test/calendar.test.ts: New: CRUD, validation, isolation, idempotency, version conflict, done, source checks, event payloads with notify, cap, purge on unlink, export, and the validator accepting service-made docs and unknown kind strings.
- backend/test/push-policy.test.ts: Table rows for calendar.created/updated (with and without notify) and calendar.deleted; add calendar.created and calendar.updated to CLIENT_NOTIFIES.
- backend/test/events-ws.test.ts (or isolation.test.ts): GET /events still returns 200 when the log holds an event type the build doesn't know (tolerant reader); calendar routes added to the cross-couple isolation matrix.
- docs/API.md, docs/PROGRESS.md, docs/COUNCIL_DECISIONS.md, docs/KNOWN_LIMITATIONS.md: Calendar section (shapes, endpoints, events, push table row, purge list); D-13 'Calendar and reminders' (new collection, derived occasions, local alarms); limitation: up to about 1 h late without 'Alarms & reminders', OEM auto-launch caveat; release order A→B→C.
- android/app/src/main/java/com/risaina/app/core/network/Dtos.kt, RisainaApi.kt: CalendarEntryDto (kind/repeat as String), CalendarSourceDto, Create and Patch requests, responses, and four API calls.
- android/app/src/main/java/com/risaina/app/core/database/Entities.kt, Daos.kt, RisainaDatabase.kt: CalendarEntryEntity, ReminderLogEntity, CalendarDao and ReminderLogDao. Version 6 with AutoMigration(5,6). clearAllContent clears both tables. DatabaseModule provides the DAOs. Commit the generated schemas/6.json.
- android/app/src/main/java/com/risaina/app/core/model/Calendar.kt: New domain types: CalendarEntry, CalendarKind, Repeat (fromWire tolerant), CalendarSource, AgendaItem/Occurrence, OccasionKey.
- android/app/src/main/java/com/risaina/app/core/domain/CalendarRules.kt: New, pure: occurrence expansion (plusWeeks/plusMonths/plusYears from the start, repeatUntil, Feb 29), anchored and floating instants, local and partner display lines, multi-day spans.
- android/app/src/main/java/com/risaina/app/core/domain/CalendarOccasions.kt: New, pure: derive the anniversary, both birthdays, the next visit and milestone items, de-duplicating the anniversary milestone. TimelineViewModel and HomeViewModel switch to it.
- android/app/src/main/java/com/risaina/app/core/domain/ReminderPlanner.kt: New, pure: due reminders (catch-up window, updatedAt and remindersSince guards, remindUserIds, done), next fire instant, keys, and the exact/inexact choice from (sdk, canExact).
- android/app/src/main/java/com/risaina/app/core/domain/DateMentions.kt: New, pure: a locale-aware date and time mention parser for messages and notes.
- android/app/src/main/java/com/risaina/app/core/data/CalendarRepository.kt, Mappers.kt: refresh/create/update/delete/setDone with conflict handling and re-arm requests; DTO↔entity↔domain mappers.
- android/app/src/main/java/com/risaina/app/core/datastore/ReminderSettingsStore.kt: New DataStore: master switch, per-occasion enabled and offsets, all-day time, partner-midnight option, remindersSince.
- android/app/src/main/java/com/risaina/app/core/reminders/ReminderScheduler.kt, ReminderAlarmReceiver.kt, ReminderRescheduleReceiver.kt, ReminderNotifier.kt, CalendarDoneReceiver.kt, CalendarDoneWorker.kt: New: single-alarm chain (setExactAndAllowWhileIdle when allowed, else setAndAllowWhileIdle), goAsync receiver with a Mutex and INSERT OR IGNORE claims, reschedule on boot/package replaced/time/zone/exact-permission change, reminder notifications with Open and Done actions, and the Done network call through WorkManager.
- android/app/src/main/AndroidManifest.xml: Two receivers (exported=false), the reschedule one with intent filters for BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED and SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED; explicit RECEIVE_BOOT_COMPLETED (already merged from WorkManager). No new FGS type, no USE_EXACT_ALARM, no full-screen use.
- android/app/src/main/java/com/risaina/app/core/notifications/NotificationChannels.kt, RisainaNotifier.kt: Channels reminders (HIGH), reminders_quiet (LOW) and calendar_updates (DEFAULT); an Incoming.CalendarShared branch; add the three channels to PARTNER_CHANNELS so sign-out and unlink clear them.
- android/app/src/main/java/com/risaina/app/core/sync/EventReducer.kt, DeferredEffects.kt, SyncWorker.kt; RisainaApp.kt: Apply calendar.* events and emit CalendarShared; queue a local re-arm after calendar, couple and partner events; refresh the calendar and re-arm in SyncWorker; re-arm in Application.onCreate (background).
- android/app/src/main/java/com/risaina/app/feature/calendar/CalendarScreen.kt, CalendarViewModel.kt, MonthGrid.kt, CalendarEntryScreen.kt, CalendarEntryViewModel.kt, ReminderSettingsSheet.kt: New: the Upcoming and Month tabs, the editor with time-zone conversion and warnings, and the reminder settings.
- android/app/src/main/java/com/risaina/app/navigation/Routes.kt, RisainaNavHost.kt: Calendar and CalendarEntry(prefill…) routes; deep links risaina://app/calendar[/id]; HomeNav.calendar.
- android/app/src/main/java/com/risaina/app/feature/home/HomeScreen.kt, HomeViewModel.kt: The Timeline tile becomes Calendar; hero chips open the calendar; a 'Coming up' card (next 7 days of entries plus due to-dos); pinned-entry chips; occasion derivation via CalendarOccasions.
- android/app/src/main/java/com/risaina/app/feature/messages/MessagesScreen.kt, MessagesViewModel.kt: Long-press action sheet for text messages (calendar, to-do, copy, delete-own); the composer '+' sheet (game, calendar, to-do); a date-suggestion chip for the draft; an 'In your calendar' chip under source bubbles.
- android/app/src/main/java/com/risaina/app/feature/lists/ListsScreens.kt, ListsViewModel.kt: 'Dates in this note' chips under the note body; a calendar icon on checklist rows with a date; linked status; 'Add a to-do' into a pinned 'To-dos' checklist when there is no due date.
- android/app/src/main/java/com/risaina/app/feature/settings/SettingsScreens.kt; feature/timeline/TimelineScreen.kt: A Calendar reminders section in Notification settings with exact-alarm status (reuse ReadinessFix.EXACT_ALARMS); make MeetingDialog and MilestoneDialog internal so the calendar can open them; Timeline uses CalendarOccasions.
- android/app/src/main/java/com/risaina/app/core/sync/WidgetSnapshotUpdater.kt (optional): Countdown fallback order: nextMeetingDate → next pinned calendar entry → next milestone.

## Tests
- Backend calendar.test.ts: create, list, patch, delete happy paths; 404 for another couple's entry; idempotent POST with the same clientKey returns 200 and the same entry with no second event; PATCH with a stale expectedVersion → 409 version_conflict with details.entry; done toggles only for kind todo (event → 400).
- Backend validation: timeZone without time → 400; fixed offset '+05:30' rejected; endDate on a timed entry → 400; todo with repeat weekly → 400; reminders with 5 items, duplicates or 40321 → 400; remindUserIds holding a non-member → 400; source.messageId from another couple → 404; a view-once message as source → 400; a deleted list as source → 404; the 1001st entry → 409.
- Backend events: calendar.created carries {entry, notify:false} by default and notify:true with notifyPartner; calendar.updated after PATCH; calendar.deleted {entryId}; the outbox sends HIGH only for notify:true, and NORMAL with collapseKey 'sync' otherwise (outbox.test.ts style with the fake push).
- Backend push-policy.test.ts: new table rows; the 'covers every event type' and 'only pushes high what the app turns into a visible notification' tests stay green with calendar.created/updated added to CLIENT_NOTIFIES.
- Backend validator: every doc the service writes passes the calendarEntries validator; an insert with kind 'anniversary_v2' or repeat 'daily' passes the validator, so Zod remains the only allow-list; the events pattern accepts the calendar.* names (profile-lists.test.ts:399 already iterates EVENT_TYPES).
- Backend tolerant reader: insert a raw event with type 'future.thing' and check GET /events returns 200 and includes it, after commit A.
- Backend unlink-purge.test.ts: calendarEntries are removed by unlink and by account deletion, and the cleanup worker's re-run purges an entry written concurrently. me export includes calendar and counts it in truncated.
- Android CalendarRulesTest: weekly, monthly from Jan 31 → Feb 28/29 → Mar 31 (no drift), yearly from Feb 29, repeatUntil inclusive; an anchored Europe/London 21:00 weekly event across the March and October DST changes keeps 21:00 London and shifts in Asia/Kolkata; a floating 09:00 stays 09:00 on both phones; the Pacific/Auckland vs America/Los_Angeles date-line case shows different local dates; the partner second-line text.
- Android CalendarOccasionsTest: anniversary from relationshipStartDate; partner birthday '--02-29' in a non-leap year; turning age only with a year; an anniversary milestone equal to the start date is de-duplicated; the next meeting is excluded once past; the outputs match the old Timeline 'Coming up' list for the same input (regression).
- Android ReminderPlannerTest: all-day offset 1440 fires the day before at 09:00 local; the catch-up window posts at 5 h late and drops at 7 h late (except an on-the-day all-day reminder before 23:00); a fire time before entry.updatedAt is skipped; a fire time before remindersSince is skipped; remindUserIds without me → none; a done to-do → none; next fire instant chosen correctly; exact versus inexact by (sdk, canExact).
- Android DateMentionsTest: '14 Nov', '14th November', 'Nov 14 at 8pm', '2026-11-14', '14/11' under en-IN vs '11/14' under en-US, 'tomorrow 9:30', 'next Fri', 'day after tomorrow'; rejects '1/2 cup', '3-4 people' and phone numbers; the year rolls to the next occurrence.
- Android EventReducer test (like SafetyEventsTest): calendar.created and updated upsert, deleted removes, notify from the partner yields Incoming.CalendarShared, own and replayed events yield nothing, and the cursor advances.
- Android Room: MigrationTestHelper 5→6 keeps outbox rows and creates the two tables (room-testing is already a test dependency).
- Android NotifierPurgeTest: notifications on the reminders, reminders_quiet and calendar_updates channels are cleared on purge, and the SOS id is still never cleared.
- Manual, main session on emulator and phones (not run here): with the app killed, a reminder fires on time with 'Alarms & reminders' granted and at most about 1 h late without it under `dumpsys deviceidle force-idle`; reboot then reminder (BOOT_COMPLETED re-arm); change the device time zone and check floating and anchored reminders; Raina's phone gets 'added to your calendar' with the app closed when notify is on, and only a silent sync when off; ColorOS swipe-away then the next push re-arms; quiet hours post silently; sign-out and unlink cancel the alarm and clear notifications; v75 paired with the new backend still syncs normally.

## Risks
- The live Atlas `events` validator may still have the strict enum. calendar.* would then be live-only: not stored, not replayed, not pushed. Mitigation: GET /calendar refresh on open, foreground and the hourly worker; check the Render boot log after deploy; granting dbAdmin fixes it.
- Rollback hazard: without commit A deployed first, rolling the backend back after any calendar.* event is stored would make GET /events fail response validation (500) for that couple and stall sync for both phones.
- Without the 'Alarms & reminders' permission (denied by default for new installs on Android 14+), reminders are inexact: up to about 1 h late on Android 12+ by the AOSP window cap (inferred, to be checked on a device), and worse on OEM builds. A timed '10 min before' reminder can then arrive after the event.
- OEM battery managers (realme/ColorOS) can drop alarms or block BOOT_COMPLETED when auto-launch is off. Re-arming on every process start (push, open, hourly worker) recovers, but a phone that receives no push and is never opened can miss a reminder. The existing OEM guidance card covers the settings.
- Mixed builds: if one partner runs v75 or an older APK and the other ticks 'Let Raina know', a HIGH push ends in no visible notification on the old phone, which teaches FCM to lower that phone's priority, pings included. Install the new APK on both phones together, or start with notify off by default.
- A Room migration mistake would trigger fallbackToDestructiveMigration and lose unsent outbox messages and the caches. Use AutoMigration 5→6 with a MigrationTestHelper test.
- Duplicate or missing reminder notifications when the alarm, worker and app start run together. The INSERT OR IGNORE claim under a Mutex gives exactly-once per phone, but the catch-up and updatedAt rules need careful tests.
- Time zone confusion: anchored vs floating times, a partner who does not share local time, DST-gap times, and the same event landing on different local dates for the two phones. The UI must always show both clocks when they differ.
- Partner-created timed events can remind at night in the other zone. Reminders inside quiet hours go to the silent channel; the editor warns at save time when either side falls between 23:00 and 07:00.
- Date detection can be wrong (dd/mm vs mm/dd, false positives). It only ever suggests; nothing is created automatically, and the editor always shows the parsed date.
- Notification noise and Play perception: reminders must never use a full-screen intent, setAlarmClock (alarm icon and next alarm on the lock screen) or a foreground service. The design keeps to plain notifications to respect the no-lock-screen-change rule and the existing Play declarations.
- Composer change: replacing 🎲 with '+' adds one tap to start a game, which users of the current build may notice.

## Open questions
- Should a new entry notify the partner by default ('Raina added … to your calendar', a HIGH push), or should calendar changes stay silent syncs until both phones run the new build?
- Default reminder offsets: is 'anniversary: 7 days before + on the day at 09:00' and 'partner's birthday: 7 days + 1 day + on the day' right? Should 'when it turns midnight for them' be on by default when the partner shares their time zone?
- Should reminders follow the in-app master notifications switch and quiet hours (as proposed), or always ring because the person asked for them?
- On Home, should Calendar replace the Timeline tile (Timeline then lives inside Calendar as 'Our story'), or replace another tile such as 'Their widget' or 'Widgets'?
- Undated to-dos: add them to a shared 'To-dos' checklist (existing lists, as proposed), or allow calendar to-dos with no date (a 'To-dos' tab in Calendar)?
- Are private 'surprise' entries wanted (for example, planning the partner's birthday)? They need an event path that never reveals the content to the partner and are left out of v1.
- Is the Atlas user granted dbAdmin now? Check the Render boot log for 'events collection validator predates these event types' or the validator warning.
- Should the countdown and 'Us' widgets fall back to pinned calendar entries when no next visit is set?
- Is a 'Snooze 1 hour' action on reminders and a 'Remind Raina' nudge (send them a ping about a to-do) wanted in v1?
- Is it acceptable to ask for 'Alarms & reminders' from the calendar as well as from the SOS readiness card, the first time a timed reminder is saved?
