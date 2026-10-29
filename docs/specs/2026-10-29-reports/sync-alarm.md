# sync-alarm

## Summary
Recommendation: the server stores one shared "Wake together" alarm rule, and each phone schedules and rings its own copy. The backend adds a new `alarms` collection (created at boot, so no collMod is needed), new endpoints and four new event types. Each phone sets only its next ring with AlarmManager.setAlarmClock, using SCHEDULE_EXACT_ALARM, which is already declared and already requested by the SOS readiness card. The ring is a full-screen-intent notification on a new IMPORTANCE_HIGH channel (alarm sound with USAGE_ALARM, CATEGORY_ALARM, FLAG_INSISTENT, its own group). There is no foreground service, so no new Play declaration is needed. The alarm rings at the same instant on both phones, tied to the IANA time zone of the person who created it. The editor shows the time for both partners, and each person can turn it off on their own phone or skip one day. Snooze and dismiss only affect the phone they happen on. "I'm up" sends a report, so the partner sees "Aanya is up", but their own alarm keeps ringing. Reusing SosAlarmService would run the specialUse foreground service for something its Play declaration does not cover (SOS only), so I rejected it. The system Clock app cannot be handed an alarm from the background and cannot be updated afterwards, so it is only an optional manual backup. Verified: RECEIVE_BOOT_COMPLETED is merged from work-runtime 2.12.0, but the app has no boot receiver of its own. Known gaps of the chosen design: it needs notifications on (the SOS does not), the insistent sound stops when the notification shade is opened (handled by an automatic snooze on timeout), full-screen alerts are off by default on Play installs on Android 14+, and two cases cannot be fixed by the app: a night-time reboot that stays locked until morning, and phone makers' force-stop. Backend changes stay backward compatible: version 75 ignores unknown event types, and every new event goes out as a normal-priority push unless the partner's phone has shown it understands alarms. I ran backend/test/push-policy.test.ts: 59 passed.

## Root causes
- [verified] There is no alarm or local scheduling feature today. The Rituals preferences (morning/night times) and the RITUALS channel exist, but no code schedules them.
  - grep for ritual/Ritual finds only android/.../core/datastore/PreferencesStore.kt:43-45,77-79,111-113, core/model/Models.kt:177-179 and core/notifications/NotificationChannels.kt:22,72-76. The only AlarmManager use is the SOS retry in core/sos/SosAlarmController.kt:138-152.
- [verified] The setAlarmClock + SCHEDULE_EXACT_ALARM path the alarm needs already exists for the SOS, and the readiness card already sends the user to 'Alarms & reminders'.
  - SosAlarmController.kt:138-152 calls canScheduleExactAlarms() and then setAlarmClock(AlarmClockInfo(now+1s, show), PendingIntent.getForegroundService(...)). core/safety/SosReadiness.kt:114-121 has the EXACT_ALARMS check and line 184 opens ACTION_REQUEST_SCHEDULE_EXACT_ALARM. AndroidManifest.xml:23 declares SCHEDULE_EXACT_ALARM.
- [likely] The specialUse foreground service is declared to Play for the SOS only. Using it to ring a wake-up alarm would be an undeclared use.
  - AndroidManifest.xml:64-72 sets PROPERTY_SPECIAL_USE_FGS_SUBTYPE = 'Rings an emergency SOS alarm sent by the user's partner until the user acknowledges it'. docs/private/PLAY_RELEASE_2026-10-26.md:14 says 'the SOS alarm as special use with video 2'. PROGRESS.md:357-360 records that systemExempted was removed because Play does not offer it. I infer that Play would refuse to widen the declaration or treat the reuse as a policy violation.
- [verified] Full-screen alerts are not granted by default on Play installs on Android 14+ for this app. Without that permission the system shows a heads-up that stays only 60 seconds.
  - PLAY_RELEASE_2026-10-26.md:15: 'core function is "Other", not alarms or calling'. The SDK source android/app/Notification.java:5846-5861 says that without USE_FULL_SCREEN_INTENT the notification shows as a heads-up even when the screen is locked or off, persistent for 60 s, and that the channel must be IMPORTANCE_HIGH. NotificationManager.java:1233-1239 has canUseFullScreenIntent(). The readiness card already has a FULL_SCREEN fix (SosReadiness.kt:74-80,180).
- [verified] RECEIVE_BOOT_COMPLETED reaches the merged manifest only through WorkManager, and the app has no BOOT_COMPLETED receiver of its own, so nothing would re-arm alarms after a reboot today.
  - In app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml (versionCode 75), line 62 is <uses-permission RECEIVE_BOOT_COMPLETED>. The blame report traces it to [androidx.work:work-runtime:2.12.0] AndroidManifest.xml:25. The only BOOT_COMPLETED filter is androidx.work...RescheduleReceiver at lines 416-422, which is android:enabled="false" and exported="false". The source manifest android/app/src/main/AndroidManifest.xml has no boot receiver.
- [likely] The app process cannot safely start before the first unlock after a reboot (direct boot). An alarm due while a rebooted phone is still locked would therefore be missed unless startup code is refactored.
  - RisainaApp.kt:21 field-injects SafetyLifecycle. Its dependency SosLocalState opens credential-encrypted SharedPreferences in its constructor (core/sos/SosLocalState.kt:34), as do SafetySettings.kt:29 and LiveLocationController.kt:132. No app component is directBootAware (merged manifest: only Firebase/Room library components are). BOOT_COMPLETED is delivered only after the first unlock (documented Android behaviour). That CE access throws in direct boot is documented Android behaviour, not tested here.
- [verified] The push policy already sends unknown event types as normal (silent sync) pushes. The rule is that a high push must end in a visible notification. Old apps ignore unknown event types.
  - backend/src/services/push/policy.ts:4-9 and 108-111 (default NORMAL with collapseKey 'sync'). backend/test/push-policy.test.ts asserts 'only pushes high what the app turns into a visible notification' (CLIENT_NOTIFIES) and 'covers every event type the server can emit'. android/.../core/sync/EventReducer.kt:80-165 is a `when` with no else branch, then advances the cursor (line 166). Dtos.kt:159 has EventDto.type as a String, and NetworkModule.kt:50 sets ignoreUnknownKeys. I ran `pnpm exec vitest run test/push-policy.test.ts`: 59 passed.
- [possible] New `alarm.*` event types pass the current events validator. If the live Atlas database still has the older strict enum validator, they would reach only connected sockets: no push and no replay.
  - backend/src/db/validators.ts:581-584 uses a dotted-name pattern instead of an enum. validators.ts:646-660 and db/index.ts:21-27 detect and log stale enum types. services/events/publisher.ts:62-80 degrades to socket-only on code 121. PROGRESS.md:94,135 says the DB user lacked collMod and that granting dbAdmin was an action item. Nothing in the repo says whether that was done.
