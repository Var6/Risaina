# message-actions

## Summary
The app has no calendar today. The nearest thing is the Timeline's milestones, which are date-only, have a fixed kind enum and feed the countdown widget. Long-pressing a message only offers Delete, on your own sent messages. Recommendation: "Add to calendar" opens the phone's own calendar app through Android's new-event screen (ACTION_INSERT on CalendarContract). That needs no permission, alarm, service or Play declaration, and the calendar app handles reminders. "Add to to-do" adds items to the existing shared checklists. The shared part is a small card in the chat that either partner can act on ("Add to my calendar", "Open list"). No new message type is needed, and none is possible: the live messages validator has had a strict type enum since v1 and cannot be changed without collMod. v75 would also crash on an unknown type, because MessageType.fromWire uses entries.first{} (checked in commit 65b9116). Cards are instead ordinary `text` messages with a new optional `card` object and a readable fallback line. Every deployed validator accepts the extra field, and v75 ignores it (ignoreUnknownKeys) and shows the text. The only backend change is that optional field, so there is no new collection or event type, and to-do appends use the existing lists PATCH with expectedVersion and client-made item ids, which makes them idempotent. Dates come from a small deterministic java.time parser in core/domain. It reads times relative to the message's own timestamp, gives confident and unsure matches, and offers a second reading for ambiguous ones such as "next Fri", "3/4" and "at 8". The person always confirms in a sheet. Baseline backend messages tests pass (4/4); nothing on Android was built or run.

## Root causes
- [verified] A new message type would crash the v75 app (and the current app) when it receives such a message.
  - android/.../core/model/Models.kt:79-82 `fun fromWire(v: String) = entries.first { it.wire == v }` throws NoSuchElementException. It is called from Mappers.kt:128-135 (MessageEntity.toDomain), EventReducer.kt:102 (message.created during sync and push handling) and MessageRepository.kt:53 (recent(), which drives MessagesViewModel and Home). The same code is in the v75 commit: `git show 65b9116:.../Models.kt` line 81 and EventReducer.kt lines 98-102.
- [verified] The live database would reject a new message type in any case: the messages validator's type enum is strict and has been since v1, and the Atlas user cannot collMod.
  - backend/src/db/validators.ts:228-249 `type: { enum: [...MESSAGE_TYPES] }`. The v1 commit c97d7c4 validators.ts:178-196 has the same enum. db/types.ts:503-507 and docs/API.md:526-533 give exactly this as the reason games got their own collection.
- [verified] An optional `card` field on a text message is backward compatible. Every deployed messages validator accepts it, and v75 ignores it and shows the fallback text.
  - Neither the v1 nor the current messages validator sets additionalProperties (`git show c97d7c4:backend/src/db/validators.ts` has 0 occurrences). viewOnce, openedAt and viewOnceExpiredAt were added the same way (validators.ts:243-246, types.ts:206-210). On v75, NetworkModule.kt:49-50 has `ignoreUnknownKeys = true` (same in 65b9116), MessageDto.type is a String (Dtos.kt:98-114), and Bubble renders TEXT as m.text (MessagesScreen.kt:322).
- [verified] A new APK sending `card` to a server that has not been updated gets 400, because the create body is strict.
  - backend/src/modules/messages/schemas.ts:4-17 `.strict()`. plugins/error-handler.ts:32-41 maps it to validation_failed with per-issue details. The Android MessageRepository.post (MessageRepository.kt:93-114) marks non-retryable failures as FAILED.
- [verified] Reusing Timeline milestones as the calendar would change v75's widgets and Timeline: everyday plans would take over the countdown widget and pile up under 'Already happened'.
  - WidgetSnapshotUpdater.kt:164-169 picks the nearest upcoming milestone of any kind when no next meeting is set. TimelineScreen.kt:119-128 lists past milestones. The milestones validator (validators.ts:330-345) has a strict kind enum and a date-only field, so no 'plan' kind and no time are possible on the live DB.
- [verified] Long-press today only offers Delete, and only on your own sent messages. The composer has a photo button, a 🎲 button, the text field and mic/send.
  - MessagesScreen.kt:233 `onLongPress = { if (mine && m.sendState == MessageSendState.SENT) toDelete = m }`, :288-296 delete dialog, :318 combinedClickable, :389-406 composer row, :391 🎲 IconButton.
- [verified] Lists have no append endpoint. PATCH replaces the whole items array under optimistic concurrency, and the server keeps a client-supplied id for a new item, so a client-side append with client ids is safe and idempotent without any backend change.
  - lists/schemas.ts:9-12 (ItemId [A-Za-z0-9_-]{1,32}), :35-58 (PatchListBody, items replace, expectedVersion). service.ts:66-107 (CAS via patchAtVersion, 409 version_conflict carries the current list). service.ts:134-154 mergeItems keeps item.id when given. Android ListsRepository.kt:62-85 already refreshes on 409.
- [verified] A Room schema bump with today's builder would wipe the local database, including the outbox of unsent messages, unless an explicit or auto migration is added.
  - RisainaDatabase.kt:20 version = 5, :46 fallbackToDestructiveMigration(dropAllTables = true). exportSchema = true and android/app/schemas/.../5.json exists, so an AutoMigration 5→6 is possible.
