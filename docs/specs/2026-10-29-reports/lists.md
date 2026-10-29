# lists

## Summary
Saved items disappear because the shared-lists editor can lose an edit in several separate ways, and none of them shows an error. The most likely one for "I cannot see my saved item" is in the checklist editor. Text typed into the "Add item, or paste a list" field only becomes an item when Enter or Done is pressed. There is no Add button (the + icon is decorative), and back, the arrow and closing the screen all ignore that field, so the item is dropped without a word (ListsScreens.kt:167, 181-185, 199-203, 243-251). Second, any failed save loses the edit: offline, timeout, a 400 from a pasted line over 300 characters, or a 409. Nothing is stored or retried, the save sent on leaving reports its error into a snackbar channel nobody listens to any more, and on a 409 the client refreshes and refills the editor with the partner's copy, which discards the user's change (ListsRepository.kt:76). Third, Room is written without a version check, so an older copy can replace a newer one. This happens through refresh()/replaceAll, which runs again from the detail screen's own ViewModel, and through EventReducer's unconditional upsert. The editor then refills from the older copy, and a later save can push that copy to the server. The note/checklist split also works against the request. "A note" (the first option offered) has no checkboxes, the server does not allow kind to change and the app has no way to convert, the overview card for a checklist shows only "N of M done" and never the items, and doneBy/doneAt are stored but no screen shows them. A scratch vitest against the real backend harness checks the server side of these paths (5/5 pass), including that a 409 can be merged and re-sent with no backend change. The proposed design is local-first. A stored per-list draft is always what the editor and overview show. One sync engine sends it, and on a conflict or a partner's change it merges three ways instead of throwing the edit away. Retries go through WorkManager, and every Room write checks the version. The editor works by rows, with a format setting: checkboxes on or off, and no marker, numbers or bullets. Ticked items show who ticked them and when. Backend changes only add things: optional format fields (not a new kind), addedBy/addedAt set by the server, and a new convert endpoint. Existing documents and app version 75 keep working.

## Root causes
- [verified] Text typed into the checklist's 'Add item, or paste a list' field is never saved unless the user presses Enter or Done. Back, the arrow and closing the screen all drop it, and there is no Add button.
  - ListsScreens.kt:167 `var newItem by remember { mutableStateOf("") }`, which is not part of `rows`. It becomes a row only in addFrom() (181-185), and addFrom() is called only when the value contains '\n' or on onDone (247-249). The + at 245 is `Icon(AddIcon, contentDescription = null)` with no click handler. leave() (199-203) compares only title, body, rows and pinned, so typed-but-uncommitted item text never triggers a save and is thrown away with the composition. Because it uses `remember` rather than `rememberSaveable`, it is also lost on rotation. Reopening the list shows nothing, which matches 'I cannot see my saved item'.
- [verified] Every failed save loses the edit for good, and a failure during the save sent on leaving is never shown. List edits have no persistence or retry.
  - ListsViewModel.kt:113-127. flush() clears `pending` before sending, and on Failure it only calls `effects.trySend(...)`. The only collector of that channel is the detail screen's `LaunchedEffect(Unit) { vm.snackbars.collect }` (ListsScreens.kt:155), which leaves with the screen right after leave() calls onBack() (202). The save started there (schedule(immediate=true), ListsViewModel.kt:104-111) finishes in the closing ViewModel, so a timeout, offline error, 400, 409 or 429 goes into a channel nobody reads. Messages have an outbox (OutboxEntity); lists have no equivalent.
- [verified] On a version conflict (both partners editing at once), the client throws away the local change and shows the partner's version instead of merging.
  - ListsRepository.kt:75-77: on 409 it calls refresh() (dao.replaceAll) and returns 'Your partner just changed this. Showing their latest version.' The editor then sees a version it did not send and refills title, body and rows (ListsScreens.kt:168-178). Scratch test 3: the partner ticks Picnic (v2) and this phone, still on v1, adds 'Stargazing' → 409 version_conflict with details.list = v2, and GET /lists has only 'Picnic'. The same test shows a merge needs no backend change: re-sending v2's items plus 'Stargazing' with expectedVersion 2 → 200, and the partner's tick keeps its doneBy.
- [verified] A partner's change that arrives while the user is typing replaces the whole editor (title, body, rows). The user's own pending save then hits a 409 and is discarded.
  - ListsScreens.kt:174-178. When takeOwnEcho() is false, title, body and pinned are overwritten and rows are reseeded from the server (ListEditor.kt:50-54), so anything not yet echoed is lost. The pending Draft still carries the old list version (ListsViewModel.kt:105, 117). SaveChain only follows this phone's own versions, so expected = the old version → 409 → cause 3.
