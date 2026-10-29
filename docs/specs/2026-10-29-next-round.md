# Next round (2026-10-29): notifications, lists, calendar, wake together, message actions

The owner asked for:
1. Notifications arrive twice.
2. Lists ("things to do together") lose saved items. They need checkboxes, numbering, and many notes.
3. A synced alarm, so both partners wake at the same time.
4. An in-app calendar with reminders for important dates. Default reminders for the anniversary and the
   partner's birthday. Dates can be picked from notes.
5. A to-do and calendar option in the message section.

This round goes to GitHub `main`, which auto-deploys the backend. It is not a Play release.

## Sources

Five investigation reports hold the detailed designs, file plans and tests. Read the one for your area:
`/private/tmp/claude-501/-Users-rishabhranjan-Desktop-Risaina/8d3b72b8-bc98-46ee-a936-322339046d05/scratchpad/next/`
- `dup-notifications.md`
- `lists.md`
- `calendar.md`
- `sync-alarm.md`
- `message-actions.md`

**Where a report and this spec disagree, this spec wins.**

## Facts checked on the live database (2026-10-29, read only)
- `events`: `type` uses the pattern `^[a-z][a-z_]*\.[a-z][a-z_]*$`, so new dotted event types (`calendar.created`,
  `alarm.woke`) are accepted.
- `lists.kind` is `enum ["note","checklist"]`. **No new list kinds.** Use the new format fields instead.
- `messages.type` is `enum ["text","emoji","photo","voice"]`. **No new message types.** The v75 app would also crash on
  one, because `MessageType.fromWire` uses `entries.first{}`.
- `milestones.kind` is a fixed enum. Leave milestones alone.
- No collection sets `additionalProperties`, so new optional fields on existing documents are accepted.
- The DB user has `readWriteAnyDatabase` (no collMod). New collections are created at boot (`VALIDATORS` + `createCollection`).
  New collection validators must use plain strings or patterns, never enums, and must not set `additionalProperties`.
- Live pushes go out once per action. Device rows and the outbox were checked and are correct (see `dup-notifications.md`).
- If local DNS fails on SRV lookups (`querySrv EBADRESP`), add `dns.setServers(['8.8.8.8','1.1.1.1'])` to throwaway scripts.

## Global rules
- **Backend: additive only.** Version 75 (in Play review) and the debug APK on the couple's phones keep using the server.
  - Never remove or rename a field, route or event.
  - Never change what an existing response means.
  - Existing tests and the documented behaviour in docs/API.md stay true.
- **Android: Play-safe.**
  - No `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `systemExempted`, new foreground-service types or
    background location.
  - Allowed: `SCHEDULE_EXACT_ALARM` (granted by the user), `USE_FULL_SCREEN_INTENT`, and `RECEIVE_BOOT_COMPLETED`
    (declare it explicitly; it is already merged from WorkManager).
  - The SOS foreground service (`specialUse`) is for the SOS only. Never reuse it.
- Never change the phone's wallpaper or lock screen.
- Every new notification gets its own explicit group key. Android 15+ auto-groups notifications that have none.
- Writing style follows the existing code: comments explain why, names match the surrounding code, no new libraries
  unless a step says so.
- Tests:
  - backend: `pnpm run typecheck && pnpm run lint && pnpm test`;
  - Android: `./gradlew :app:testDebugUnitTest :app:assembleDebug`, with JDK 17 at
    `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
  - Add unit tests for every pure function (planners, parsers, mergers, rules).

## Decisions (they override the reports)

D1. **The calendar is in the app**, as in `calendar.md`: a new `calendarEntries` collection, derived occasions, and local
    reminders on both phones.
- The phone-calendar handoff (`ACTION_INSERT`) from `message-actions.md` is only a secondary button, "Also add to my phone's
  calendar", in the entry screen. It needs no permission.
- Message "cards" (text + `card` object) are **not** in this round. A message links to calendar entries through
  `source.messageId`, and the bubble shows a chip "📅 In your calendar · Fri 14 Nov".

D2. **One Room migration for the whole round**: version 6, `@AutoMigration(from = 5, to = 6)`. It contains:
- `lists`: new columns `marker TEXT NOT NULL DEFAULT 'none'`, `checkboxes INTEGER NOT NULL DEFAULT 1`,
  `syncedAt INTEGER NOT NULL DEFAULT 0`;
- the new table `list_drafts`;
- the new table `calendar_entries`;
- the new table `reminder_log`.

The foundation package (F2) owns it, and nobody else changes the Room version or the entity set. Keep
`fallbackToDestructiveMigration` for versions before 5. Commit the exported `schemas/.../6.json`. Wake-together alarms live in
SharedPreferences (`WakeAlarmStore`), not Room.

D3. **Push priority:**
- `calendar.*` always use the existing NORMAL default. The receiving new app posts the "Raina added … to your calendar" notice
  when it syncs an event whose payload has `notify: true` and that came from the partner.