- [likely] If a stored card ever fails the response schema, GET /messages would fail for the whole page with a 500 response-serialization error, so the DTO must sanitise it.
  - plugins/error-handler.ts:44-48 maps isResponseSerializationError to 500. Responses are Zod-typed (messages/schemas.ts:44-46).
- [possible] Whether the live `events` validator is still the v1 strict enum (which would make list.* events WebSocket-only, with no push or replay) cannot be confirmed from the repo. This design adds no event type, so it is unaffected, but to-do appends inherit the existing list.updated behaviour.
  - publisher.ts:62-80 degrades code-121 rejections to live-only. validators.ts:650-663 only logs stale types at boot (db/index.ts:21-27). The health route does not expose it. docs/PROGRESS.md:94 shows the problem existed on Atlas once. MY_SETUP.md:37-45 suggests the live `risaina` DB may have been created later, with the pattern validator.

## Current state
Messages (android/app/src/main/java/com/risaina/app/feature/messages):
- MessagesScreen.kt (437 lines) merges messages and game cards into one reversed LazyColumn (:121-125, :208-237).
- Bubble (:306-377) renders TEXT, EMOJI, PHOTO (incl. view-once) and VOICE by `when (m.type)` (:321).
- Long-press opens the delete confirm dialog, only for your own SENT messages (:233, :288-296).
- Composer (:379-408) has a quick-emoji row, then photo button, 🎲 game button (:391, opens GamePickerSheet via vm::openGamePicker), text field, and mic/send.
- MessagesViewModel.kt combines 7 flows (:101-120). send() (:254-265) goes through MessageRepository.sendText. Text messages are optimistic: a PENDING Room row plus an outbox entry, and post() builds CreateMessageRequest from the MessageEntity (MessageRepository.kt:61-114).
- Message model: Models.kt:79-104 (MessageType.fromWire throws on unknown). DTO: Dtos.kt:98-118. Entity: Entities.kt:25-43. Room v5 with destructive fallback: RisainaDatabase.kt:20, :46.

Lists (feature/lists):
- ListsScreen and ListDetailScreen (ListsScreens.kt:73-263) cover notes (body ≤ 10k) and checklists (items ≤ 200, text ≤ 300).
- Debounced CAS saves through ListsViewModel/SaveChain/ListEditorSync (ListsViewModel.kt:83-139, ListEditor.kt). PasteParser.split turns pasted lines into items (ListsViewModel.kt:32-39).
- ListsRepository.save does PATCH with expectedVersion and refreshes on 409 (ListsRepository.kt:62-85).
- Backend /lists: GET, POST, PATCH (whole items array, expectedVersion) and DELETE, with list.* events (backend/src/modules/lists). There is no append endpoint and no due dates.

Calendar:
- No calendar feature, calendar permission or CalendarContract use exists.
- The Timeline (feature/timeline) holds the anniversary, the next meeting (couple.nextMeetingDate) and milestones {title, date YYYY-MM-DD, kind anniversary|meeting|custom}. TimelineScreen.kt has a public DateDialog (:139-147). A CalendarIcon already exists in feature/common/Icons.kt:58.
- Partner.timeZone (IANA, optional) and Occasions.partnerClock (Occasions.kt:61-73) already handle the partner's local time.

Backend messages:
- POST /messages strict body {type, text?, mediaId?, idempotencyKey, viewOnce?} (messages/schemas.ts:4-42). The service stores MessageDoc and emits message.created with {message: dto} (service.ts:36-108).
- toMessageDto: shared/dto.ts:161-179. Zod Message: shared/schemas.ts:255-275.
- Push policy sends message.created at high priority (services/push/policy.ts:42-48). The notifier body is m.text (RisainaNotifier.kt:135-142).
- The widget's love-note slot uses the partner's latest text/emoji message (WidgetSnapshotUpdater.kt:120).

Reusable Zod pieces: CalendarDate, HHmm, TimeZone (shared/schemas.ts:34-39), canonicalTimeZone (lib/time.ts:63). Logger redaction list: lib/logger.ts:7-60.

Tests:
- Backend: Vitest. backend/test/messages.test.ts ran 4/4 green here. games-more.test.ts:674-720 has the frozen-live-validator pattern with fixtures/games-validator.live.json.
- Android: JUnit 4 unit tests under app/src/test (e.g. core/domain/OccasionsTest.kt).

Manifest: has SCHEDULE_EXACT_ALARM and USE_FULL_SCREEN_INTENT, no calendar permissions, and a <queries> block for OEM packages only (AndroidManifest.xml:198-211).

## Design
DECISIONS
- Calendar: the phone's own calendar app, through Android's new-event screen (Intent.ACTION_INSERT on CalendarContract.Events.CONTENT_URI).
  - No READ/WRITE_CALENDAR, no alarms, no foreground service, nothing new to declare to Play.
  - The calendar app's own screen is the final confirmation, and it does reminders and sync.
- To-do: the existing shared checklists.
- Shared part: a card in the chat (plan or to-do) that either partner can act on.
- Cards are ordinary `type:'text'` messages with a new optional `card` object, and their `text` is a readable fallback line. No new message type, collection or event type. A new event type might be live-only if the production events validator is still the v1 enum.