- [verified] Room list rows are overwritten with no version check, so an older copy can replace a newer one. The editor then refills from the older copy, and the next save can delete the new item on the server. A stale GET can even remove a just-created list from Room, leaving the editor on a spinner where edits are not saved. How often this happens depends on network timing.
  - (a) ListDao.replaceAll = clear() + upsert (Daos.kt:200-201) runs from ListsRepository.refresh() (ListsRepository.kt:54-55) in every ListsViewModel init (ListsViewModel.kt:70). The detail screen gets its own ListsViewModel instance, because hiltViewModel() is scoped per back-stack entry (RisainaNavHost.kt:129-130), so opening a list fires a GET /lists that races the first PATCH. (b) EventReducer.kt:130-132 upserts payload.list without comparing versions, and the saver receives its own list.updated events through WS replay and GET /events. Scratch test 1: after this phone stored v3 from its HTTP reply, catch-up delivers its own v2 then v3, and applying them the way the reducer does gives Room versions [3, 2, 3] ('Hike' vanishes); a newer-wins check gives [3, 3, 3]. Scratch test 2: a GET /lists answered after a save carries version 1 with no items. After Room goes back to v1, takeOwnEcho is false → refill. Then schedule(l=v1) → SaveChain maps 1→2 → it sends the refilled rows without the item, and the server accepts. If the overview's initial GET returns after a POST create, replaceAll removes the new list, `l == null`, save() becomes a no-op (ListsScreens.kt:180) and leave() saves nothing (200-201).
- [likely] A note has no checkboxes and can never become a checklist, so to-do items typed into 'A note' can never be ticked. The kind chosen at creation hides what the user is asking for.
  - The New dialog lists '📝 A note' first (ListsScreens.kt:106-107). The note editor shows only the body (230-232) and checklist rows only for kind=checklist (233-242). The server refuses a kind change: PatchListBody is .strict() and has no kind (lists/schemas.ts:37-58), the test asserts it (profile-lists.test.ts:371), and docs/API.md:329-330 states 'kind cannot be changed'. The app has no conversion. The user's wording, 'I need checkboxes there and a numbering system', fits a to-do list typed into a note.
- [verified] The overview never shows a checklist's items, and 'who ticked what' is stored but never displayed.
  - ListCard (ListsScreens.kt:139-142) shows only 'N of M done' plus a progress bar for checklists; only notes show text (144). A grep for doneBy/doneAt under android/.../feature finds nothing. The data does arrive: ListItem in core/model/Lists.kt:10 and ListsRepository.kt:46.
- [verified] A pasted line over 300 characters, or more than 200 items, makes the whole PATCH fail with 400. Every save after that fails too, so all new items in the list are lost.
  - PasteParser.split (ListsViewModel.kt:32-39) does not shorten lines and caps at 200 per paste only. addFrom adds to the existing rows without a total cap (ListsScreens.kt:184). Scratch test 4: a PATCH of ['Short one', 301 x 'x'] → 400, and the stored list has no items.
- [verified] The last ≤700 ms of typing is dropped if the detail screen is removed from the back stack by something other than leave(), for example tapping a notification that opens Home.
  - schedule() waits `delay(700)` before it enters NonCancellable (ListsViewModel.kt:107-110), so clearing the ViewModel cancels a debounce that has not started its flush. openDeepLink pops back to Home for Home links (RisainaAppUi.kt:98-99), and resetToGate uses popUpTo(0) (133-136).
- [possible] If the live Atlas `events` collection still had the v1 strict type enum, list.* events would be sent live over the WebSocket only, with no FCM push and no replay. The partner would then not see changes after backgrounding until the Lists screen is recreated. This is probably not the case on production.
  - publisher.ts:62-80 falls back on code 121. v1's validator used `type: { enum }` without list.* (git c97d7c4 validators.ts:288), and PROGRESS.md:94 recorded this on the Atlas DB in use at the time. The app refreshes lists only in ListsViewModel init. On the other hand, PROGRESS.md:370-372 records SOS (sos.raised, which v1's enum also lacked) ringing through the live server, so production probably has the dotted-pattern validator. To check: look in the Render logs for the boot line '[risaina] The events collection validator predates these event types' or 'event rejected by the events collection validator'.
- [possible] Recognising this phone's own saves depends on comparing content, which is fragile. Any mismatch between the server's normalisation and the client's turns an echo into a 'partner change' and refills the editor.
  - takeOwnEcho compares ListEditorSync.contentOf(list) with sent(...) (ListEditor.kt:21-31, ListsViewModel.kt:133-139). JS String.trim removes U+FEFF and Kotlin trim does not. The queue holds only 8 entries (MAX_UNECHOED).