- `alarm.created` and `alarm.updated` are HIGH only when `payload.notify === true`. The server sets `notify` only when all of
  these hold:
  - the recipient is a ringing member;
  - the alarm is enabled;
  - the change is a create or an edit, not their own member change;
  - the recipient's `member.ready !== null`, meaning their phone runs a build that understands alarms.
- `alarm.deleted` and `alarm.woke` are NORMAL.
- `list.*` stay as they are.

D4. **Home:**
- The "Timeline" tile becomes "Calendar". The Timeline stays as "Our story" inside Calendar and Settings.
- The "Settings" tile becomes "Wake". The top row already has the gear.
- Add a "Coming up" card: up to 3 stored entries in the next 7 days, plus to-dos that are due or overdue. Hide it when empty.
- Add a "Wake together" card when an alarm exists.

D5. **Undated to-dos** from chat go into a pinned shared checklist titled "To-dos", created if missing. A to-do with a date is a
    calendar entry of kind `todo`.

D6. **Reminder defaults**, kept per device in DataStore:
- anniversary: on, 7 days before and on the day;
- partner's birthday: on, 7 days, 1 day and on the day;
- my birthday: off;
- next visit: off;
- yearly milestones: off;
- all-day reminder time: 09:00;
- master switch: on.

Reminders respect the in-app master notification switch, and use the quiet channel during quiet hours. No "surprise" private
entries in this round.

D7. **The date parser** is `core/domain/DateDetector.kt`, following the spec in `message-actions.md` §4, with the API
    `find(text, reference, order)` and `suggestTitle`. The calendar's "pick from notes" uses the same parser.

D8. **Wake together** follows `sync-alarm.md` Option A:
- `setAlarmClock` + a full-screen-intent notification on an alarm channel (USAGE_ALARM, FLAG_INSISTENT);
- no foreground service;
- same instant anchored to the creator's IANA zone; per-person opt-out and skip;
- "I'm up" reported to the partner, without silencing their phone.
- Phase-2 items stay out: direct boot, the Clock-app backup, `local` mode.

D9. **Notifications fix** follows `dup-notifications.md`:
- A1: the lock card never repeats something that has its own notification.
- A2: one MessagingStyle conversation notification for chat, cleared when read.
- A3: update, skip or alert after a quick alert, without setSilent.
- A4: explicit groups.
- A5: a handled-push ring buffer.
- A6: panic SOS cancels the "is sharing" notice.
- A7: no expedited work on API 30 and below.
- A8: game alert-once within 30 s.
- B1: prune dead duplicate device rows.
- B2: optional `sdkInt` on devices.
- B3: outbox `leaseId`.

D10. **Lists** follow `lists.md`:
- the P0 hotfix items A1–A8;
- the local-first engine B (drafts in Room, `ListMerge.merge3`, `ListSaveWorker`, version checks on every Room write);
- backend C1–C4: format fields, `addedBy`/`addedAt`, `POST /lists/:id/convert`, `GET /lists/:id`;
- the editor D: rows, Enter splits, Backspace joins, checkboxes, numbers, bullets, who ticked what, reorder by drag or
  Move up/down, undo on delete;
- the overview E: grid, previews, filters, and a new sheet with To-do list, Numbered list, Bulleted list and Note.

`clientKey` on POST /lists (C5) is included as well.

## API contract (backend implements it; Android builds against it)

All routes are under `/api/v1` and use Bearer + requireCouple, unless a route says otherwise. Ids are 24-hex strings, dates
are `YYYY-MM-DD`, times are `HH:mm`, zones are IANA, and timestamps are ISO strings.

### Lists (additive)
- `List` response gains:
  - `marker: 'none'|'number'|'bullet'` (default `'none'`);
  - `checkboxes: boolean` (default `true`).
- `ListItem` gains `addedBy: Id|null` and `addedAt: ISO|null`.
- POST /lists and PATCH /lists/:id accept optional `marker` and `checkboxes`. POST /lists also accepts an optional `clientKey`
  (≤ 64 characters). Repeating a clientKey returns 200 with the existing list.
- `POST /lists/:id/convert {to:'note'|'checklist', marker?, checkboxes?, expectedVersion?}` → `{list}`.
  - Note → checklist: body lines become items (strip markers, `[ ]` / `[x]` set done, cut to 300 characters, keep at most 200;
    the rest stays in the body).
  - Checklist → note: items are rendered as lines.
  - It emits `list.updated`.
- `GET /lists/:id` → `{list}`, or 404.