1. BACKEND (additive only)
Shapes, in shared/schemas.ts:
  MessageCard (discriminated on kind; each variant .strict()):
  - Plan: { kind:'plan', title: 1..120 trimmed, date: CalendarDate, time: HHmm|null (null = all day), endTime: HHmm|null (requires time; an end at or before the start means the next day), timeZone: TimeZone (IANA, stored canonical via canonicalTimeZone) }
  - Todo: { kind:'todo', listId: IdString, listTitle: 1..120, items: [{ id: ItemId, text: 1..300 }] with 1..20 items }
  Message gains `card: MessageCard.nullable()` (null on every older message).
POST /messages: body adds `card?: MessageCard`. superRefine rules:
  - card is only allowed with type 'text' (400 validation_failed, path /card).
  - text is still required: it is the client-built fallback, ≤ 2000.
  - listId is NOT looked up: the card is a snapshot plus a pointer, resolved on the reader's phone inside its own couple. That gives no cross-couple leak, and an outbox replay after a list was deleted still sends.
  - Idempotency is unchanged.
MessageDoc gets `card?: MessageCardDoc`. Write it only when present, never `card: null`.
validators.ts messages: add `card: { bsonType: ['object','null'] }` to properties, never to required. This only tightens fresh databases; the live one already accepts it.
toMessageDto: `card: MessageCard.safeParse(m.card ?? null)` → data, or null on failure. A malformed or future card can then never 500 GET /messages.
Events and push: unchanged. message.created carries the card in the DTO, it is pushed high, and the notification shows the fallback text.
Logger: redact 'card' and '*.card'.
Docs:
- API.md "Message cards" section.
- COUNCIL_DECISIONS D-13 "Plans and to-dos are text messages with an optional card", giving the validator and v75 reasons.
Rollback safety: an older backend's Zod object strips the unknown `card` from responses, so cards degrade to their text.

2. COMPATIBILITY
- v75 and the current debug APK:
  - The card is ignored (ignoreUnknownKeys), so the bubble, notification, Home "latest" and love-note widget all show "📅 Dinner at Olive · Sat 31 Oct, 8:00 pm" or "☑️ Groceries: Buy milk".
  - Long-press Delete still works on your own card.
- Harden the new app for the future: MessageType.fromWire becomes `firstOrNull ?: UNKNOWN`, rendered as the text, or "Update Risaina to see this" when the text is null.
- New APK on an old server (before deploy): the strict body gives 400. Ship the backend first. MessageRepository.post also falls back once: if a request with a card gets 400 validation_failed with a detail keyword `unrecognized_keys`, it re-POSTs the same idempotencyKey without the card, so the plan still arrives as text.

3. ANDROID DATA LAYER
core/model/MessageCard.kt:
  sealed interface MessageCard {
    Plan(title, date: LocalDate, time: LocalTime?, endTime: LocalTime?, zone: ZoneId)
    Todo(listId, listTitle, items: List<TodoRef(id, text)>)
    Unknown(kind)
  }
  Message.card: MessageCard? = null.
Dtos.kt:
- Flat MessageCardDto(kind: String, title?, date?, time?, endTime?, timeZone?, listId?, listTitle?, items?). It is flat so an unknown kind still decodes instead of throwing.
- MessageDto.card and CreateMessageRequest.card default to null. explicitNulls = false (NetworkModule.kt:51) omits the null, so requests without a card are byte-identical to today's.
Room:
- MessageEntity.cardJson: String? = null.
- RisainaDatabase version 6 with @AutoMigration(from = 5, to = 6), i.e. ADD COLUMN cardJson TEXT. Without it the destructive fallback would drop the outbox of unsent messages.
- Mappers decode with runCatching. A bad JSON gives null, and a bad zone gives Unknown.
MessageRepository.sendCard(text, card): same optimistic PENDING row and outbox as sendText, with cardJson set. post() adds the card, so it works offline and survives restarts.
ListsRepository.appendItems(listId, texts): AppResult<List<String>>:
- Makes ids "c" + 15 base62 chars (SecureRandom).
- PATCH items = current + new (skipping ids already present), expectedVersion = local version.
- On 409: refresh() (already done in save), re-read the DAO, retry up to 3 times.
- Idempotent because the server keeps client ids (mergeItems).
- Errors: >200 items → "That list is full"; 404 → "That list was deleted"; offline → "Lists need a connection".
- For a new list: create(title, CHECKLIST, items) in one request, taking the ids from the response.
ListsRepository.setDone(listId, itemId, done): same compare-and-set loop, used by the to-do card's checkbox.

4. DATE DETECTION: core/domain/DateDetector.kt (pure Kotlin + java.time, about 250 lines, no library)
  enum class DateOrder { DMY, MDY }
  - DateOrder.forLocale(locale) reads the locale's SHORT date pattern: en-IN and en-GB give DMY, en-US gives MDY.
  data class DateHit(range: IntRange, date: LocalDate, time: LocalTime?, endTime: LocalTime?, confident: Boolean, dateGuessed: Boolean, alternatives: List<Pair<LocalDate, LocalTime?>>)
  DateDetector.find(text, reference: ZonedDateTime, order): List<DateHit> (in text order)
  DateDetector.suggestTitle(text, hits): String