## Current state
Backend (backend/src/modules/lists):
- routes.ts: GET /lists, POST /lists, PATCH /lists/:id, DELETE /lists/:id. Writes share a per-user limit of 120/min.
- schemas.ts: Create and Patch bodies are .strict(). Patch has no `kind`. `items` replaces the whole array. `expectedVersion` is optional. Limits: title 1-120, body ≤10k, item text 1-300, ≤200 items.
- service.ts: create() emits list.created. update() does compare-and-set on version, and with expectedVersion it returns 409 version_conflict with details.list. mergeItems (134-154) rebuilds every item as {id, text, done, doneBy, doneAt}: doneBy/doneAt are owned by the server and kept when `done` is unchanged.
- repo.ts: soft delete; 200 lists per couple.
- Shared Zod `List`/`ListItem` (shared/schemas.ts:333-360) and toListDto (shared/dto.ts:86-107). Unknown keys are stripped from responses.
- Mongo validator (db/validators.ts:346-391): `kind: { enum: ['note','checklist'] }`, required item fields, no additionalProperties. The live DB keeps whatever validator it was created with (no collMod).
- Events list.created / list.updated {list} and list.deleted {listId} go to both members. Push policy is normal/silent (policy.ts:108, API.md:838).

Android:
- core/model/Lists.kt: ListKind NOTE/CHECKLIST, and fromWire falls back to NOTE for unknown values. ListItem(id, text, done, doneBy, doneAt). SharedList.
- core/network/Dtos.kt:180-189: list DTOs. The client Json has ignoreUnknownKeys=true (NetworkModule.kt:50) and EventReducer uses the same Json.
- core/database: ListEntity (Entities.kt:99-113) stores items as itemsJson. ListDao (Daos.kt:188-205) uses @Upsert and replaceAll=clear+upsert. Room v5, exportSchema on (schemas 1-5.json), fallbackToDestructiveMigration (RisainaDatabase.kt:15-22, 46). clearAllContent clears lists.
- core/data/ListsRepository.kt: refresh/create/save/delete. On 409, save() refreshes and returns a Conflict.
- core/sync/EventReducer.kt:130-133 upserts or deletes from events without a version check. EventSyncManager applies WebSocket events live and replays them on reconnect; syncOnce covers FCM and the worker.
- feature/lists:
  - ListsViewModel: create; a debounced (700 ms) save that sends one request at a time; SaveChain; sentNotEchoed/takeOwnEcho; PasteParser; templates.
  - ListEditor.kt: ListEditorSync.adoptIds/reseed and SaveChain.
  - ListsScreens.kt: the overview (cards, New dialog with note/checklist/templates) and the detail editor (title, note body or checklist rows with Checkbox, a text field and ×, and an 'Add item' field).
  - The overview and the detail screen each get their own ListsViewModel instance (RisainaNavHost.kt:129-130).
- Tests: ListEditorTest.kt covers echo, adoptIds, reseed, SaveChain and save ordering. Backend profile-lists.test.ts covers CRUD, 409, isolation, limits, export, the old events validator and the rate limit.
- Not present: reordering UI, numbering or bullets, display of who ticked, item previews on the overview, note↔checklist conversion, offline drafts or retries, merging on conflict.

Scratch reproduction (not in the repo): /private/tmp/claude-501/-Users-rishabhranjan-Desktop-Risaina/8d3b72b8-bc98-46ee-a936-322339046d05/scratchpad/repro/test/lists-repro.test.ts. Run from backend/ with `pnpm exec vitest run --globals --dir /private/tmp/claude-501/-Users-rishabhranjan-Desktop-Risaina/8d3b72b8-bc98-46ee-a936-322339046d05/scratchpad/repro` → 5/5 passed. It proves: own older versions are replayed after a newer HTTP reply; a GET answered after a save holds the pre-save copy; a 409 discards the item and a rebase on details.list succeeds; a single line over 300 characters fails the whole save with 400; a new `kind` value is rejected by the lists validator (code 121); extra list and item fields pass the validator, but the response strips them and a version-75-shaped PATCH drops extra item fields while keeping list-level ones; PATCH rejects unknown top-level keys.

## Design
GOAL: what the user typed is always on screen and in the overview, and it reaches the server eventually. Two people can edit the same list at once without either losing work. The editor behaves like Keep or Samsung Notes. The design has a P0 hotfix that needs no backend change, then the full design.