- [verified] Room is wiped on schema changes, so the alarm schedule that boot and time-change receivers read must live outside Room.
  - core/database/RisainaDatabase.kt:20 is version 5, and line 46 is .fallbackToDestructiveMigration(dropAllTables = true) 'cache only, server is the source of truth'.
- [verified] Exact alarms need SCHEDULE_EXACT_ALARM, which is denied by default for this app (targetSdk 36), or a battery exemption. Inexact fallbacks may be at least 10 minutes late, and later in Doze.
  - SDK android/app/AlarmManager.java: 296-304 (SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT, target 33+); ~1604-1609 (canScheduleExactAlarms is true with the permission OR on the power-save exemption list); 159-180 (on revoke all exact alarms and setAlarmClock alarms are deleted, with no broadcast; on grant ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED is sent to manifest receivers); 604-607 (S+ windows shorter than 10 min are lengthened to 10 min); 966-967 (setAlarmClock alarms may start a foreground service). app/build.gradle.kts:38 sets targetSdk = 36.
- [verified] The partner's IANA time zone is already on the phone when they share their local time, so the editor can show both local times.
  - core/data/CoupleRepository.kt:141-146 PATCHes the phone's zone to the server. Dtos.kt:20 has PartnerDto.timeZone. core/domain/Occasions.kt:62-73 partnerClock() already renders 'tomorrow for them'. SettingsScreens.kt:301 is the 'My local time' toggle (when off, the partner's timeZone is null).
- [verified] Older Android 15 builds auto-group app notifications, which stopped the SOS full-screen alarm from launching. The wake alarm notification needs its own group too.
  - core/notifications/SosAlert.kt:83-86 setGroup(SOS_GROUP) with the comment; PROGRESS.md:339 and 377-380.

## Current state
Android (verified by reading the code):
- There is no alarm feature. The only scheduling is SOS-related:
  - SosAlarmController.scheduleAlarmClock (core/sos/SosAlarmController.kt:138-152) is a one-second setAlarmClock retry that starts SosAlarmService through PendingIntent.getForegroundService.
  - SosAlarmService (core/sos/SosAlarmService.kt) is a specialUse foreground service. It plays the user's alarm tone, or R.raw.sos_alarm as backup, with MediaPlayer on USAGE_ALARM (lines 319-340), with alarm-usage vibration (343-357). It raises the alarm volume and restores it later (291-306, 427-443). It holds a wake lock, adds a DND ringer only with DND access (168-170), and shows SosAlarmActivity directly when the app is on screen (178-180).
  - SosAlarmActivity (core/sos/SosAlarmActivity.kt) is showWhenLocked/turnScreenOn and never unlocks. Its back button does nothing, and it finishes when the controller state turns null.
  - SosAlert (core/notifications/SosAlert.kt) builds the FGS notification: CATEGORY_ALARM, full-screen intent, its own group, and a delete intent that silences without acknowledging. It also builds an insistent notification-only fallback on an alarm-sound channel (169-225).
- NotificationChannels.kt:
  - Defines PINGS, MESSAGES, RITUALS (unused), SYSTEM and the safety channels.
  - SOS_ALARM is silent because the service plays the sound.
  - SOS_RING_DND bypasses DND when access is granted.
  - ensureSafety() runs on every foreground (SafetyLifecycle.kt:62).
- SosReadiness/SosReadinessProbe (core/safety/SosReadiness.kt) already check and link to fixes for: notifications, channel blocked, full-screen intent, battery, background restriction, DND access, silent mode muting alarms, alarm volume 0, exact alarms, and OEM autostart. SosDiagnostics.dndBlocksAlarms (core/sos/SosDiagnostics.kt) detects a DND mode that blocks alarms.
- Manifest (android/app/src/main/AndroidManifest.xml):
  - Permissions: POST_NOTIFICATIONS, ACCESS_NOTIFICATION_POLICY, USE_FULL_SCREEN_INTENT, FOREGROUND_SERVICE(+SPECIAL_USE, +LOCATION), WAKE_LOCK, SCHEDULE_EXACT_ALARM.
  - Not declared, as required: USE_EXACT_ALARM, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, systemExempted.
  - The app has no boot, time or time-zone receiver. RECEIVE_BOOT_COMPLETED is present only because work-runtime 2.12.0 merges it (merged manifest line 62).
- Startup and lifecycle:
  - RisainaApp.onCreate creates channels and ignores SosAlarmActivity for AppVisibility (RisainaApp.kt:27).
  - SafetyLifecycle stops SOS ringing and clears state on sign-out or unlink (SafetyLifecycle.kt:76-97).
- Sync and data:
  - Room is a destructive-migration cache (RisainaDatabase.kt:46).
  - EventReducer applies events by seq and ignores unknown types.
  - RisainaMessagingService handles data-only pushes, with SOS handled first.
  - A periodic SyncWorker runs hourly (SyncWorker.kt:141-145).
- Time zones: the partner's time zone is synced and shown on Home (Occasions.partnerClock).
- Home: QuickRow has 8 tiles, including a "Settings" tile (HomeScreen.kt:355-367). TopRow already has a settings button (HomeScreen.kt:133).

Backend (verified):
- Pattern: Fastify + Zod modules (for example milestones: routes/service/repo/schemas). Services call ctx.events.emit; EventPublisher allocates the per-couple seq, inserts the event, publishes to sockets and wakes the outbox.
- The outbox (workers/outbox.ts) pushes data-only FCM messages to the other member only (lines 113-116). Priority comes from services/push/policy.ts; the default is normal with collapseKey 'sync'. SOS bypasses preferences.
- New collections are created at boot with validators (validators.ts:606-631). The events validator uses a type pattern, not an enum (581-584).
- EVENT_TYPES (db/types.ts:289-319) feeds z.enum for the GET /events response (shared/schemas.ts:618-627), so new types must be added there.
- Existing helpers: HHmm and ProfileTimeZone validators (shared/schemas.ts:38,49-53); localMinutes(at, tz) (lib/quiet-hours.ts:17).
- CouplePurger.purgeData deletes every couple-scoped collection on unlink (couples/purge.ts).
- SosRepushWorker shows how interval workers are built.
- Hosting: Render. The plan could be free (sleeps after about 15 minutes) or Starter (docs/private/MY_SETUP.md:132-133). This is one reason the server must not be the alarm's clock.