reference:
- For a message: its createdAt in its author's zone (partner zone if known, else the device's). "tomorrow" in Tuesday's message therefore means Wednesday.
- For the composer and notes: now, in the device zone.
Lexing works on a per-char lowercased copy so indices match the original text.
- Relative days: today, tonight/tonite, tomorrow (+ tmrw, tmr, tomorow, tommorow, 2moro), day after tomorrow. Longest match first.
- Weekdays:
  - Full names always match.
  - Short forms (mon, tue/tues, wed, thu/thur/thurs, fri, sat, sun) match only when capitalised, after on/this/next/coming/by/till/until/before, or right before a time. "I sat down", "the sun" and "we wed" therefore don't match.
- Month-name dates: "25 Dec", "25th of December", "Dec 25", "December 25th, 2027". A month name never matches without a day number ("I may", "march on").
- Numeric dates:
  - ISO yyyy-mm-dd.
  - d/m, d/m/yy, d/m/yyyy with '/'.
  - '.' and '-' only with a year, so "7.30", "2-1" and "7-9pm" stay free.
  - DateOrder decides the reading, swapped when only one reading is valid ("13/12", "12/25"). Not when glued to other digits, letters or decimals.
- Relative spans: in N days/weeks/hours/minutes. N is digits, one…twelve, or a/an.
- Times:
  - H am/pm (also a.m./p.m.), H:MM or H.MM with am/pm, HH:MM in 24-hour form, noon/midday, midnight.
  - Bare "at H" and "@H".
  - Ranges H(-|–|to|till|until)H; the end's am/pm is applied to the start when the start has none and start < end.
- Dayparts (morning/afternoon/evening/night/tonight) are only am/pm hints, never a time.
Combining: a date part and a time part separated only by spaces, commas, at, @, on, by or from (≤ 12 chars) become one hit, in either order.
Resolving:
- today, tomorrow and day after = reference date +0, +1, +2. tonight = today with a pm hint.
- A bare weekday is the next one strictly after today. If it is today's weekday, the alternative is today.
- "this <wd>" counts today (0–6 days ahead).
- "next <wd>" is that day in the following Monday-based week, with the coming one as the alternative when they differ.
- Day+month without a year: this year if on or after today, else next year. 29 Feb goes to the next leap year.
- A 2-digit year means 20yy. An invalid date gives no hit.
- A time without a date is today if later than the reference, else tomorrow (dateGuessed).
- Bare "at H" (1–11): a daypart hint wins, otherwise pm with am as the alternative. 12 is noon, and 13–23 are read as 24-hour times.
- midnight is 00:00 of the next day.
- "in N hours/minutes" = reference + N, with a time. Days and weeks give a date only.
- confident: month names, ISO, numeric with a year, relative-day words, weekdays, am/pm or HH:MM times.
- Not confident: d/m without a year, bare "at H".
suggestTitle:
- Cut the hit spans and connector words left at their edges (on, at, by, this, from, @, ",").
- Drop a leading "let's/lets/can we/shall we/we should/remember to/remind me to/don't forget to".
- Trim punctuation, capitalise, cap at 120.
- An empty result falls back to "Plan with <partner>".
Out of scope, and documented: Hinglish ("kal" means both yesterday and tomorrow, "baje"), holidays, "next week", "end of month", recurrence.
Library comparison:
- Android TextClassifier does not expose parsed values publicly and varies by device or OEM.
- ML Kit entity extraction downloads a model per language.
- Natty is unmaintained and pulls in ANTLR.

5. UI
5a. Long-press (MessagesScreen.kt:233/:318) opens a new MessageActionsSheet (ModalBottomSheet, like PingComposerSheet.kt:61). Its actions depend on the message:
  - Sent TEXT from either partner: Add to to-do · Add to calendar · Copy · Delete (own only, reusing the dialog at :288-296).
  - EMOJI: Copy · Delete (own only).
  - PHOTO/VOICE: unchanged (own → delete dialog).
  - PENDING/FAILED: unchanged (inline Retry/Discard).
  - Plan card: Add to my calendar · Copy · Delete (own only).
  - To-do card: Open list · Delete (own only).
5b. AddToTodoSheet:
  - The message becomes one editable item, or several via PasteParser.split, each with an include checkbox. Items are cut to 300 chars, with a note when cut.
  - List chooser: checklists only, pinned first, last-used preselected (per-phone preference, try/catch DataStore). "New list…" adds a title field, default "To-do".
  - "Also share in chat" switch: off from long-press, on from the composer.
  - Calls listsRepository.refresh() on open, because list events may not have been replayed.
  - Success: snackbar "Added to Groceries" with an Open action.
5c. PlanSheet (feature/plans, used by chat and lists):
  - Title from suggestTitle.
  - Date chip using the public TimelineScreen DateDialog (:139-147).
  - Time chip (Material 3 TimePicker in a dialog) with an All-day switch and an optional end time (default 1 h).
  - Chips for alternative readings, and a 'Found: "sat 8pm"' line.
  - When Partner.timeZone differs from this phone's zone: a "Whose time? Mine | Raina's" toggle. It defaults to the message author's zone and shows the converted time.
  - A past date gives a warning but is allowed.
  - No date found: the date stays empty and Save is disabled until one is picked.
  - Buttons:
    - From a message: [Add to my calendar] and [Send as a plan].
    - From the composer: [Send plan] with an "Also add to my calendar" checkbox.
    - From lists: [Add to my calendar] with a "Share in chat" checkbox.