== A. P0 hotfix (next APK, Android only) ==
1. Remove the separate add buffer. '+ Add item' is a real row: tapping it appends an empty EditRow, focuses it, and saves it like any other row. If a `newItem` field is kept for a transition period, it must be committed in leave(), on focus loss and in onDispose, and it should use rememberSaveable.
2. Check versions on every Room write. Add `ListDao.upsertIfNewer(e)` (@Transaction: write only if no row exists or e.version >= current.version). Use it in EventReducer.kt:131, ListsRepository.save/create and refresh. Replace replaceAll with `syncFromServer(items, requestedAt)`: upsertIfNewer each item, and delete a local row only if the server did not return it AND it was last written before the GET started (new column syncedAt). Stop refreshing from the detail screen: give it its own ListEditorViewModel(SavedStateHandle id).
3. On 409, rebase instead of discarding: ListMerge.merge3(base = the version the editor started from, mine = editor state, theirs = error.details.list), then re-send with expectedVersion = theirs.version, at most 3 tries. This needs no backend change (scratch test 3). ApiErrors must parse details.list.
4. When a partner's version arrives while editing, refill with merge3(base, mine, theirs) instead of replacing everything.
5. Never fail silently. If the save sent on leaving fails, the draft stays (section B) and the overview shows 'Not saved – tap to retry'. Errors go to a repository-level SharedFlow that the overview also collects.
6. PasteParser: cut lines over 300 characters at a word boundary into several items, cap the total at 200, and show a snackbar for anything left over.
7. Overview cards preview the first 4 items. Ticked rows show '✓ Asha · 2h'.
8. Notes get 'Turn into checklist' (section C3; until that endpoint ships, create a new checklist from the lines and delete the note).