Play state (docs/private/PLAY_RELEASE_*):
- Version 75 is in production review.
- Foreground-service declarations: location (video 1) and specialUse SOS (video 2).
- Full-screen intent declaration: "Other".

## Design
1. PRODUCT DECISIONS

1.1 Who sets it
- Either partner can create, edit, turn off or delete a "Wake together" alarm. It is shared like a list; edits are versioned and a stale edit gets 409.
- By default it rings on both phones. Each person controls only their own phone: "Not on my phone" (an opt-out switch) and "Skip tomorrow on my phone".
- The server enforces this: PUT /alarms/:id/me changes only the caller's member entry.
- When the partner's phone first receives an alarm that will ring on it, it posts a notice: "Rishabh set Wake together for 6:30 am, Mon–Fri. It rings on your phone too." with a "Not on my phone" action. A 5 am alarm never comes as a surprise.
- The alarm never bypasses Do Not Disturb. Only the SOS does.

1.2 Same instant, not the same local time (recommended)
- The rule is: HH:mm + weekday set + an anchor IANA zone (the creator's zone at creation).
- Both phones compute the same occurrence instant with java.time, so they ring together.
- For couples in one zone (most couples), this is identical to "same local time", and no zone UI is shown.
- For long-distance couples, the same instant is the only meaning of "wake up at the same time". The per-person opt-out covers an unreasonable local hour.
- Making it clear in the UI:
  a. When the viewer's zone differs from the anchor zone, or from the partner's shared zone, the editor shows two lines under the time picker: "6:30 am for you · 9:00 am for Aanya". It adds the existing "tomorrow/yesterday for them" note (Occasions.partnerClock) and a chip "Set in India time".
  b. If the partner's local time falls between 00:00 and 05:00, the editor warns: "That's 2:00 am for Aanya".
  c. On the partner's phone, the list shows the actual local time of the next ring: "Rings here 2:00 am (6:30 am for Rishabh)".
  d. If the partner hides their local time, the text is "Rings at the same moment on both phones".
  e. The editor says "Aanya will see this alarm's time zone". The anchor zone is visible to the partner even when "My local time" is off.
- Edits keep the anchor zone. A later "Use my time zone" option can re-anchor it.
- Store `mode: 'together'` (a plain string in the database, a Zod enum in the API) so a later `local` mode (each at their own wall-clock time) is an additive change. From the first release, a client that sees an unknown mode does not schedule that alarm and shows "Update Risaina to use this alarm".

1.3 DST and nonexistent times
- Each occurrence is computed with ZonedDateTime.of(date, time, anchorZone). A time in a spring-forward gap moves forward by the gap (America/New_York 2027-03-14 02:30 becomes 03:30 EDT). An overlap uses the earlier offset (2026-11-01 01:30 resolves to EDT).
- When the two zones switch DST on different dates (for example New York on 14 Mar 2027 and London on 28 Mar 2027), the partner's local ring time moves by an hour for those weeks. This is inherent to "same instant". The list always shows the real local time of the next ring on each phone.

1.4 Repeat days, label, snooze, dismiss
- days: ISO weekdays 1–7 in the anchor zone. An empty list means a one-off alarm, stored as `onceAt` (an instant).
- label: up to 40 characters, optional, default "Wake together".
- snoozeMinutes: default 10 (1–30), shared.
- Ring length: 10 minutes. If nobody acts, the alarm snoozes itself once and rings again. If the second ring also gets no answer, the alarm stops, a quiet "Missed: Wake together 6:30" notice is posted and outcome `missed` is reported.
- Snooze is local only and is not reported in v1.
- Dismiss ("I'm up") is local and is reported as outcome `up`, with the time and the snooze count.

1.5 Does one person's dismiss tell the other?
- Yes, but it never silences the other phone. Otherwise one person dismissing could leave the other asleep.
- On the partner's phone, `alarm.woke` updates:
  - the ringing alarm screen: "Aanya is up ☀ 6:34";
  - the snoozed notification: "Snoozed until 6:40 · Aanya is up";
  - or, once they are up themselves, a quiet "Aanya is up too · 6:34" on the updates channel, respecting quiet hours.
- Today's state ("You're both up · 6:31 / 6:34") shows on the Home card.
- Once I'm up, if my partner has not reported `up` yet, the Wake screen offers "Send a good-morning ping". This reuses ping type `good_morning` (backend/src/db/types.ts PING_TYPES) and its existing high-priority push. No new push type is needed.

2. SERVER (new and additive only)

2.1 Collection `alarms`
- Created at boot by adding it to VALIDATORS; the createCollection path needs no collMod.
- Use plain strings or numbers in the validator, never enums. Do not set additionalProperties:false, so fields can be added later without collMod (the lesson from games, validators.ts:458,500).
- AlarmDoc fields:
  - _id, coupleId, createdBy, updatedBy
  - mode: 'together'
  - label: string|null (≤40)
  - time: 'HH:mm'
  - timeZone: IANA
  - days: int[] (1..7, unique, sorted)
  - onceAt: Date|null (required when days is empty)
  - enabled: bool
  - snoozeMinutes: int
  - version: int (starts at 1)
  - createdAt, updatedAt
  - members: exactly the 2 couple members, each with:
    - userId
    - ring: bool
    - skips: Date[] (future occurrences only, at most 7)
    - ready: bool|null (null means this person's phone has never reported, for example version 75)
    - reason: string|null (≤32, for example 'exact_alarms_off', 'notifications_off', 'channel_blocked')
    - armedFor: Date|null
    - reportedAt: Date|null
    - lastWake: {occurrence: Date, outcome: 'up'|'missed', at: Date, snoozes: int}|null
- Index: {coupleId:1, createdAt:1}, added in db/indexes.ts.
- Limit: 10 alarms per couple.

2.2 Endpoints (all use authenticate + requireCouple, like milestones/routes.ts:19)
- GET /alarms returns {items: Alarm[]}.
- POST /alarms takes {label?, time (HHmm), timeZone (ProfileTimeZone), days, onceAt?, ringOnMyPhone=true, snoozeMinutes=10, mode='together'} and returns 201 {alarm}.
  - Validation: onceAt is required when days is empty. It must lie between now−1 min and now+366 days, and localMinutes(onceAt, timeZone) must equal parseHHmm(time) (lib/quiet-hours.ts:10,17).
  - The partner member starts with ring:true. The creator's ring comes from ringOnMyPhone.
- PATCH /alarms/:id takes {expectedVersion, label?, time?, timeZone?, days?, onceAt?, enabled?, snoozeMinutes?}. It returns 200 {alarm}, or 409 when the version is stale.
- DELETE /alarms/:id returns 204.
- PUT /alarms/:id/me takes {ring?, skip?: {occurrence, skip: bool}} and returns {alarm}. It changes the caller's entry only.
- POST /alarms/report takes {items: [{alarmId, ready, reason|null, armedFor|null}]} (≤10) and returns 204. It is sent by the phone after arming. It emits an event only when `ready` or `reason` changes.
- POST /alarms/:id/wake takes {occurrence, outcome, at, snoozes (0..20)} and returns 200 {alarm}. It is idempotent per member and occurrence: a repeat with the same occurrence is a no-op without an event.

2.3 Events
Add 'alarm.created', 'alarm.updated', 'alarm.deleted' and 'alarm.woke' to EVENT_TYPES (db/types.ts:289). The payloads:
- alarm.created: {alarm, notify}
- alarm.updated: {alarm, change: 'edited'|'enabled'|'member', notify}
- alarm.deleted: {alarmId}
- alarm.woke: {alarmId, userId, occurrence, outcome, at, snoozes}

The payloads carry the label, as milestone events carry the title. Pushes carry no content (outbox.ts:139-147).

2.4 Push policy (services/push/policy.ts)
- 'alarm.created' and 'alarm.updated' are high only when payload.notify === true, otherwise normal. This follows the payload-dependent pattern of isPanicWithSos (policy.ts:93-101).
- The service sets notify = true only when all of these hold: the change affects the recipient's phone (they are a ringing member and the alarm is enabled), the change is an edit or creation and not their own member change, and the recipient's member.ready !== null (their phone has reported, so it runs a build that posts the visible notice).
- So version 75 phones never get a high push they would answer with nothing, which protects the FCM priority the SOS depends on.
- 'alarm.deleted' and 'alarm.woke' are normal, through the existing default branch (policy.ts:108-111).
- Notifications off still turns high into normal through quietPolicy (outbox.ts:178-181).
- At wake-up time the phones are awake, so normal pushes arrive promptly. The WebSocket covers the case where the app is open.
- The test file needs table rows for every alarm type, and CLIENT_NOTIFIES must gain the two notify cases.

2.5 Purge and cleanup
- Add `this.db.alarms.deleteMany(byCouple)` to CouplePurger.purgeData (couples/purge.ts). Unlink and account deletion then remove alarms.
- Optional: the cleanup worker deletes one-off alarms 2 days after onceAt.
- Update the /privacy text: shared alarms (time, days, label, zone, who woke when) are stored.

2.6 Compatibility
- Only additions: a new collection, new routes, new event types (pattern-valid, ignored by version 75), and a new policy branch.
- DeviceUpsertBody and other existing shapes are unchanged.
- After deploy, check the Render boot log for "events collection validator predates". If it appears, the alarm events are socket-only until dbAdmin is granted.

3. ANDROID DATA LAYER

3.1 Storage and rules
- WakeAlarmStore stores the alarms as JSON in SharedPreferences "wake_alarms", outside Room, because Room is wiped on schema changes. It also keeps:
  - the set of armed alarm ids, to cancel stale ones;
  - the local ring state: {alarmId, occurrence, phase ringing|snoozed|up|missed, snoozes, until};
  - the last readiness reported.
  It exposes a StateFlow for the UI and is read synchronously by the receivers.
- WakeAlarmRules is pure and unit-tested:
  - next(alarm, me, now) returns the next occurrence instant, honouring days in the anchor zone, onceAt, skips, enabled and ring;
  - shouldRing(occurrence, now): ring only if now − occurrence ≤ 30 min;
  - localLabel(instant, zone);
  - partnerLine(...): reuses the Occasions.partnerClock logic.

3.2 Network and events
- WakeAlarmRepository wraps the new endpoints (RisainaApi and Dtos).
- apply(event) handles alarm.* by upserting or deleting in the store, re-arming, and returning Incoming.WakeSet (when notify is set) or Incoming.WakeUp.
- EventReducer gets one new branch: "alarm.created","alarm.updated","alarm.deleted","alarm.woke" -> wakeAlarms.apply(event, me).
- RisainaNotifier posts the partner notices on the new wake_updates channel, or on wake_updates_quiet during quiet hours (QuietHoursPolicy.shouldAlert).
- A full refresh (GET /alarms) runs on app open and after sign-in.

3.3 Workers and lifecycle
- WakeReportWorker uses unique work with a network constraint and backoff, like SosReceiptWorker. It sends POST /wake and the readiness report.
- On sign-out or unlink: cancel every PendingIntent and notification and clear the store. Hook this into SafetyLifecycle.onAccountOrCoupleGone (SafetyLifecycle.kt:89-97) or a sibling lifecycle.

4. HOW THE PHONE RINGS: OPTIONS AND CHOICE

4.1 Option A, recommended: setAlarmClock + a full-screen-intent notification, with no foreground service
- Arming (WakeAlarmScheduler.arm, called on every store change, app open, sync, fire, and the re-arm broadcasts):
  - Compute the next occurrence for each enabled alarm where my member has ring=true.
  - Build the operation: PendingIntent.getBroadcast(WakeAlarmReceiver, action FIRE, data risaina://wake/<id>, extra occurrence ms, FLAG_IMMUTABLE|UPDATE_CURRENT). The data URI gives each alarm its own identity, with no clash with SOS request code 9120.
  - Build the show intent: an activity PendingIntent to risaina://app/wake.
  - If SDK<31 or canScheduleExactAlarms(): call setAlarmClock and report ready = true.
  - Otherwise: call setAndAllowWhileIdle(RTC_WAKEUP, t) as a best effort and report ready = false with reason 'exact_alarms_off'.
- On fire, WakeAlarmReceiver (goAsync, no network, no Room):
  1. Validate against the store: the alarm still exists, is enabled, ring is on, the occurrence is not skipped and not already up, and shouldRing passes. Otherwise post a quiet "missed (phone was off)" notice.
  2. If an SOS is ringing (SosAlarmController.state != null), snooze instead.
  3. Post the ring notification and set the controller state.
  4. Arm the timeout at T+10 min with setExactAndAllowWhileIdle, with setTimeoutAfter as a backstop.
  5. Arm the next occurrence right away, so tomorrow is set even if ColorOS kills the process.
  6. If Risaina is on screen, also start the activity directly (the same reason as SosAlarmService.kt:176-180).
- The ring notification (WakeAlarmNotifier, modelled on SosAlert.alarmNotification):
  - Channel `wake_alarm`, IMPORTANCE_HIGH (required for a full-screen launch).
  - Sound: a new bundled tone res/raw/wake_alarm, distinct from the SOS tone and not dependent on the user's default alarm tone being set, with AudioAttributes USAGE_ALARM and CONTENT_TYPE_SONIFICATION. The user can change it in the channel settings, which the Wake screen links to.
  - Vibration on. No setBypassDnd.
  - Notification: CATEGORY_ALARM, PRIORITY_MAX, FLAG_INSISTENT (loops the sound), setFullScreenIntent(WakeAlarmActivity, true), setOngoing(true).
  - Its own group "risaina_wake_alarm" (the Android 15 auto-group lesson).
  - Actions "Snooze 10 min" and "I'm up" are broadcasts to WakeAlarmReceiver. The delete intent (a swipe on Android 14+) means snooze, not dismiss.
  - On a locked phone with previews off, the public version shows only "Alarm · 6:30".
- Why this option:
  - It needs no new permission, foreground-service type or Play declaration. USE_FULL_SCREEN_INTENT and SCHEDULE_EXACT_ALARM are already declared (manifest 14, 23), and a user-set alarm is the textbook use of both (Notification.java:5837-5840).
  - setAlarmClock is exact and keeps working in Doze. Android leaves idle before an alarm clock; this is documented behaviour and not re-verified here.
  - The sound is played by the system, so it keeps going even if the app process is killed after the receiver returns.
- Costs:
  - Notifications must be on, and the wake channel must not be blocked. The SOS does not need this (SosAlarmService.kt:39-42).
  - FLAG_INSISTENT sound stops when the shade is opened (Notification.java:647-652). The timeout's automatic snooze makes it ring again.
  - There is no app-controlled volume ramp.
  - Vibration may be suppressed in full silent mode. That is likely; verify it.
  - Full-screen alerts are off by default on Play installs on Android 14+. The alarm then shows as a heads-up that persists 60 s while the sound keeps looping; verify the looping.

4.2 Option B, rejected: reuse SosAlarmService and specialUse
- It would work technically and would give the best ring: setAlarmClock may start a foreground service (AlarmManager.java:966-967, already done at SosAlarmController.kt:143-150), with MediaPlayer on the alarm stream, alarm vibration, and ringing even with notifications off.
- But the specialUse subtype and the Play declaration (video 2) cover the SOS only (manifest 69-71; PLAY_RELEASE doc line 14). Ringing a wake-up alarm with it is an undeclared use.
- Changing the declaration means a new review. Google steers alarm ringing to systemExempted, which this app cannot use (PROGRESS.md:357-360).
- It also mixes wake-up alarms into SOS-specific logic (receipts, merge(), RING_MS, full-volume raise, DND ringer), which risks regressions on the safety path.
- shortService (no special permission, 3-minute limit) would still be a new foreground-service type and declaration, so it is not chosen either.

4.3 Option C, backup only: hand the alarm to the system Clock app (AlarmClock.ACTION_SET_ALARM)
- It is an activity intent and cannot be started from the background, so the partner's phone cannot add it when the event arrives. It needs com.android.alarm.permission.SET_ALARM.
- EXTRA_SKIP_UI is honoured only for non-repeating alarms (AlarmClock.java:43-44), and Clock apps from different phone makers behave differently.
- Risaina gets no id back, so it cannot edit the alarm later. ACTION_DISMISS_ALARM works by a fuzzy search.
- The Clock app uses local wall-clock time only, and Risaina learns nothing about "is up".
- Use it only as an optional foreground button, "Also add to my Clock app", in phase 2. It converts the alarm to device-local time and says the Clock copy won't follow later edits.

4.4 WakeAlarmActivity
- Modelled on SosAlarmActivity: showWhenLocked, turnScreenOn, FLAG_KEEP_SCREEN_ON, excludeFromRecents, singleInstance, taskAffinity ${applicationId}.wake.
- The back button does nothing. It never unlocks the phone.
- It shows a warm dark theme (not SOS red): a large time, the label, the partner line ("Aanya's alarm is ringing too" / "Aanya is up ☀ 6:34"), a primary "I'm up" button and "Snooze 10 min".
- It finishes when WakeAlarmController.state turns null.
- Add it to the AppVisibility ignore list (RisainaApp.kt:27).

4.5 Snoozed state
- The notification is cancelled.
- setAlarmClock(now+snooze) is armed for the SNOOZE_FIRE action, so the status bar shows the next alarm.
- An ongoing LOW notification on `wake_status` says "Snoozed until 6:40" and has an "I'm up" action.

4.6 SOS interplay
- When SosAlarmService.startRinging begins, call WakeAlarmController.pauseForSos(), which snoozes the wake alarm. The SOS always wins.

5. RELIABILITY

5.1 Doze
- setAlarmClock fires exactly in Doze. The receiver gets the usual short allow-list window.
- The ring itself needs no wake lock: NotificationManager plays the sound, and the full-screen intent turns the screen on.

5.2 Without exact-alarm permission
- canScheduleExactAlarms() is also true when Risaina's battery use is unrestricted (AlarmManager.java ~1604-1609). The existing battery fix in the readiness card therefore helps too.
- Otherwise the fallback is setAndAllowWhileIdle, which can be minutes late and longer in deep Doze. Windows under 10 minutes are lengthened on Android 12+ (AlarmManager.java:604-607).
- The phone reports ready = false with reason 'exact_alarms_off'. The partner sees "Not ready on Aanya's phone: Alarms & reminders is off". The owner sees a fix row that opens ACTION_REQUEST_SCHEDULE_EXACT_ALARM (SosReadinessProbe.intentFor already does this).
- A manifest receiver for ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED re-arms when the permission is granted.
- Revoking the permission kills the app and deletes all exact alarms, with no broadcast. The next app open, sync or push re-arms and reports.

5.3 Reboot and app update
- Declare RECEIVE_BOOT_COMPLETED explicitly. It is merged today only through work-runtime 2.12.0; it is a normal permission with no Play form.
- Add WakeAlarmRearmReceiver (exported="false", like WorkManager's RescheduleReceiver) for BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED and SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED. It calls arm() from the SharedPreferences store with no network.
- An occurrence that passed while the phone was off gets a quiet "missed" notice and is not rung late (more than 30 minutes late counts as passed).
- Gap: BOOT_COMPLETED arrives only after the first unlock. A phone that reboots at 3 am (a system update, or Samsung or ColorOS scheduled restart) and stays locked will miss the alarm.
  - Supporting this needs a directBootAware receiver for LOCKED_BOOT_COMPLETED and a device-protected copy of the schedule.
  - It also needs RisainaApp to stop constructing credential-storage SharedPreferences singletons at injection time (dagger.Lazy for SafetyLifecycle and friends).
  - This is phase 2. In v1 the Wake screen says it plainly.

5.4 Time and time-zone changes, and DST
- Instants are absolute, so a phone travelling to another zone keeps ringing at the same moment as the partner.
- TIME_SET and TIMEZONE_CHANGED still re-arm and refresh the labels. A future `local` mode will need this.
- DST is handled per occurrence, because only the next occurrence is ever armed and it is recomputed after each fire.

5.5 Silent mode, DND and volume
- USAGE_ALARM sound plays on the alarm stream, which silent mode does not mute. The exception is phones whose silent mode affects the alarm stream; SosReadinessProbe already detects this through MODE_RINGER_STREAMS_AFFECTED, and the Wake screen shows the same line.
- In DND, CATEGORY_ALARM / USAGE_ALARM notifications pass whenever DND allows alarms (the Android default). If DND blocks alarms (SosDiagnostics.dndBlocksAlarms) or Total silence is on, the alarm is silent, exactly like the Clock app. The Wake screen says so, and there is no bypass.
- The alarm volume is respected and never changed, unlike the SOS full-volume raise. Volume 0 shows a warning with a button to the volume panel.
- Risaina's in-app notification preference and quiet hours never suppress the ring itself. The user opted into the alarm.

5.6 Notifications and full-screen alerts
- Notifications off or a blocked channel means no ring, so ring readiness goes false and the reason is reported.
- Full-screen alerts denied on Android 14+ means a heads-up instead of the full screen (shown as an Allow row).

5.7 Phone makers (ColorOS)
- A force-stop or swipe-away cancels alarms, the same limit as the SOS (KNOWN_LIMITATIONS.md:18).
- The existing autostart guidance row covers it, and so does the phase-2 Clock backup button.
- A same-day re-arm happens on every push, sync and open.

5.8 Wallpaper and lock screen
- Nothing writes the wallpaper or the lock screen.
- The activity draws over the keyguard only while ringing, the same precedent as SosAlarmActivity (manifest 73-74).
- setAlarmClock makes Android show its standard alarm icon and next-alarm time. This is system UI; see the open questions.

6. UI

6.1 Routes and entry points
- New route WakeTogether in navigation/Routes.kt, deep link risaina://app/wake.
- Home: replace the redundant "Settings" QuickTile (HomeScreen.kt:365) with "Wake". TopRow already has the gear.
- Home: add a "Wake together" card when an alarm exists, for example "Tomorrow 6:30 am · both phones ✓" or "Aanya is up ☀ · you're snoozed".
- Settings → Experience gets a "Wake together" row.

6.2 Wake screen
- The list of alarms, each with a switch and, per person, chips showing who it rings for:
  - "You · Ready"
  - "Aanya · Ready" / "Off on her phone" / "Not set on her phone yet" / "Alarms permission off" / "Needs the new Risaina" (when ready is null)
- A readiness section that reuses the ReadinessFix intents.
- "Test on this phone": rings in 10 s through the full setAlarmClock and full-screen path, with no report.

6.3 Editor bottom sheet
- Time picker, M T W T F S S chips (none selected means "once", with a date), label, and snooze length.
- "Rings on: You [switch] · Aanya (only she can change this)".
- The two-zone lines and warnings from 1.2.

6.4 Channels
- New channels in NotificationChannels.ensure: wake_alarm (HIGH), wake_status (LOW), wake_updates (DEFAULT) and wake_updates_quiet (LOW).

7. ROLLOUT
1. Backend first. It is safe on main because it is additive.
2. Then the Android build. Partners on version 75 show as "Needs the new Risaina", never get high alarm pushes, and ignore alarm events.
3. Phase 2: Clock-app backup button, direct-boot support, a "Wake" line on the "Us" widget, and an optional `local` mode.

## File plan
- backend/src/db/types.ts: Add AlarmDoc, AlarmMember and AlarmWake interfaces. Append 'alarm.created','alarm.updated','alarm.deleted','alarm.woke' to EVENT_TYPES (the z.enum for GET /events is built from it).
- backend/src/db/collections.ts: Add `alarms: 'alarms'` to COLLECTION_NAMES, the Collections interface and getCollections.
- backend/src/db/validators.ts: Add the alarms $jsonSchema (new collection, created at boot, so no collMod). Mode and reason are plain strings, days an int array (maxItems 7), members an array with maxItems 2, and no additionalProperties:false.
- backend/src/db/indexes.ts: Add an index {coupleId:1, createdAt:1} named coupleId_createdAt on alarms.
- backend/src/modules/alarms/schemas.ts: New. CreateAlarmBody, PatchAlarmBody (expectedVersion), PutMyAlarmBody, ReportBody and WakeBody, all strict. Reuses HHmm and ProfileTimeZone; onceAt is required iff days is empty.
- backend/src/modules/alarms/repo.ts: New. CRUD with a version-guarded update, a max-10 count, and a member-scoped $set for the caller's entry.
- backend/src/modules/alarms/service.ts: New. Validation, including onceAt agreeing with time in the zone via localMinutes. Emits alarm.* events, computes the notify flag (recipient ring && enabled && member.ready !== null && change not by the recipient), keeps wake reports idempotent per member and occurrence, and emits on readiness changes only.
- backend/src/modules/alarms/routes.ts: New. GET/POST /alarms, PATCH/DELETE /alarms/:id, PUT /alarms/:id/me, POST /alarms/report and POST /alarms/:id/wake, guarded like milestones/routes.ts:19.
- backend/src/modules/shared/schemas.ts: Add the Alarm and AlarmMember Zod DTOs and export the AlarmDto type.
- backend/src/modules/shared/dto.ts: Add toAlarmDto (ISO dates, string ids).
- backend/src/app.ts: Register alarmRoutes next to milestoneRoutes (around line 250).
- backend/src/services/push/policy.ts: 'alarm.created' and 'alarm.updated' are HIGH only when payload.notify === true, otherwise NORMAL. alarm.deleted and alarm.woke fall to the default NORMAL. Document why: version 75 phones never get a high push they can't show.
- backend/src/modules/couples/purge.ts: Add this.db.alarms.deleteMany(byCouple) to the purgeData Promise.all.
- backend/src/workers/cleanup.ts: Optional. Delete one-off alarms 2 days after onceAt.
- backend/src/modules/legal/routes.ts: Privacy text: shared alarms (time, days, label, time zone, wake outcomes) are stored and purged on unlink or deletion.
- backend/test/alarms.test.ts: New integration tests (see the tests list).
- backend/test/push-policy.test.ts: Table rows for the four alarm types (with and without notify). Add alarm.created and alarm.updated to CLIENT_NOTIFIES.
- backend/test/unlink-purge.test.ts: Assert that alarms are removed on unlink.
- backend/test/isolation.test.ts: Assert another couple cannot read or change an alarm.
- docs/API.md: New 'Wake together alarms' section: shapes, endpoints, events, priorities (also add them to the table at line ~838).
- android/app/src/main/AndroidManifest.xml: Explicit RECEIVE_BOOT_COMPLETED. WakeAlarmReceiver (exported=false). WakeAlarmRearmReceiver (exported=false) with BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED and android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED. WakeAlarmActivity (showWhenLocked, turnScreenOn, singleInstance, excludeFromRecents, taskAffinity ${applicationId}.wake). No new foreground service and no new runtime permission.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmRules.kt: New pure logic: next occurrence (days, onceAt, skips, anchor zone, DST gap and overlap), the late-ring guard, and local and partner labels.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmStore.kt: New SharedPreferences JSON store (outside the destructive Room DB): alarms, armed ids, ring state, last readiness. Exposes a StateFlow.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmScheduler.kt: New. arm() and cancelAll(): setAlarmClock with a broadcast operation and a show intent, falling back to setAndAllowWhileIdle without the permission. Snooze and timeout alarms. Computes readiness and queues the report.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmReceiver.kt: New. FIRE, SNOOZE_FIRE, SNOOZE, DISMISS and TIMEOUT. Validates, posts or cancels the ring, arms the next occurrence, defers to a ringing SOS.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmRearmReceiver.kt: New. Re-arms on boot, update, time or zone change and exact-alarm grant.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmNotifier.kt: New. Insistent full-screen ring notification (own group, CATEGORY_ALARM, swipe = snooze), snoozed status, missed notice, partner notices (set, changed, is up).
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmActivity.kt: New full-screen alarm UI patterned on SosAlarmActivity: I'm up, Snooze, partner status line.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmController.kt: New singleton StateFlow of the current ring, plus partnerUp() and pauseForSos().
- android/app/src/main/java/com/risaina/app/core/alarm/WakeAlarmRepository.kt: New. API calls, apply(event) for alarm.*, refresh on open.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeReportWorker.kt: New WorkManager worker: POST wake outcome and readiness with retries.
- android/app/src/main/java/com/risaina/app/core/alarm/WakeReadiness.kt: New readiness evaluation (exact alarms or battery exemption, notifications, wake channel, full-screen, alarm volume, silent-mutes-alarms, DND blocks alarms, autostart), reusing the SosReadinessProbe fixes.
- android/app/src/main/java/com/risaina/app/core/notifications/NotificationChannels.kt: Add WAKE_ALARM (HIGH, bundled tone with USAGE_ALARM, vibration, no DND bypass), WAKE_STATUS (LOW), WAKE_UPDATES (DEFAULT) and WAKE_UPDATES_QUIET (LOW).
- android/app/src/main/res/raw/wake_alarm.ogg: New bundled wake tone, distinct from sos_alarm.
- android/app/src/main/java/com/risaina/app/core/network/RisainaApi.kt: Add the alarm endpoints.
- android/app/src/main/java/com/risaina/app/core/network/Dtos.kt: Add AlarmDto, AlarmMemberDto and the request bodies (explicitNulls=false compatible).
- android/app/src/main/java/com/risaina/app/core/sync/EventReducer.kt: New branch for alarm.created, alarm.updated, alarm.deleted and alarm.woke going to WakeAlarmRepository.apply. New Incoming.WakeSet and Incoming.WakeUp.
- android/app/src/main/java/com/risaina/app/core/notifications/RisainaNotifier.kt: Post WakeSet and WakeUp notices on the wake_updates or quiet channels.
- android/app/src/main/java/com/risaina/app/core/safety/SafetyLifecycle.kt: On sign-out or unlink, cancel all wake alarms and clear the store. On foreground, re-arm and refresh.
- android/app/src/main/java/com/risaina/app/core/sos/SosAlarmService.kt: On startRinging, call WakeAlarmController.pauseForSos() so the SOS always wins.
- android/app/src/main/java/com/risaina/app/RisainaApp.kt: Add WakeAlarmActivity to the AppVisibility ignore predicate (line 27).
- android/app/src/main/java/com/risaina/app/navigation/Routes.kt: Add a WakeTogether destination and wire it in RisainaNavHost.kt with deep link risaina://app/wake.
- android/app/src/main/java/com/risaina/app/feature/wake/WakeScreen.kt: New list, readiness and test screen, plus the editor bottom sheet (time, days, label, snooze, own ring switch, two-zone lines and warnings).
- android/app/src/main/java/com/risaina/app/feature/wake/WakeViewModel.kt: New view model over the store and repository, with partner zone data from CoupleDao.
- android/app/src/main/java/com/risaina/app/feature/home/HomeScreen.kt: Replace the 'Settings' QuickTile with 'Wake'. Add the Wake together card when an alarm exists (with HomeViewModel.kt).
- android/app/src/main/java/com/risaina/app/feature/settings/SettingsScreens.kt: Add a 'Wake together' row under Experience.
- docs/PROGRESS.md, docs/KNOWN_LIMITATIONS.md, docs/COUNCIL_DECISIONS.md, docs/PRIVACY_POLICY_DRAFT.md, docs/TESTING.md: Record the decision (D-13: same instant, notification-based ring, no foreground service), the limits (notifications required, overnight locked reboot, force-stop, DND) and the manual device checks.

## Tests
- backend alarms.test.ts: create (repeating and one-off) returns 201 and emits alarm.created with the full DTO. Version 75-style partner (ready null) gives notify=false. After the partner posts /alarms/report, an edit gives notify=true.
- backend alarms.test.ts: validation rejects a bad HH:mm, a fixed-offset zone ('+05:30'), duplicate or out-of-range days, a missing onceAt with empty days, an onceAt whose local time in the zone differs from `time`, and an 11th alarm (409).
- backend alarms.test.ts: PATCH with a stale expectedVersion returns 409. A good one increments version and emits alarm.updated change 'edited'.
- backend alarms.test.ts: PUT /alarms/:id/me changes only the caller's member entry. No route lets a user set the partner's ring flag.
- backend alarms.test.ts: POST /wake is idempotent per member and occurrence (a second call emits no event), emits alarm.woke {alarmId,userId,occurrence,outcome,at,snoozes}, and stores lastWake.
- backend alarms.test.ts: /alarms/report emits only when ready or reason changes. An armedFor-only change is stored silently.
- backend alarms.test.ts: every new document passes the alarms $jsonSchema (insert through a collection created with VALIDATORS, as the games validator test does).
- backend push-policy.test.ts: rows alarm.created/alarm.updated with {notify:true} give high, {} or {notify:false} give normal, alarm.deleted and alarm.woke give normal. CLIENT_NOTIFIES updated. 'covers every event type' still passes.
- backend outbox.test.ts / push-data-fields.test.ts: alarm pushes carry only type/eventId/coupleId/seq/pr (no label or content), and quietPolicy downgrades a notify=true push when the recipient turned notifications off.
- backend unlink-purge.test.ts: alarms are deleted on unlink and by the cleanup re-run. isolation.test.ts: another couple gets 404 on GET/PATCH/DELETE/wake.
- backend events: GET /events returns alarm.* events (the z.enum includes them), and EventPublisher accepts them under the pattern validator.
- android WakeAlarmRulesTest: next occurrence across weekdays and week wrap, one-off past and future, skip of the next occurrence, disabled or ring=false give null. America/New_York 2027-03-14 02:30 resolves to 03:30 EDT. 2026-11-01 01:30 resolves to the earlier offset. Anchor Asia/Kolkata 06:30 shows as 01:00 Europe/London before 2027-03-28 and 02:00 after. A phone zone different from the anchor gives the same instant. The late guard rings at +29 min but not at +31 min.
- android WakeAlarmStoreTest (FakeSharedPreferences, as in the existing tests): round-trip of alarms, ring state and armed ids. Unknown mode is kept but not scheduled.
- android WakeReadinessTest: each missing condition (exact alarms without battery exemption, notifications off, channel blocked, full-screen off on SDK 34+, alarm volume 0, silent mutes alarms, DND blocks alarms) gives ready=false with the expected reason, and exact alarms granted OR battery-exempt gives ok.
- android EventReducer test: alarm.* events update the store and return Incoming.WakeSet only when notify is true and the actor is the partner. alarm.woke returns Incoming.WakeUp. The cursor advances. A version-75-style unknown type is still ignored.
- android scheduler test (AlarmManager behind an interface): setAlarmClock is used when allowed, setAndAllowWhileIdle plus a not-ready report otherwise. Removed alarms are cancelled. Each alarm's PendingIntent identity is distinct and does not collide with SOS request code 9120.
- Manual device matrix, to run later by the main session (not in this read-only task): Doze with the screen off overnight, reboot then unlock, reboot left locked past the alarm (expect missed in v1), manual time and zone change, both phones in different zones, silent/vibrate/DND alarms allowed/DND alarms blocked/Total silence, notifications denied, full-screen denied on Android 14+ (heads-up for 60 s while the sound keeps looping), exact alarms denied then granted (broadcast re-arm), shade pulled down mid-ring (auto-snooze re-rings), SOS arriving during a wake ring, ColorOS swipe-away, partner on version 75, alarm volume 0.

## Risks
- The ring is a notification, so with Risaina's notifications off or the wake channel blocked nothing rings, unlike the SOS (SosAlarmService.kt:39-42). Readiness must report this to both partners.
- FLAG_INSISTENT stops the sound when the notification shade is opened (Notification.java:647-652). Without the timeout auto-snooze, a half-asleep shade pull would silence the alarm for good.
- On Play installs on Android 14+, full-screen alerts are off by default (declaration 'Other'). The alarm then shows as a heads-up that persists 60 s. That the insistent sound keeps looping after that is not verified.
- Vibration of a notification alarm may be suppressed in full silent mode (the SOS uses VibrationAttributes USAGE_ALARM through the Vibrator). Unverified; check on device.
- Android 15+ 'notification cooldown' could lower the volume of an alert that follows other Risaina notifications. Likely not applied to a single insistent CATEGORY_ALARM post; verify.
- If the phone reboots at night and stays locked, the alarm is missed: BOOT_COMPLETED comes only after the first unlock, and direct boot needs refactoring of RisainaApp injection (credential-storage SharedPreferences at SosLocalState.kt:34).
- Phone makers' force-stop (ColorOS swipe-away) deletes all AlarmManager alarms, the same limit as the SOS (KNOWN_LIMITATIONS.md:18). Only reopening Risaina, a push or a sync re-arms.
- If the live Atlas events validator is still the old enum version (dbAdmin never granted), alarm.* events are socket-only: no push and no replay (publisher.ts:62-80). Check the Render boot log after deploy.
- Revoking 'Alarms & reminders' kills the app and deletes exact alarms with no broadcast (AlarmManager.java:159-169). Detection waits for the next app start, push or sync.
- The partner's phone learns about a new or changed alarm through a normal-priority push unless notify=true. An alarm set minutes before it rings, while the partner's phone is in deep Doze, may not be armed in time. The UI shows 'Not set on Aanya's phone yet'.
- setAlarmClock makes Android show its alarm icon and 'next alarm' time (status bar, Quick Settings, some lock-screen clocks). This is standard system UI, not a lock screen change by the app, but it should be confirmed against the 'never change the lock screen' rule.
- Consent and abuse: one partner can make the other's phone ring. Mitigated by the visible notice on receipt, a one-tap 'Not on my phone', no DND bypass, and unlink. The Terms and Data safety may need a line.
- The anchor IANA zone in the alarm reveals the creator's zone even if 'My local time' is off.
- Room's destructive migration would wipe a Room-based schedule. Keeping it in SharedPreferences adds a second source of truth, which must be cleared on sign-out or unlink.

## Open questions
- Confirm 'same instant, anchored to the creator's zone' as the only v1 mode, or ship 'each at their own local time' too. The model reserves `mode` for it.
- Should an alarm the partner creates ring on my phone by default (with the notice and one-tap opt-out, as proposed), or should I have to tap 'Join' first?
- Snooze length (10 min proposed), ring length (10 min), and one automatic snooze before 'missed': are these right?
- Respect the user's alarm volume (proposed), or offer an opt-in 'ring at full volume' like the SOS, which needs save, restore and crash recovery?
- Is the system's own alarm icon and 'next alarm' text, which any setAlarmClock alarm shows, acceptable under the 'never change the wallpaper or lock screen' rule?
- Was dbAdmin granted on Atlas, so that the live `events` collection has the pattern validator? Otherwise alarm events (and possibly the newer SOS and location types) are socket-only.
- Should 'Aanya is up' also notify a partner who opted out of that alarm, or only someone whose own alarm rang for that occurrence?
- OK to replace the redundant 'Settings' tile on Home with 'Wake', given the gear in the top row?
- Is direct-boot support (ringing after an overnight reboot before first unlock) needed in v1, or can it wait for phase 2 with a clear note in the UI?
- Should the Play Data safety form and /privacy be updated before this ships (shared alarms are user content stored on the server)?
- Is Render on the paid Starter plan? This does not affect ringing, which is phone-owned, but it affects how fast 'is up' and new alarms arrive.