5d. CalendarInsert (feature/plans/CalendarInsert.kt):
  - A pure extras(plan) → Insert(beginMillis, endMillis, allDay, title, description), plus launch(context).
  - The intent: Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI) with TITLE, DESCRIPTION ("Planned with <partner> in Risaina"), EXTRA_EVENT_BEGIN_TIME/END_TIME and EXTRA_EVENT_ALL_DAY.
  - Timed plan: ZonedDateTime.of(date, time, zone). End = endTime (next day if ≤ start), else +1 h.
  - All-day plan: UTC midnight of date to UTC midnight of date+1.
  - startActivity inside try/catch ActivityNotFoundException → snackbar "No calendar app on this phone". No <queries> entry is needed because nothing is resolved in advance.
5e. Composer (:380-408):
  - The 🎲 IconButton (:391) becomes "+" (AddIcon), opening ComposerAddSheet: 📅 Plan · ☑️ To-do · 🎲 Game (vm::openGamePicker) · 🖼 Photo.
  - The photo button stays where it is.
  - Plan and To-do prefill from a non-blank draft and clear it after sending.
5f. Cards: Bubble checks m.card before m.type.
  PlanCard shows:
  - 📅 and the title.
  - The date and time in the reader's zone, plus "8:00 pm Raina's time" when the zones differ.
  - [Add to my calendar] for both partners.
  - Greyed "Happened" once past.
  TodoCard shows:
  - ☑️ and the list title.
  - The referenced items, with live done state from ListsRepository.observe(listId). It falls back to the snapshot text, or "removed" when an item is gone.
  - Checkbox → setDone. Tap → ListDetail(listId), through a new onOpenList param wired at RisainaNavHost.kt:136.
  An unknown card kind renders as a normal text bubble.
5g. Lists:
  - A CalendarIcon action in the ListDetail top bar (beside pin and delete, ListsScreens.kt:211-214) opens a "Dates here" sheet. It lists the detected dates in the title, each body line and each item. "Add" opens PlanSheet with that line as the text.
  - Checklist rows (:236-241) get a small 📅 button only when the item has a confident hit.
  - No inline highlighting in v1.
5h. State: a new ChatPlansViewModel (hiltViewModel in MessagesScreen) holds the checklists flow, sheet state and actions. MessagesViewModel's combine stays as it is.

6. EDGE CASES
- Offline: plan cards queue in the outbox. Appending to a list needs a connection, so it shows a snackbar.
- The partner edits the same list during an append: the compare-and-set retry handles it.
- A list is deleted after its card was sent: the card shows the snapshot and "List removed".
- Multi-line or bullet messages become several items. The card lists at most 20 items; the fallback text stops at 2000 chars.
- Time zones: DST gaps follow java.time rules, and all-day plans are never zone-converted.
- The fallback text uses the creator's local time.
- Accessibility: cards get content descriptions, and the sheet actions are labelled.

7. PHASE 2 (separate decision)
- The Timeline's "Coming up" would list upcoming plan cards from the local messages table (cardJson kind=plan, date ≥ today).
- Later it could be backed by GET /messages/cards?kind=plan&from=, with a partial index {coupleId, 'card.kind', 'card.date'}. Index creation is part of readWrite.
- Do not reuse milestones: strict kind enum, dates only, and v75's countdown widget picks the nearest milestone.
- In-app reminders are not needed while the phone calendar handles them.

8. ROLLOUT
1. The backend commit (card field, tests, docs) goes to main first and auto-deploys.
2. Then the new APK on both phones.
No Play declaration changes.