== B. Local-first sync engine (Android) ==
- New Room table `list_drafts`: listId PK, baseVersion, baseJson (the server copy the draft was built from), mineJson (title, body, pinned, marker, checkboxes, and rows[{localKey, id?, text, done}]), editedAt, state (pending|sending|failed|orphaned), lastError, attempts. Room v6 uses AutoMigration(5→6) (schemas/5.json exists), adding to ListEntity the columns marker TEXT DEFAULT 'none', checkboxes INTEGER DEFAULT 1 and syncedAt INTEGER DEFAULT 0. This avoids the destructive fallback, which would also wipe the chat outbox. clearAllContent also clears list_drafts and cancels list-save work.
- `ListSyncEngine` (@Singleton, core/data/lists):
  - `observe(id): Flow<EditorDoc>` shows the draft if there is one, otherwise the server copy, plus a status (Saved / Saving… / Offline – saved on this phone / Couldn't save: reason).
  - `edit(id, transform)` updates an in-memory StateFlow at once, writes the draft to Room after 250 ms, and schedules a send after 700 ms.
  - Send loop: one per list under a Mutex.
    - If mine equals base, delete the draft.
    - Otherwise PATCH mine with expectedVersion = baseVersion.
    - 200: upsertIfNewer the reply. If the draft has not changed since it was sent, delete it. If it has, set base to the reply and give new rows their ids by INDEX in the sent array (mergeItems keeps order 1:1, which is exact, unlike today's text matching).
    - 409: merge3 with details.list and resend (at most 3, then back off).
    - 400: clamp to the limits and retry once, else state=failed (keep the draft).
    - 404: state=orphaned, then the UI offers 'Asha deleted this list – Keep as a new list / Discard'.
    - 429: wait for Retry-After.
    - IO or timeout: enqueue ListSaveWorker (unique 'list-save', NetworkType.CONNECTED, exponential backoff).
  - Recognising its own saves uses versions, not content. Server versions ≤ baseVersion are ignored; a higher version with a draft present runs `rebase` (merge3).
  - EventReducer calls engine.onServerVersion(list) after upsertIfNewer. This is Room-only, with no network inside apply().
  - SaveChain, sentNotEchoed and takeOwnEcho are removed.
- `ListMerge.merge3(base, mine, theirs)`, pure Kotlin:
  - Scalar fields (title, pinned, marker, checkboxes): mine if mine != base, else theirs.
  - body: line-level diff3. Changes that do not overlap are combined. Where both changed the same lines, mine's lines stay and theirs' are inserted below them, and the user sees 'Asha edited this note too – both kept'.
  - Items by id:
    - Deleted by me → stays deleted.
    - Deleted by them → kept only if I changed its text (the same id is resent; the server keeps a client id it does not know as a new item), otherwise removed.
    - Both kept → text = mine if mine changed it, else theirs; done = mine if mine changed it, else theirs.
    - Adds by either side are kept.
  - Order: start from theirs. If I reordered the base items, apply my relative order to them. Insert my new rows after their nearest preceding row from mine that is still present. Partner adds keep their place.
  - Properties: merge3(b,b,t)=t, merge3(b,m,b)=m, and merging is idempotent.
- Refresh policy: a single owner, ListsRepository.refresh(). It runs on opening the overview and, at most once a minute, after WebSocket Synced or the app coming to the foreground (via DeferredEffects, outside apply). This covers list events that were never stored or pushed (API.md:367-370).

== C. Backend (additive only) ==
1. List format fields (NOT a new kind: the live validator's kind enum rejects new values with code 121, scratch test 5, and v75's ListKind.fromWire maps unknown kinds to NOTE, which would hide the items):
   - `marker?: 'none'|'number'|'bullet'` and `checkboxes?: boolean`, optional on POST and PATCH (added to the .strict() bodies).
   - The DTO always returns them (`marker ?? 'none'`, `checkboxes ?? true`).
   - VALIDATORS.lists lists them as optional properties for fresh databases. The live DB accepts them as extra fields (proven).
   - $set touches only the fields sent, so a v75 PATCH keeps them.
2. Item attribution, owned by the server: `addedBy: Id|null` and `addedAt: ISO|null`.
   - mergeItems becomes `{ ...prev, id, text, done, doneBy, doneAt, addedBy: prev?.addedBy ?? (isNew ? actor : null), addedAt: … }`. Any future item field then survives v75 saves; today they are dropped (scratch test 5).
   - New items from v75 are stamped too. Legacy items are null.
   - The ListItem Zod schema gets nullable addedBy/addedAt. Input keys stay ignored, as today.
3. `POST /lists/:id/convert { to: 'note'|'checklist', marker?, checkboxes?, expectedVersion? }`, a new route so the documented 'PATCH kind → 400' stays true:
   - note→checklist: body lines become items (strip '- ', '* ', '• ', '1.', '[ ]'/'[x]' → done; cut to 300; cap at 200; the rest stays in body), and body becomes ''.
   - checklist→note: items are rendered as lines ('1. ', '• ', '☐ '/'☑ ' by format) and items become [].
   - It goes through the same patchAtVersion and emits the same `list.updated {list}`, so no new event type is needed and the stale-enum risk does not arise. Same rate limiter.
4. Optional: `GET /lists/:id` (opening from a link or a single refresh).
5. Optional (P2): `POST /lists` accepts `clientKey` (≤64) with a partial unique index {coupleId, clientKey} (createIndex is part of readWrite), so an offline-created list can be retried without duplicates.
6. No push-policy change: list.* stays silent and normal.

== D. Editor UI (ListEditorScreen) ==
- Top bar: back, sync status text, pin, overflow menu (format, convert, uncheck all, delete checked, delete list).
- Title: single line; ImeAction.Next moves focus to the first row.
- Each row:
  - Drag handle (long-press to drag, built in-house with detectDragGesturesAfterLongPress and LazyListState.layoutInfo, about 80 lines, no new dependency; TalkBack custom actions Move up / Move down / Move to top).
  - Marker by format: a 48 dp Checkbox if checkboxes is on, and/or '1.' (from position) or '•'.
  - BasicTextField: Enter splits the row at the cursor and focuses the new row. This detects the '\n' in the value, which works on Gboard and Samsung keyboards. Backspace at the start of a row joins it to the previous row; an empty row is deleted. Pasting several lines inserts rows there (clamped).
  - × on the focused row, with an Undo snackbar.
- A ticked row is struck through, with '✓ You · 3m' or '✓ Asha · yesterday' under it (doneBy == myId → 'You', == partner.id → displayName; Format.relative(doneAt)). Partner-added rows show a small initial badge (addedBy). Optional 'Move ticked to bottom' only changes the display; it does not reorder the array.
- Format bar above the keyboard: [☑ Checkboxes] [1. Numbers] [• Bullets].
- Note editor: plain-text body with list continuation. Enter after a '• '/'- ' or 'N. ' line adds the next bullet or number; Enter on an empty bullet ends the list. The bar has '• List', '1. List' and 'Turn into checklist'. The text stays plain, so v75 shows it unchanged.
- Title placeholder 'Untitled'. A blank title sends the previous title (as today).

== E. Overview (ListsScreen) ==
- Two-column staggered grid of cards: Pinned, then Others.
- Filter chips: All · To-dos · Notes. Search appears when there are more than 6 lists (local filter on title, body and items).
- A card shows the format icon, the title, up to 4 rows previewed with ☐/☑/1./•, '+N more', and '3 of 7' with progress for checkbox lists. The footer is the newest of 'Asha ticked “Beach” · 5m' (latest partner doneAt) or 'Asha edited · 5m', plus a draft chip ('Saving…' / 'Not saved – tap to retry').
- The New FAB opens a sheet: To-do list, Numbered list, Bulleted list, Note, then templates. Each is created with its format and opened. Many notes or lists are one tap each (the 200-list limit is unchanged).

== F. Notifications and scheduling ==
None required. The only new background work is ListSaveWorker (WorkManager, CONNECTED constraint, no foreground service, no new permission or Play declaration). Partner list activity stays silent; see the open questions.

== G. Edge cases ==
- Partner deletes the list while I edit → 404 → the orphaned draft offers 'Keep as new list' (POST).
- Unlink or sign-out → drafts cleared and work cancelled.
- v75 PATCHes items onto a list the new app has already converted to a note → the new note view shows 'N list items from an older version – Show'.
- Over 200 items → sending is blocked with a visible message.
- 429 → backoff.
- Process death while typing → the draft is already in Room, and the worker sends it.
- Old events replayed → ignored by the version check.
- Rows with empty text are kept locally but not sent (as today).

== H. Backward compatibility ==
- v75 reading: new fields are ignored (ignoreUnknownKeys, NetworkModule.kt:50; EventReducer uses the same Json). Numbered and bulleted lists appear in v75 as ordinary checklists.
- v75 writing: PATCH semantics are unchanged; marker and checkboxes are kept ($set); addedBy and extra item fields are kept by id; new items get addedBy. v75 still discards its own edit on a 409 (only a new APK fixes that).
- Existing documents: the DTO fills defaults; nothing is migrated in Mongo.
- Live DB: no collMod; extra fields pass the existing validator (scratch test 5); no new event type or kind value.
- Existing tests and documented behaviour (PATCH kind → 400, strict bodies, limits, 409 shape) stay as they are.

## File plan
- backend/src/db/types.ts: Add LIST_MARKERS = ['none','number','bullet']. Add ListDoc.marker?: ListMarker and checkboxes?: boolean. Add ListItem.addedBy?: ObjectId|null and addedAt?: Date|null. LIST_KINDS unchanged.
- backend/src/modules/shared/schemas.ts: Add ListMarker enum. Add `marker` and `checkboxes` to List, and nullable `addedBy` (IdString) and `addedAt` (IsoDateTimeNullable) to ListItem.
- backend/src/modules/shared/dto.ts: toListDto: marker ?? 'none', checkboxes ?? true, addedBy/addedAt ?? null.
- backend/src/modules/lists/schemas.ts: Add optional marker and checkboxes to CreateListBody and PatchListBody (still .strict(), no kind). New ConvertListBody { to, marker?, checkboxes?, expectedVersion? }. Export the clamp helpers used by convert.
- backend/src/modules/lists/service.ts: create(): stamp addedBy/addedAt on items and store marker/checkboxes. update(): $set marker/checkboxes when given. mergeItems(): spread prev (keep unknown or new item fields), keep or stamp addedBy/addedAt. New convert() using patchAtVersion and emitting list.updated. Optional getOne().
- backend/src/modules/lists/routes.ts: Add POST /lists/:id/convert (guardedWrite, same limiter) and optional GET /lists/:id.
- backend/src/db/validators.ts: lists: add optional properties marker (enum), checkboxes (bool), and item addedBy (nullableObjectId) and addedAt (nullableDate), not required. Only fresh databases are affected; the live DB keeps its validator, which already accepts them.
- backend/test/lists-editor.test.ts: New suite: format fields, attribution, kept through v75-shaped saves, convert, GET by id, behaviour under the current (older) lists validator. Move scratch repro cases 3 and 5 into it.
- docs/API.md: Additive section: List.marker/checkboxes, ListItem.addedBy/addedAt, POST /lists/:id/convert, GET /lists/:id; note that v75 clients keep working.
- android/app/src/main/java/com/risaina/app/core/model/Lists.kt: Add ListMarker (fromWire: unknown → NONE). SharedList gets marker and checkboxes. ListItem gets addedBy and addedAt.
- android/app/src/main/java/com/risaina/app/core/network/Dtos.kt: Defaults for SharedListDto marker/checkboxes and ListItemDto addedBy/addedAt. Create/PatchListRequest get marker/checkboxes. Add ConvertListRequest. Error details carry `list` for version_conflict.
- android/app/src/main/java/com/risaina/app/core/network/RisainaApi.kt: Add convertList(id, req) and list(id).
- android/app/src/main/java/com/risaina/app/core/network/ApiErrors.kt: Parse error.details.list on 409 version_conflict into a typed AppError.VersionConflict(list).
- android/app/src/main/java/com/risaina/app/core/database/Entities.kt: ListEntity: add marker, checkboxes, syncedAt (with defaults). New ListDraftEntity (list_drafts).
- android/app/src/main/java/com/risaina/app/core/database/Daos.kt: ListDao: upsertIfNewer (@Transaction), syncFromServer(items, requestedAt) replacing replaceAll. New ListDraftDao (observe, all, get, put, delete, clear).
- android/app/src/main/java/com/risaina/app/core/database/RisainaDatabase.kt: version 6, autoMigrations = [AutoMigration(5, 6)], add ListDraftEntity, clearAllContent clears drafts. Keep the destructive fallback only as a last resort.
- android/app/src/main/java/com/risaina/app/core/data/lists/ListMerge.kt: New pure functions: merge3, line diff3 for the body, clamp(limits), adoptIdsByIndex.
- android/app/src/main/java/com/risaina/app/core/data/lists/ListSyncEngine.kt: New singleton: drafts, debounced persist and send, a send loop per list, rebase on 409 and on server versions, status flows, orphan handling, schedules the worker.
- android/app/src/main/java/com/risaina/app/core/sync/ListSaveWorker.kt: New CoroutineWorker: drains pending or failed drafts through the engine. Unique work with a CONNECTED constraint. Cancelled on sign-out or unlink.
- android/app/src/main/java/com/risaina/app/core/data/ListsRepository.kt: Use upsertIfNewer and syncFromServer. Remove refresh-and-discard on 409 (the engine handles conflicts). create() takes marker/checkboxes. Add convert(). Add an errors SharedFlow.
- android/app/src/main/java/com/risaina/app/core/sync/EventReducer.kt: Lines 130-133: listDao.upsertIfNewer, then listSyncEngine.onServerVersion(list) (Room only). list.deleted marks any draft orphaned.
- android/app/src/main/java/com/risaina/app/core/sync/EventSyncManager.kt: On Synced and app foreground, queue a lists refresh at most once a minute through DeferredEffects.
- android/app/src/main/java/com/risaina/app/feature/lists/ListsViewModel.kt: Split into ListsOverviewViewModel (refresh once, filters, search, drafts merged into cards, error collection) and ListEditorViewModel (SavedStateHandle id, delegates to the engine). Remove SaveChain, sentNotEchoed and takeOwnEcho. PasteParser cuts lines to 300 and caps at 200.
- android/app/src/main/java/com/risaina/app/feature/lists/ListEditor.kt: Replace ListEditorSync/SaveChain with pure editor operations: splitAt, joinWithPrevious, insertPasted, move, toggle, numbering, note list continuation.
- android/app/src/main/java/com/risaina/app/feature/lists/Reorder.kt: New in-house drag-to-reorder helper for LazyColumn plus accessibility move actions.
- android/app/src/main/java/com/risaina/app/feature/lists/ListsScreens.kt: New overview (staggered grid, previews, filters, New sheet with formats, draft chips) and editor (rows, markers, Enter/Backspace behaviour, format bar, ticked-by line, status, convert, orphan dialog).
- android/app/src/test/java/com/risaina/app/feature/lists/ListEditorTest.kt: Drop the SaveChain/echo tests. Add editor-operation and PasteParser tests.
- android/app/src/test/java/com/risaina/app/core/data/lists/ListMergeTest.kt: New: merge3 cases and invariants.
- android/app/src/test/java/com/risaina/app/core/data/lists/ListSyncEngineTest.kt: New: fake DAOs plus a Ktor MockEngine covering 200/409/400/404/429/IO paths and rebasing.
- android/app/src/androidTest/java/com/risaina/app/core/database/ListDaoMigrationTest.kt: New instrumented tests: upsertIfNewer, syncFromServer, and MigrationTestHelper 5→6.

## Tests
- Backend: move scratch repro tests 3 and 5 into backend/test/lists-editor.test.ts: 409 details.list plus a successful rebase; lists validator rejects a new kind (121) and accepts extra fields; v75-shaped PATCH behaviour.
- Backend: POST with marker 'number' and checkboxes false → DTO echoes them. A legacy doc without the fields → marker 'none', checkboxes true. Invalid marker → 400. A v75-shaped PATCH (title/items only) keeps marker and checkboxes.
- Backend: addedBy/addedAt set on POST items and on new items in a PATCH, kept for existing ids through v75-shaped PATCHes (only id/text/done), null on legacy items, and present in the list.updated event payload and in GET /events.
- Backend: mergeItems keeps an unknown extra item field (inserted directly) through a PATCH. doneBy/doneAt rules are unchanged (the existing profile-lists.test.ts passes unmodified).
- Backend: POST /lists/:id/convert note→checklist strips bullets, numbers and [ ]/[x] (done), cuts to 300, caps at 200 with the remainder kept in body, emits list.updated, bumps version, gives 409 on a stale expectedVersion, 404 across couples, and counts against the shared write limit. checklist→note renders lines by format and empties items.
- Backend: under a simulated live (pre-change) lists validator (collMod in the test to the old VALIDATORS.lists), create/patch/convert with the new fields succeed. PATCH with `kind` still returns 400.
- Android JUnit ListMergeTest: partner ticks X while I add Y → both; partner deletes X while I edit X → X kept; I delete X while partner edits it → deleted; both edit X's text → mine; both toggle → mine; my reorder plus a partner add → my order and their add after its anchor; title and pinned field rules; body diff3 with non-overlapping and overlapping edits; merge3(b,b,t)==t, merge3(b,m,b)==m, idempotent.
- Android JUnit ListSyncEngineTest (fake ListDao/ListDraftDao, Ktor MockEngine): edit then leave → draft persisted before any network; 200 → draft deleted and ids taken by index; 200 while typing continued → draft rebased onto the reply with the typed text intact; 409 → merged and resent with theirs.version; IO failure → draft kept, state pending, worker enqueued; 400 → clamped retry, then failed and kept; 404 → orphaned; own WebSocket echo (version ≤ base) ignored; partner version above base → rebase with no lost keystrokes.
- Android JUnit EventReducer: list.updated with a lower version than Room is ignored; list.created for an unknown id is inserted; list.deleted with a draft marks it orphaned.
- Android JUnit editor operations: Enter splits a row at the cursor and focuses the new row; Backspace at the start joins rows; pasting 3 lines into row 2 inserts 3 rows there; a 650-character line becomes 3 items of 300 or fewer; total capped at 200 with the overflow reported; numbering follows array order; note continuation for '1. ' → '2. ' and '• ' → '• ', and Enter on an empty marker ends the list.
- Android instrumented: in-memory Room upsertIfNewer and syncFromServer (a row written after requestedAt survives a GET that lacks it); MigrationTestHelper 5→6 keeps lists, messages and outbox rows.
- Android Compose UI (instrumented): type in '+ Add item', then press back → the item appears on the overview card and after reopening; tick an item → '✓ You' shows; switch the format to Numbers → markers 1..n.
- Manual on two phones (one on v75): v75 ticks an item in a numbered list → the new app shows it struck through with 'ticked by'; the new app adds while v75 edits → neither side's server copy loses items (v75 may still show its own 409 message).

## Risks
- Merge rules can surprise people (for example, a delete beats a partner's simultaneous edit). The rules should be written down, shown through brief snackbars, and covered by exhaustive unit tests.
- The sync engine and the drafts table are a sizeable rewrite of a feature used every day. A bug there could duplicate or resurrect items. Ship the P0 hotfix first, then the engine behind a build flag, with the old path removable.
- A wrong Room AutoMigration 5→6 falls back to the destructive migration, which also wipes the chat outbox (unsent messages). The migration must be tested with MigrationTestHelper before release.
- v75, and the APK currently in Play review, keep the add-field bug and still discard edits on a 409 until they update. The backend changes cannot fix the old client.
- A list converted while v75 has it open: v75 may PATCH items onto what is now a note. The note view needs the 'items from an older version' strip so nothing is hidden.
- Retries from WorkManager after sign-out or unlink could send drafts into the wrong session. Drafts must be cleared and the work cancelled in clearAllContent and on unlink.
- Write volume: bounded resends and backoff are needed to stay under 120 writes per user per minute. One save in flight per list and the 700 ms debounce stay.
- The in-house drag-to-reorder needs care with the keyboard open and with TalkBack. The move up/down actions are the accessible fallback.
- If the live events validator is in fact stale for list.*, partners still depend on the refresh on reconnect or foreground to see each other's changes. This should be confirmed from the Render logs.

## Open questions
- Which flow did the user actually hit: a to-do list typed into a 'Note' (no checkboxes), or an item typed in 'Add item' without pressing Enter? Asking them, or checking kind and items in their /me/export, would confirm the top cause.
- Do they want checkboxes mixed into free-text notes (Keep-style 'show checkboxes' on a note), or is note ↔ list conversion plus numbered and bulleted list formats enough?
- Should a numbered list also have checkboxes by default (a numbered to-do), or should numbered and bulleted lists be plain lists?
- Conflict policy: when one partner deletes an item while the other edits it, should the delete win (proposed) or the edit?
- Are nested sub-items (indentation) wanted? They would be one more optional item field and are left out of v1.
- Should a partner's list activity ('Asha added 3 things to Weekend') post a quiet notification? Today list.* pushes are silent and no notification is shown.
- Does the live Atlas `events` validator still reject list.*? Check the Render boot log for '[risaina] The events collection validator predates these event types'.
- Should ticked items move to a collapsed 'Ticked' section by default, as in Keep?
- Is an offline-created list (clientKey idempotency on POST /lists) needed now, or can creation stay online-only with a clear error?