### Calendar (new)
`CalendarEntry`:
```
{ id, coupleId, createdBy, updatedBy, kind: 'event'|'todo', title, notes|null, emoji|null,
  date, endDate|null, time|null, durationMinutes|null, timeZone|null,
  repeat: 'none'|'weekly'|'monthly'|'yearly', repeatUntil|null,
  reminders: int[], remindUserIds: Id[], pinned: boolean,
  done: boolean, doneAt|null, doneBy|null,
  source: {kind:'message'|'list'|'list_item', messageId?, listId?, itemId?}|null,
  clientKey|null, version, createdAt, updatedAt }
```
In responses, `kind` and `repeat` are plain strings. In requests they are enums.

Routes:
- `GET /calendar` → `{items}`.
- `POST /calendar` → 201 `{entry}`. A repeated clientKey returns 200 with the existing entry.
- `PATCH /calendar/:id` takes partial fields, `done?`, `expectedVersion?` and `notifyPartner?`. A stale version gets
  409 `version_conflict` with `details.entry`.
- `DELETE /calendar/:id` → 204.

Validation and the 1000-entry cap follow `calendar.md` §2–3. POST also takes `notifyPartner?`.

Events:
- `calendar.created {entry, notify}`
- `calendar.updated {entry, notify}`
- `calendar.deleted {entryId}`

Other changes: purge with the couple, include in `GET /me/export` as `calendar`, and add a per-user write rate limit.

### Wake together (new)
`Alarm`:
```
{ id, coupleId, createdBy, updatedBy, mode: string ('together'), label|null, time, timeZone,
  days: int[], onceAt|null, enabled, snoozeMinutes, version, createdAt, updatedAt,
  members: [{ userId, ring, skips: ISO[], ready: boolean|null, reason|null,
              armedFor: ISO|null, reportedAt: ISO|null,
              lastWake: {occurrence, outcome:'up'|'missed', at, snoozes}|null }] }
```

Routes:
- `GET /alarms` → `{items}`.
- `POST /alarms` takes `{label?, time, timeZone, days, onceAt?, ringOnMyPhone?=true, snoozeMinutes?=10, mode?='together'}` and
  returns 201 `{alarm}`.
- `PATCH /alarms/:id` takes `{expectedVersion, …}` and returns `{alarm}`, or 409.
- `DELETE /alarms/:id` → 204.
- `PUT /alarms/:id/me` takes `{ring?, skip?: {occurrence, skip}}` and returns `{alarm}`. It changes only the caller's member entry.
- `POST /alarms/report` takes `{items:[{alarmId, ready, reason|null, armedFor|null}]}` (at most 10) and returns 204.
- `POST /alarms/:id/wake` takes `{occurrence, outcome, at, snoozes}` and returns `{alarm}`. It is idempotent per member and
  occurrence.

Limit: 10 alarms per couple.

Events:
- `alarm.created {alarm, notify}`
- `alarm.updated {alarm, change, notify}`
- `alarm.deleted {alarmId}`
- `alarm.woke {alarmId, userId, occurrence, outcome, at, snoozes}`

Other changes: purge with the couple, and include in the export as `alarms`.

### Devices and outbox (hardening)
- PUT /devices accepts an optional `sdkInt` (21–99). GET /devices/health returns `sdkInt|null`.
- The cleanup worker prunes dead duplicate device rows (rule B1).
- The outbox uses `push.leaseId` (B3).

### Docs
- docs/API.md documents everything above.
- The privacy policy (`backend/public/legal/privacy.html`) gains rows for calendar entries and shared alarms (what is stored,
  that the partner sees it, and that it is deleted on unlink or account deletion), and its date is updated.

## Work packages

Foundation (in parallel):
- **F1 Backend.** Everything in the API contract plus B1–B3, with tests.
- **F2 Android foundation.**
  - DTOs and `RisainaApi` methods for every new or changed endpoint above, with tolerant decoding (unknown strings never crash).
  - Room v6 (D2) with entities, DAOs and the exported schema.
  - Core models.
  - `MessageType.fromWire` → `firstOrNull ?: UNKNOWN`, rendered as its text.
  - Repository skeletons: `CalendarRepository` (refresh, create, update, delete, setDone) and `WakeAlarmRepository` (API wrapper).
  - The list API additions in `ListsRepository` (convert, get, appendItems).
- **F3 Android notifications fix.** D9, Android parts A1–A8 and A9 (the device-count line on the delivery card).

Features (in parallel, after the foundation is merged):
- **P1 Lists.** D10, Android parts: sync engine, merge, editor, overview.
- **P2 Calendar + message actions.**
  - Calendar screens, editor, occasions, ReminderPlanner and ReminderScheduler, receivers.
  - Home "Coming up" and the Calendar tile.
  - Message long-press sheet, the composer "+" sheet, date suggestion chip and bubble chips.
  - DateDetector.
  - "Add to calendar" from notes and list items.
- **P3 Wake together.** Everything in D8 on Android: store, rules, scheduler, receivers, ring notification, activity, Wake
  screen and editor, Home tile and card, partner notices, reports.

Then: merge, full build and tests, adversarial review, emulator checks, push.