## File plan
- /Users/rishabhranjan/Desktop/Risaina/backend/src/modules/shared/schemas.ts: Add MessageCard (discriminated union: plan {title, date: CalendarDate, time: HHmm|null, endTime: HHmm|null, timeZone} and todo {listId, listTitle, items[1..20] {id: ItemId, text 1..300}}, each .strict(), meta id 'MessageCard'). Add `card: MessageCard.nullable()` to Message (lines 255-275).
- /Users/rishabhranjan/Desktop/Risaina/backend/src/modules/messages/schemas.ts: CreateMessageBody gains `card: MessageCard.optional()`. superRefine: card only with type 'text' (path /card); endTime requires time.
- /Users/rishabhranjan/Desktop/Risaina/backend/src/modules/messages/service.ts: Add card to CreateMessageInput. Store `card` (timeZone canonicalised with canonicalTimeZone) on the doc only when present. No list lookup.
- /Users/rishabhranjan/Desktop/Risaina/backend/src/db/types.ts: MessageCardDoc types. MessageDoc `card?: MessageCardDoc | null`, with the same 'optional, no collMod' comment as viewOnce.
- /Users/rishabhranjan/Desktop/Risaina/backend/src/db/validators.ts: Add `card: { bsonType: ['object','null'] }` to the messages properties only, never to required. MESSAGE_TYPES stays unchanged.
- /Users/rishabhranjan/Desktop/Risaina/backend/src/modules/shared/dto.ts: In toMessageDto: `card` = MessageCard.safeParse(m.card) data, or null. Garbage never 500s GET /messages.
- /Users/rishabhranjan/Desktop/Risaina/backend/src/lib/logger.ts: Add 'card' and '*.card' to REDACT_PATHS.
- /Users/rishabhranjan/Desktop/Risaina/backend/test/message-cards.test.ts: New Vitest suite (see tests), including the frozen-validator checks.
- /Users/rishabhranjan/Desktop/Risaina/backend/test/fixtures/messages-validator.live.json: Copy of the v1 messages $jsonSchema (from c97d7c4 validators.ts:178-196, MESSAGE_TYPES expanded) as the frozen live validator.
- /Users/rishabhranjan/Desktop/Risaina/docs/API.md: New section 'Message cards (plans and to-dos)': shape, rules, the fallback-text contract, why there is no new type, old-server behaviour. Add `card?` to the POST /messages row (line 99).
- /Users/rishabhranjan/Desktop/Risaina/docs/COUNCIL_DECISIONS.md: Add D-13: plans and to-dos are text messages with an optional card, the calendar is the phone's calendar via ACTION_INSERT, no calendar permission.
- /Users/rishabhranjan/Desktop/Risaina/docs/PROGRESS.md: Progress entry with what was verified, plus the release order (backend first).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/domain/DateDetector.kt: New pure parser: DateOrder (+forLocale), DateHit, DateDetector.find(text, reference, order) and suggestTitle.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/model/MessageCard.kt: New sealed MessageCard (Plan, Todo, Unknown) and TodoRef.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/model/Models.kt: Message.card: MessageCard? = null. MessageType gets UNKNOWN, and fromWire becomes firstOrNull ?: UNKNOWN (line 81).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/network/Dtos.kt: Flat @Serializable MessageCardDto plus TodoRefDto. MessageDto.card and CreateMessageRequest.card default to null (lines 98-115).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/database/Entities.kt: MessageEntity.cardJson: String? = null.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/database/RisainaDatabase.kt: version = 6 with autoMigrations = [AutoMigration(from = 5, to = 6)]. Keep the destructive fallback only for older jumps. Commit the generated schemas/6.json.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/data/Mappers.kt: Encode the card DTO to cardJson. Decode tolerantly to MessageCard (runCatching; bad zone → Unknown).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/data/MessageRepository.kt: sendCard(text, card) works like sendText with cardJson. post() passes the card. One-time resend without the card when an old server answers 400 unrecognized_keys.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/data/ListsRepository.kt: appendItems(listId, texts) with client ids, a compare-and-set PATCH and up to 3 retries after 409/refresh, returning the new ids. setDone(listId, itemId, done) uses the same loop. Pure helper TodoAppend.merge(list, newItems) for tests.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/plans/CalendarInsert.kt: New. Pure extras(plan) → Insert(begin, end, allDay, title, description). launch(context) with ACTION_INSERT and an ActivityNotFoundException fallback.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/plans/PlanText.kt: New. Fallback text for plan and to-do cards, and the reader's-zone label with the partner's time when zones differ.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/plans/PlanSheet.kt: New shared confirm sheet: title, date, time, all-day, end, alternatives, whose-time toggle, and buttons that depend on where it was opened.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/MessageActionsSheet.kt: New long-press sheet: Add to to-do, Add to calendar, Copy, Delete (own), plus card actions.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/AddToTodoSheet.kt: New: items from the message (PasteParser), checklist chooser, New list…, share-in-chat switch.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/ComposerAddSheet.kt: New '+' sheet: Plan, To-do, Game, Photo.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/cards/PlanCard.kt: New plan card composable with an Add to my calendar button.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/cards/TodoCard.kt: New to-do card with live item state from ListsRepository, a checkbox (setDone) and Open list.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/ChatPlansViewModel.kt: New Hilt VM: checklists flow, last-used list, appendToTodo, sendPlanCard, sendTodoCard, calendar launch effects. Keeps MessagesViewModel's combine unchanged.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/messages/MessagesScreen.kt: onLongPress (line 233) opens MessageActionsSheet for every sent message. Bubble (line 321) renders m.card first. The composer's 🎲 button (line 391) becomes '+' opening ComposerAddSheet. New onOpenList param. Hosts PlanSheet and AddToTodoSheet.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/navigation/RisainaNavHost.kt: Pass onOpenList = { navController.navigate(ListDetail(it)) } to MessagesScreen (line 136).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/feature/lists/ListsScreens.kt: ListDetail top bar gets a CalendarIcon action that opens a 'Dates here' sheet (lines 211-214). Checklist rows get a 📅 button for confident hits (lines 236-241). Opens PlanSheet with an optional share-in-chat.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/main/java/com/risaina/app/core/sync/WidgetSnapshotUpdater.kt: Optional, depending on an open question: skip card messages in the love-note slot (line 120).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/test/java/com/risaina/app/core/domain/DateDetectorTest.kt: New JUnit 4 tests (see tests).
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/test/java/com/risaina/app/feature/plans/CalendarInsertTest.kt: New tests for begin/end/all-day and zone conversion.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/test/java/com/risaina/app/feature/plans/PlanTextTest.kt: New tests for fallback text and reader-zone labels.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/test/java/com/risaina/app/core/network/MessageCardDtoParsingTest.kt: New tests for DTO and entity round trips and tolerant decoding.
- /Users/rishabhranjan/Desktop/Risaina/android/app/src/test/java/com/risaina/app/core/data/TodoAppendTest.kt: New tests for append merge, idempotency and the conflict retry with a fake API.

## Tests
- DateDetectorTest setup: reference = Thu 2026-10-29 10:00 Asia/Kolkata, order = DMY unless stated. Weekdays checked with `date`: 31 Oct is Sat, 30 Oct Fri, 6 Nov Fri, 2 Nov Mon, 1 Nov Sun.
- DateDetector relative days: 'tomorrow 7pm' → 2026-10-30 19:00, confident. 'TOMORROW 7 P.M.' → same. 'tmrw 7pm' → same. 'day after tomorrow' → 2026-10-31 with no 'tomorrow' sub-hit. 'tonight at 9' → 2026-10-29 21:00. 'tomorrow morning at 8' → 2026-10-30 08:00. 'noon tomorrow' and '7pm tomorrow' (time first) → 2026-10-30 12:00 and 19:00.
- DateDetector weekdays: 'Sat' → 2026-10-31, all day. 'see you Sat 8pm' → 2026-10-31 20:00. 'on sat' → 2026-10-31. 'this Sat' → 2026-10-31. 'next Friday' → 2026-11-06 with alternative 2026-10-30. 'next Monday' → 2026-11-02 with no alternative. 'Thursday' (today's weekday) → 2026-11-05 with alternative 2026-10-29. 'Sun 5pm' → 2026-11-01 17:00.
- DateDetector month names and years: '25 Dec', '25th of December', 'Dec 25th' → 2026-12-25. 'December 25th, 2027' → 2027-12-25. '1 Oct' (already past) → 2027-10-01. '29 Oct' (today) → 2026-10-29. '29 Feb' → 2028-02-29.
- DateDetector numeric dates: '25/12' → 2026-12-25 in DMY. '12/25' in DMY → 2026-12-25 (swapped because only one reading is valid). '3/4' in DMY → 2027-04-03, not confident, alternative 2027-03-04. '3/4' in MDY → 2027-03-04. '2026-12-25', '25.12.2026', '25-12-26' → 2026-12-25. '31/02' → no hit.
- DateDetector times without a date: '7pm' → 2026-10-29 19:00 with dateGuessed. '9am' → 2026-10-30 09:00 (already past today). '19:30', '7:30pm', '7.30 pm' → 19:30. 'at 8' → 2026-10-29 20:00, not confident, alternative 08:00. 'at 15' → 15:00. 'midnight' → 2026-10-30 00:00.
- DateDetector ranges: 'Sat 7-9pm' → 2026-10-31 19:00 to 21:00. '10am-1pm' → 10:00 to 13:00. '11pm to 1am' → end 01:00, and CalendarInsert puts the end on the next day.
- DateDetector relative spans: 'in 3 days' → 2026-11-01, date only. 'in 2 weeks' → 2026-11-12. 'in 2 hours' → 2026-10-29 12:00.
- DateDetector must find nothing in: 'I sat down', 'the sun is out', 'I may come', 'march on', 'we are 3 of us', 'v1.2.3', 'score 2-1', '₹500', 'call 98765 43210', 'tom said hi', 'kal 7 baje' (Hinglish, out of scope, documented).
- DateDetector reference time and zone: a message sent Tue 2026-10-27 21:00 IST saying 'tomorrow 7pm' resolves to 2026-10-28 19:00, not 30 Oct. At reference instant 2026-10-29T20:00Z, 'tomorrow' is 2026-10-31 in Asia/Kolkata and 2026-10-30 in America/New_York.
- DateDetector multiple hits and order: 'Sat or Sun?' → two hits in text order (31 Oct, 1 Nov). Hit ranges map back to the original text, including with an uppercase 'İ' before the match.
- DateDetector.suggestTitle: 'Dinner at Olive tomorrow 7pm?' → 'Dinner at Olive'. 'Let's call Sat 9pm' → 'Call'. 'remind me to book tickets by 25 Dec' → 'Book tickets'. 'Sat' → '' (the caller falls back). The result is capped at 120 characters.
- DateOrder.forLocale: en-IN → DMY, en-GB → DMY, en-US → MDY.
- CalendarInsertTest: plan 2026-10-31 20:00 Asia/Kolkata → begin = epoch millis of 2026-10-31T14:30Z, end = begin + 1 h, allDay false. endTime 01:00 after a 23:00 start → end on the next day. All-day 2026-12-25 → begin 2026-12-25T00:00Z, end 2026-12-26T00:00Z, allDay true. The intent carries TITLE and DESCRIPTION.
- PlanTextTest: fallback text '📅 Dinner at Olive · Sat 31 Oct, 8:00 pm'. All-day '📅 Secret Santa · Fri 25 Dec'. To-do '☑️ Groceries: Buy milk, Eggs', cut to 2000 characters. A New_York reader sees 'Sat 31 Oct, 10:30 am' plus '8:00 pm Raina's time'.
- MessageCardDtoParsingTest: a MessageDto JSON with a plan card decodes to Plan. Kind 'poll' → Unknown, no throw. A card with a bad zone → Unknown. No card → null. Malformed cardJson in the entity → card null and the message still renders. A CreateMessageRequest without a card encodes without a 'card' key (explicitNulls=false), byte-identical to today. MessageType.fromWire('game') → UNKNOWN, no throw.
- TodoAppendTest: append to a list at version 3 → items = old + new with client ids matching [A-Za-z0-9_-]{1,32}. Appending again with the same ids is a no-op (idempotent). 199 items + 2 new → 'list full'. A fake API answering 409 once → refresh, then the retry keeps the partner's newly added item and adds ours. 404 → 'deleted' error.
- Backend message-cards.test.ts, part 1, API behaviour: POST text with a plan card → 201 with the card in the response, in the message.created event payload and in partner GET /messages. Older messages read card: null. An idempotent replay returns the same card. Partner and other-couple isolation holds (another couple gets 404 on the message).
- Backend message-cards.test.ts, part 2, validation (all 400): a card with type emoji or photo, a card without text, date '2026-02-30', time '25:00', endTime without time, timeZone 'Mars/Base', title over 120 characters, a to-do with 0 or 21 items, an item text over 300, unknown kind 'poll', extra keys inside the card. timeZone 'asia/kolkata' is stored as 'Asia/Kolkata'.
- Backend message-cards.test.ts, part 3, stored data: a doc with an invalid stored card (inserted raw) → GET /messages returns card: null, not 500. Messages without a card have no 'card' key in Mongo. Card fields are redacted in logs.
- Backend message-cards.test.ts, part 4, frozen live validator (fixtures/messages-validator.live.json = the v1 schema): the current validator's required list and existing properties are unchanged, and plan and to-do card message docs insert into a collection created with the frozen validator.
- Instrumented (androidTest, not runnable here): Room MigrationTestHelper 5→6 keeps messages and outbox rows and adds a null cardJson.
- Manual on the real phones: ACTION_INSERT opens Google Calendar or the ColorOS calendar on the Realme with the title, time and all-day prefilled. A v75 or older-APK phone shows a card message as plain text and does not crash.

## Risks
- Release order: a new APK talking to the old server gets 400 for any message with a card (strict body, messages/schemas.ts). Deploy the backend first. The client resends once without the card on unrecognized_keys, so the plan still arrives as text.
- Response serialization: a stored card that does not match the current Zod schema would turn GET /messages into a 500 for the whole page. toMessageDto must safeParse and null it.
- Room: bumping to version 6 without an AutoMigration would hit fallbackToDestructiveMigration and drop the outbox (unsent messages) and all caches. The migration is not runnable or verified here (no Gradle).
- Parser false positives and negatives: 'sat', 'sun', 'may', '3/4', 'at 9'. These are mitigated by capitalisation and context rules, a confident flag, alternative chips and confirmation every time. No proactive highlighting in chat in v1. Hinglish is unsupported.
- Calendar apps: the realme/ColorOS calendar's handling of ACTION_INSERT extras, especially all-day events in UTC, is unverified. If no handler exists, the app shows a snackbar. Plan details go to the calendar app (and its cloud sync) only on an explicit tap; consider a line in the privacy policy draft.
- Snapshots go stale: a plan card does not update if the sender changes their mind. They send a new card, and the partner's calendar entry stays as it was. That is accepted for v1.
- Lists: PATCH sends the whole items array (up to 200 × 300 chars). The compare-and-set retry is bounded at 3, so heavy concurrent editing could still surface 'try again'. If the live events validator is stale, list.updated is not pushed or replayed (existing limitation), so the partner sees appended items when Lists refreshes.
- UX: moving 🎲 into the '+' sheet adds a tap for games. Card fallback text will appear in the love-note and 'Us' widget slot unless filtered.
- v75 and Play-review phones can only see cards as text and cannot act on them (Add to my calendar, live to-do state). Acceptable, but worth noting for the reviewer accounts.
- Time zones: when partners are in different zones, '9pm' is ambiguous. The sheet defaults to the message author's zone. A wrong Partner.timeZone (or none shared) falls back to this phone's zone.

## Open questions
- Does the user mean the phone's own calendar (the recommended default, with reminders from the calendar app) or a shared calendar inside Risaina? If the latter, approve phase 2: Timeline 'Coming up' shows upcoming plan cards, later backed by GET /messages/cards.
- Is it acceptable to move the 🎲 button into the new '+' sheet, or should '+' sit alongside 🎲 (narrower text field)?
- Should 'Add to to-do' from a long-press also post a to-do card in the chat by default (designed default: off from long-press, on from the composer)?
- Should card messages (plans and to-dos) show in the love-note and 'Us' widget slot, or be skipped there?
- Is basic Hinglish needed ('kal' is both yesterday and tomorrow, 'parso', '7 baje')? It is out of scope in the proposed parser.
- Should 'next Friday' default to the following week's Friday (as designed, with the coming one as a one-tap alternative) or to the coming Friday?
- Has dbAdmin been granted on the live `risaina` database? Check the Render boot log for '[risaina] The events collection validator predates these event types'. This decides whether list.updated, and any future new event type, is pushed and replayed in production.
- Should a plan card from your partner offer a quick 'Count me in' reply? It is not designed here, to keep the card read-only and avoid new events.
