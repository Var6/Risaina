# Risaina — Progress

Legend: ✅ done and verified · 🟡 implemented, not yet verified · ⬜ not started · ❌ blocked

_Last updated: 2026-10-25 (emulator journey verified against MongoDB Atlas)_

## Environment
- ✅ JDK 17 installed (Homebrew `openjdk@17`)
- ✅ Android command-line tools, platform 36, build-tools 36.0.0, platform-tools installed
- ✅ Gradle 9.6.0 distribution fetched for wrapper bootstrap
- ✅ Node 26.5 / pnpm 11 present; MongoDB 8.3 running locally
- ✅ Android emulator (API 37 image, headless) used for journey tests; ❌ no physical phone tested yet
- ❌ No Firebase project, R2 bucket, or email provider configured (user action)

## Phase 0
- ✅ Repository inspected (was empty), git initialised, `.gitignore` excludes secrets
- ✅ docs/COUNCIL_DECISIONS.md, ARCHITECTURE.md, IMPLEMENTATION_PLAN.md, API.md

## Phase 1 — Android foundation
- ✅ Gradle project builds: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (AGP 9.4.1, Kotlin 2.4.21, compileSdk 37, minSdk 26); `app-debug.apk` 24.7 MB
- ✅ Design system (tokens, Fraunces/Inter type scale, motion tokens with reduced-motion, components)
- ✅ Adaptive launcher icon (vector foreground/background/monochrome) + splash
- ✅ Type-safe navigation, deep links (risaina://app/{home,ping/{id},messages})
- ✅ Screens: Welcome, Sign up/in, Forgot password, Pairing intro/pairing, Notification permission, Home, Ping history/detail, Messages, Memories (+detail, add), Timeline, Wallpaper Studio, Widget gallery, Settings (+notifications, privacy, profile, about)
- ✅ Unit tests: 22/22 passed (Countdown 6, Format 2, MessageRules 3, PingRules 5, QuietHours 6)
- ✅ Lint: 0 errors, 35 warnings (`UnsafeOptInUsage*` disabled: lint crashes on Kotlin 2.4 sources)

## Phase 2 — Backend foundation
- ✅ Server boots with validated env; `/api/v1/health` → mongo ok, storage local, push disabled
- ✅ Auth (argon2id, JWT access, rotating refresh with reuse detection), `authenticate` + `requireCouple` guards
- ✅ OpenAPI generated from Zod schemas, served at `/docs` (35 paths)
- ✅ `pnpm typecheck` clean (TS 7.0.2); `pnpm test` → 12 files, **63/63 passed** (verified by me after the implementation agent)
- ✅ Security tests: cross-couple isolation, invitation reuse/expiry/revoke/self, sender cannot mark delivered/seen, media non-member 403 + signed URL expiry, device token never in any response, deletion cascade, login 429

## Phase 3 — Pairing & notifications
- ✅ Backend: invitations (hashed, single-use, 24 h), devices, pings (idempotent, cooldown, recipient-only delivered/seen), events log + WS hub + outbox worker with quiet hours — tested
- ✅ Android: network layer with token refresh, session store, WebSocket sync manager, event reducer, FCM service (data-only), notification channels incl. quiet-hours silent channels, haptics, pairing flow, Touch Ping end-to-end code path
- ✅ Emulator + curl-driven partner, backend on **MongoDB Atlas**: sign up → pairing code → both sides `active` → Home
- ✅ Partner → phone ping: arrived live over WebSocket, in-app reveal with "Send one back", phone auto-acknowledged → server status `delivered`
- ✅ Phone → partner ping: `accepted` → partner acks → `delivered` → partner opens → `seen`; phone shows "Mira saw it" (persisted after fix, checked 25 s later)
- ❌ Background push not tested (no Firebase project yet)

## Phase 4 — Media & messaging
- ✅ Backend media (presign → verify → ready, signed download, delete, cleanup) and messages — tested
- ✅ Android: conversation UI, photo picker + compression (EXIF stripped), voice recorder (MediaRecorder, pause/resume, 3 min cap), Media3 playback, memories grid/detail/add, pagination, outbox for offline text
- 🟡 Media journey on device — pending

## Phase 5 — Widgets & wallpaper
- ✅ Six Glance widgets (photo, love note, Touch Ping, emoji, countdown, voice note) reading a privacy-filtered snapshot; gallery with pin requests; Wallpaper Studio with preview, save, and confirmed apply
- 🟡 Launcher verification — pending (emulator)

## Phase 6 — Hardening
- ✅ Docs: README, SECURITY, FIREBASE/R2/MONGODB setup, DEPLOYMENT, TESTING, RELEASE, KNOWN_LIMITATIONS, TROUBLESHOOTING, PRIVACY_POLICY_DRAFT
- 🟡 Android unit tests + lint run — pending
- 🟡 Instrumented UI tests written — run pending
- ⬜ Real-device test

## Test results log
| Date | Check | Result |
|---|---|---|
| 2026-10-25 | backend `pnpm typecheck` / `pnpm test` | clean / 63 of 63 passed (before Google + email additions) |
| 2026-10-25 | android `assembleDebug` / `testDebugUnitTest` / `lintDebug` | success / 22 of 22 / 0 errors |
| 2026-10-25 | emulator journey on Atlas | pairing + ping both directions + delivered + seen verified |

## Bugs found by running the app (all fixed)
- Crash on Sign up: negative padding (`IllegalArgumentException: Padding must be non-negative`) → replaced with `offset`.
- Emoji validation rejected ZWJ emoji on JVM (BreakIterator differs from Android ICU) → deterministic counter + tests.
- Two `NewApi` lint errors (MediaStore.Downloads, MediaMetadataRetriever.use on API 26–28) → version-gated.
- Sender lost "Delivered/Seen" after 8 s (cooldown text replaced it) → last status persists 10 min, cooldown moved to hint line, status announced to TalkBack.
- Greeting "Still up?, Asha" → "Still up, Asha?" (unit-tested).

## Added 2026-10-25 (second session)
- ✅ Google sign-in: Android Credential Manager + backend `/auth/google` (hidden until a client ID is configured). Not yet tried with a real Google account.
- ✅ Email verification codes (non-blocking "Confirm your email" card) with console mailer; Resend driver tested with a mocked API only.
- ✅ Anniversary: asked on Home after pairing; shows "together" time, total days, days to next anniversary (verified on emulator).
- ✅ Birthdays (year can be hidden): partner countdown on Home within 60 days and in Timeline (verified on emulator).
- ✅ Partner's local time on Home, with a privacy switch (verified on emulator with America/New_York).
- ✅ Shared notes & checklists with templates and paste-to-items; partner's list appeared on the emulator.
- ✅ Gift stickers (16) and a 72-emoji palette in the composer; quick emoji row in Messages.
- ✅ Personal colour (6 palettes, Rosewood default) and Home background (plain, glow, dusk, pinned photo). Rendering of non-default palettes not screenshot-verified.
- ✅ Release signing from `android/keystore.properties`; `assembleRelease` and `bundleRelease` build (unsigned until the key exists).
- ✅ Backend Dockerfile for Render; deploy steps in `docs/private/MY_SETUP.md`.

| Date | Check | Result |
|---|---|---|
| 2026-10-25 | backend tests | 105 of 105 passed |
| 2026-10-25 | android unit tests / lint | 31 of 31 passed / 0 errors |
| 2026-10-25 (later) | backend tests | 177 of 177 passed |
| 2026-10-25 (later) | android unit tests | 42 of 42 passed |

### More bugs found on the emulator (fixed)
- Opening Timeline crashed (`ClassCastException` from a leftover top-level property). All such leftovers removed.
- Home recomputed its whole state every second even when idle; now 1 s only during countdowns, 30 s otherwise.
- Backend crashed at start on Atlas because the DB user lacks `collMod`; now warns and continues. **Action: grant `dbAdmin`** (see MY_SETUP Step 1). Until then, list changes sync live but are not replayed to a phone that was offline.

### Known, not fixed
- Home briefly shows "—" and "Good morning, you" for a moment on a slow cold start before data loads.
- The emulator raised one "not responding" dialog; the trace showed the main thread waiting on the software GPU, with no app code on the stack. Re-check on a real phone.

## Phone feedback round (2026-10-25)
Built against the user's notes after using the app on their phone. Verified on the emulator against a
local server with the new backend (fresh database); backend 137/137 tests, Android 32/32 unit tests,
lint 0 errors. **The live server needs these backend changes deployed (user pushes; Render redeploys).**

- ✅ Chat order: oldest at top, newest at bottom, bottom-anchored (was reversed).
- ✅ Ping cooldown counts down live (was frozen at 15 s).
- ✅ Partner's phone buzzes: instant notification from the push itself; buzz on any screen when open; both partners asked for notification permission; battery/autostart guidance.
- ✅ Profile photo changes reach the partner (Home, chat, profile) — verified live update within seconds.
- ✅ Partner birthday and weather (Open-Meteo, shared city only) in the partner profile; weather under their name on Home; local time optional in Settings.
- ✅ Colour themes recolour secondary colours, cards, chips, borders and hero labels too.
- ✅ Chat wallpaper per person (5 designs or own photo). Wallpaper Studio and the SET_WALLPAPER permission removed: the app never changes the phone wallpaper or lock screen.
- ✅ Chat photos saved to Memories automatically; view-once photos (open once, screenshots blocked, deleted after viewing).
- ✅ Widgets show the partner's content: their mood/note/emoji (set from "Their widget"), their latest chat photo, latest gift. New Gift widget; widgets allow lock-screen placement where the phone supports it; opt-in lock-screen card shows the latest gift.
- ✅ SOS in the partner profile with a warning; rings on the alarm channel even on silent until opened; optional Do Not Disturb bypass; sender sees "saw your SOS".
- ✅ Also fixed: notification/link taps ignored when the app was already open; false "Offline" banner.
- ❌ Not verified: SOS/ping push with the app closed on real phones (needs the deployed server + Firebase); lock-screen widgets on a real launcher.

## Widgets, game and cleanup round (2026-10-25, later)
Verified on the emulator against a local server running the new backend with push enabled, with
the app process killed for every "closed app" check. Backend 177/177 tests; Android 42 unit tests.

- ✅ **"Us" all-in-one widget** (4×2 by default, resizes 2×2 to 4×4): the partner's newest ping, gift, note/mood, photo or voice note, the countdown, and a ping button. The Widgets screen recommends "Us" plus Touch Ping; the seven single-purpose widgets moved under "More widgets" (still supported). Real preview in the system widget picker.
- ✅ **Widgets update on a ping with the app closed**: the push handler now syncs immediately instead of waiting for a background job (about 12 s end to end on the emulator, mostly cold start). Widgets read their data live, so a second ping during a redraw is not lost.
- ✅ **Widgets as a "they reached out" signal**: an unseen ping lights up "Us", Touch Ping and Emoji in rose with "● New"; Touch Ping turns into "Send one back". Opening Risaina or replying from the widget clears it and sends "Seen".
- ✅ Newest wins: a fresh ping or message replaces an older "Their widget" mood/emoji/photo (it used to stay stuck on the board).
- ✅ Countdown widget said "No date set" although a meeting was set (server sends a timestamp; the widget parsed only a date). Widgets now also refresh on every sync, so day counts stay current.
- ✅ Lock-screen card: moved to a default-importance silent channel, because Android hides low-importance notifications on the lock screen. Shows "<partner> reached out" for unseen pings. Verified on a PIN-locked emulator.
- ✅ **Ping vibration**: a clear two-heartbeat pattern (≈0.6 s of vibration) on a new channel; the old 40–90 ms taps were barely perceptible. Verified: 1.26 s vibration with the app open; 1.24 s notification vibration with the app closed in vibrate mode. On full silent/DND Android suppresses it by design; SOS is the one that breaks through.
- ✅ **Realtime socket died after opening a link or notification**: a second MainActivity started, then the first one's stop cancelled the shared socket, leaving "Will sync when online" and no in-app buzz. The socket now follows the app process's foreground state. Pings that arrive by push while the app is open also buzz.
- ✅ Every sync made 10 requests even when caught up (`return@repeat` only skips one pass). Fixed.
- ✅ **Blind truth or dare**: winner writes a truth AND a dare; the loser sees two sealed cards and picks one; only the chosen text is revealed; the loser marks it done (truth needs an answer); the winner confirms or sends it back with a note. Verified both roles end to end, including the server never sending the hidden texts to the loser.
- ✅ **Unlink deletes the couple's data** (server: messages, pings, memories, milestones, lists, games, widget boards, SOS, invitations, events, media files; phone: local chat, games and widgets). The phone also cleans up if it missed the unlink event.
- ✅ Re-pairing skipped the new couple's first events (event numbers restart per couple; the phone kept the old cursor). The cursor now resets when a different couple becomes active.
- ✅ Home shows gifts as "A gift" with their line, not "A ping"/"Custom". New game cards scroll into view; read receipts no longer mark the wrong messages when game cards are in the chat.
- ⚠️ Atlas: the DB user cannot change an existing collection's validator (`collMod`). Production had no `games` collection, so it is created with the new rules on deploy. Grant `dbAdmin` to avoid this class of problem.
- ❌ Not verified on real phones: OS lock-screen widgets (this emulator's launcher has them disabled; the opt-in lock-screen card works everywhere); vibration on Realme/ColorOS, which may need "Allow background activity" (the Widgets screen links to App info).

## Games stream: tic-tac-toe, trivia duel, This or That (2026-10-26)
Built on the existing server-authoritative games engine (no library fits: Google Play Games turn-based
multiplayer was shut down in 2020, and boardgame.io and Colyseus each need their own server, transport and
storage). Backend 227/227 tests; Android 89 unit tests, lint 0 errors. Not yet run on the emulator or on phones.

- ✅ **Game picker**: the 🎲 composer button opens a sheet with four games (from `GET /games/catalog`; an older
  server answers 404 and only Stone Paper Scissors Thread is offered). Trivia: category and difficulty chips;
  This or That: a deck; competitive games: "Loser does a truth or dare 😈" switch (off = just for fun). With a
  game open it says so ("Go to it" / "End it"), and starting another asks "End the current game?" first. If
  the partner started a game this phone missed, "Let's play" loads it so "End it" appears. The last
  options are remembered after the sheet closes. A 404 catalog is not kept, so the new games appear as soon
  as the backend is deployed.
- ✅ **Tic-tac-toe**: the invited partner plays X and moves first; X swaps on each rematch. A draw offers
  "Play again" or "Call it a draw". Two quick taps can never both land.
- ✅ **Trivia duel**: 5 questions fetched by the server from Open Trivia Database (curated categories only, "any"
  picks one at random each round), with an in-house fallback set when it is slow, down or rate-limited. Questions
  arrive one at a time, the right answer only after you answer, your partner's answers and score only once you
  have both finished (never after a cancel). The CC BY-SA 4.0 credit links to opentdb.com and to the licence,
  and says category names are shortened, on the card, in the picker and in Settings → About.
- ✅ **This or That**: 7 curated PG prompts, picked blind and sent in one go (one event and one push per
  player), then "In sync: n of 7".
- ✅ Winners of tic-tac-toe and trivia get the existing blind truth or dare; GameCard.kt split into
  `feature/messages/games/` (one section per kind plus a shared prize section).
- ✅ Notifications: per-game texts ("Bea challenged you to tic-tac-toe ❌⭕ You go first"); a cancelled or
  called-off game removes a stale "Your turn"; tic-tac-toe moves update the notification without ringing again;
  old game events are not re-announced after a sign-in or re-pair; a partner's single trivia answer updates the
  card from the event without a network call, and a catch-up sync (push, worker or the socket's replay)
  re-fetches each game it touched once at the end instead of once per event (a full replay is one list
  request), so a push-triggered sync stays well inside its 8 s.
- ✅ Compatibility: older apps keep working with `{ kind: 'rps' }` and `/move`; on a new kind of game they get
  "Update Risaina to play this game". Only new optional fields are stored, every required field is still
  written, and a test checks every new document against a frozen copy of the deployed `games` validator.
- ⚠️ **Rollout**: deploy the tolerant-reader commit ("Games: tolerant reader for unknown game kinds") on its own
  first, then the rest of the backend, then install the APK on both phones. Before any rollback of the games
  backend, cancel open tic-tac-toe, trivia or This or That games (an older build would let them block new games).
- ⚠️ **Release order**: the delivery stream's push policy must be merged and deployed before (or with) the games
  backend. Until then `answered` (up to 4 per player per trivia duel), `cancelled` and `ended` are pushed at
  high priority with no visible notification, which FCM may deprioritize.
- ⚠️ Open decision: once `cancelled` and `ended` are no longer pushed, a "Your turn" notification for a game the
  partner cancelled or called off is only cleared by a later sync or by opening the app. Keeping `cancelled`
  pushed but silent would clear it, but conflicts with the brief.
- ⚠️ Trivia with a difficulty chosen asks Open Trivia DB for exactly 5 questions, so recent questions can't be
  skipped and narrow category + difficulty pairs will repeat; one invalid item out of 5 falls back to the
  built-in set. On an older server "Call it a draw" on an rps draw gets a 404 (no `/end` there).
- ❌ Not verified: anything on a device (picker, cards, notifications with the app closed, each game from both
  roles with the process killed, the trivia fallback with opentdb blocked, an old APK on the new server); live
  Open Trivia DB from Render; `GameCardsTest` (instrumented) compiles but was not run.

## Delivery round: widgets and alerts with the app closed, false gift alerts (2026-10-26)
Goal: on a realme/ColorOS phone with Risaina closed, the partner's widgets and notifications change as
promptly as a notification does. Checked with unit tests (backend Vitest, Android JUnit) only: not yet
on the real phones.

- ✅ **Widgets are drawn inside the push handler**: each placed widget is composed with Glance
  `runComposition` and handed to `AppWidgetManager` directly ("Us" first, one 2.5 s deadline for all,
  per-widget fallback to the queued Glance update). Before, `updateAll()` only queued a job that ColorOS
  could freeze until the app was opened.
- ✅ **Push handler order**: SOS first (unchanged), then the safety-net SyncWorker (inside FCM's expedited
  exemption), then a network-free alert for a ping or message, then a sync bounded to 4 s. Each
  notification is posted as its event is applied, so a timeout or a later error never loses one. Photo
  downloads, delivered receipts and game refreshes run after the sync, only with what is left of the 4 s
  (the worker does the rest).
- ✅ **One sync at a time**: the push handler and the workers share a sync instead of fetching side by
  side, but never one that started before their push arrived. Widget recomputes run one at a time, so an
  older one cannot overwrite a newer snapshot.
- ✅ **Push priority per event type** (backend): only events that end in a visible notification are high;
  receipts, lists, memories and other silent changes are normal with one collapse key; game answered,
  cancelled and ended are not pushed. "Their widget" changes stay high and post a quiet "updated your
  widget". Notifications off now sends a silent push (widgets still update) instead of nothing; quiet hours
  now only make the phone silent. (FCM lowers an app whose high pushes rarely show anything.)
- ✅ **Delivery health** card on the Widgets screen: notification permission and channels, battery
  optimisation (one-tap exemption request), background restriction, standby bucket, Data Saver, Play
  services, recent swipe-kills/freezes (Android 11+), force-stop (Android 15+), realme/ColorOS steps and a
  phone-maker settings chain ending at App info, the last push this phone received (with an FCM downgrade
  warning), when the partner's phone last received one, the build id, and "Send me a test push".
- ✅ **Build id**: `versionName` is `0.2.0+<git sha>` and `versionCode` the commit count; shown in Settings
  and in Delivery health, and sent to the server as the device's `appVersion`.
- ✅ **No sign-outs from a killed refresh**: the token refresh and storing its answer cannot be cut in half
  by a caller's timeout, and the server accepts a retry of a just-rotated token for 60 s from the same install
  while the new session is unused.
- ✅ **False gift alerts (on top of 6619f22)**: the calm lock card never shows a gift through the emoji
  fields; it is re-posted only when its content changes (a swipe is respected, a reboot or force-stop brings
  it back) with a stable time; a sign-in or re-pair replays history without notifying, buzzing or revealing
  old gifts (persisted high-water mark, used by both the sync and the socket); opening the app clears the
  gift/ping, game and widget-update notifications (not chat messages); the detailed alert after a quick one
  for the same item is silent; a quick alert never overwrites a notification an earlier sync already posted.
- ✅ **Review fixes**: sign-out, session expiry, account deletion and unlink clear every partner
  notification again, unread chat included (opening the app still keeps chat); a new pairing applied by a
  sync no longer rewinds the event cursor to 0 (which made the next push count as history); the replay mark
  is written once per fresh start; a sync waiting out a retry backoff is replaced by a fresh expedited one on
  the next push; the push is recorded before the worker starts, so its ack reaches the server; each shared
  sync ends after 15 s and one running over 8 s no longer blocks a new push; the photo download runs
  outside the shared sync; the socket runs receipts once after its replay, beside the collector; a queued
  game refresh asked for again while it runs runs again, and a receipt the server keeps refusing is dropped
  after 5 tries; the quick alert can no longer replace the detailed one; game pushes are checked per action
  against the app's notification texts. Delivery health: "I've done this" turns the phone-maker check green
  (a later kill brings it back), and ColorOS phones get an "Auto launch list" button next to App info.
- ❌ Not verified on real phones yet. Main-session device checks: build id on Raina's Realme; with the app
  swiped away and the screen off, a text, a photo and a gift each update the "Us" widget within seconds;
  `dumpsys package com.risaina.app | grep stopped`; Delivery health on ColorOS (which settings screens open;
  "Allow" for the battery exemption); the test push; the lock card on a PIN-locked ColorOS lock screen;
  Glance responsive sizes drawn by `runComposition` on the realme launcher; on ColorOS, what "Auto launch
  list" opens; sign out with message notifications showing (they must all go, an SOS must stay).
- Merge notes for the games stream: `finished` and `matched` are pushed high; add their texts to
  RisainaNotifier's game branch when the server starts emitting them (backend/test/push-policy.test.ts
  fails until then).

## Safety: SOS that rings through silent, live location, panic button (2026-10-26)
Built on branch `stream/safety`. Checked with unit and integration tests only: backend Vitest
(typecheck, lint, build), Android JUnit and lint. **Nothing in this section has run on a real phone
yet**, and the emulator was not used (the main session needed it).

- ✅ **SOS alarm service**: a foreground service started first thing from the high-priority FCM
  message (type `systemExempted` while the exact-alarm permission is held, else `specialUse`). It plays
  the user's alarm tone (bundled tone as backup) on the alarm stream with the alarm volume raised to
  the maximum and put back afterwards (also after a crash, on the next start), vibrates with alarm
  usage (not muted by silent mode), and shows a full-screen alarm screen over the lock screen. It needs
  no notification permission. It rings until "I'm on it" or 5 minutes, never because a screen opened.
  A refused start retries through an alarm clock, then falls back to the old notification alarm.
- ✅ **Do Not Disturb**: SOS channels exist from app start; with DND access the channels bypass DND,
  and a ringtone-stream ringer is added when DND blocks alarms (the alarm stream itself is muted then).
  Total silence still mutes everything: no app can override it.
- ✅ **No false alarms from replays**: rings only if this phone has not already rung, acknowledged or
  seen the SOS resolved (kept on disk), and only within 6 hours (fails open on unreadable times). An
  older SOS in the window is double-checked with the server first. Replaces the 30-minute check.
- ✅ **Sender knows**: delivered receipt from the ringing phone (with device state, no content), re-push
  every minute for 10 minutes until it arrives, "Ringing on X's phone" / "X saw it" / "Not reaching X's
  phone yet. Call X directly." after 30 s, Call and Text after 2 minutes, and "I'm safe now".
- ✅ **"Can an SOS reach you?"** card (Settings → Safety): notifications, SOS channel, full-screen
  alerts, battery, background restriction, DND access, silent mode muting alarms on this phone, the
  backup start, and an OEM auto-launch reminder, each with a button to the right system screen; "Ring
  SOS at full volume" switch; a 5-second local test and a test through the server after 15 s.
- ✅ **Live location**: one switch in Settings (1 hour by default, 8 hours, or until I turn it off),
  a location foreground service started only from a visible screen (no background-location
  permission), a notification the whole time with Stop sharing, a Home banner, and the partner's card
  (freshness, accuracy, battery, Open in Maps) in their profile, on the SOS screen and at
  `risaina://app/location`. The partner is notified when sharing starts. The server keeps only the
  latest position and deletes it on stop, expiry, sign-out, unlink or account deletion.
- ✅ **Panic button**: hold for 2 seconds (Settings → Safety, the launcher long-press shortcut "I'm in
  trouble", or a pinned home-screen icon). Sends an SOS marked panic ("I'm in trouble" by default) and
  shares live location for 2 hours. If it can't be sent: keeps retrying for 30 minutes and offers to
  send a maps link by SMS or WhatsApp. The SOS dialog in the partner's profile can also share location.
- ⚠️ Push priorities for the new event types (`sos.resolved` and `location.started` high,
  `sos.delivered` and `location.stopped` normal) come from the delivery stream's outbox policy. Until
  that merges, every event is still pushed at high priority.
- ❌ Not verified on real phones: the alarm in silent, vibrate, DND and Total silence; with
  notifications denied; from a killed app in Doze; the full-screen screen over the lock screen; volume
  restore; the Realme/ColorOS auto-launch behaviour; location updates with the screen off for a long
  time; the launcher shortcut and pinned shortcut.

### Safety review fixes (2026-10-26)
- ✅ **Panic merged into an existing share**: the server answers a start with the session it already
  had; the phone now takes that id for fixes and the stop (before, the first fix got 409 and sharing
  stopped silently while the partner saw a frozen "in trouble" share).
- ✅ **App open no longer ends a share whose start is still retrying** (offline, server waking up);
  only a session the server had confirmed and no longer has is stopped. A panic whose start never got
  through resumes when Risaina opens.
- ✅ **A panic start never waits behind the network**: the app-open check runs its GET outside the
  lock and drops a stale answer. Stops are written down before they are sent, and every new start
  sends the pending ones first.
- ✅ Re-registering (retry, Resume, app-open restart) sends the time left, so the end never moves
  later. Every retry of the start waits (60 s after a 401 in panic).
- ✅ **A retried SOS whose alarm event never went out is raised** (failed emit, restart between insert
  and event); a retry that finds another request emitting it gets a retryable 503.
- ✅ A stop that races its own start on the server still ends it; a refresh-token reuse revocation
  ends live location, as sign-out does.
- ✅ **An SOS from this APK still goes out on an older server** (as a plain SOS).
- ✅ A resolved older SOS no longer silences a newer one ringing with it; alarm changes run on the
  main thread; the notification-only fallback counts as rung only when it was shown; the alarm screen
  waits for the unlock before opening the location screen; the alarm volume is restored at every
  process start; the panic screen shows the alert's status once the background retry sends it.
- ✅ Tests added: LiveLocationControllerTest, SafetyEventsTest (cursor-0 replay), SosLocalStateTest,
  SosRepositoryTest, ring-removal and requestMinutes tests; backend tests for the emit retry, the
  stop/start race and refresh reuse. Docs: COUNCIL_DECISIONS D-12 (D-10 was taken), PRIVACY_POLICY_DRAFT,
  SECURITY, KNOWN_LIMITATIONS, TESTING.
- ⚠️ **Release order**: backend first. Live location exists only on the new server, and on an older
  one a panic alert goes out as a plain SOS (no panic flag, no retry key).
- ⚠️ **Merging**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is now declared exactly as the delivery stream
  declares it, so the manifests merge into one element. Dtos.kt, RisainaMessagingService.kt, API.md,
  PROGRESS.md (and errors.ts against games) conflict as before: both sides' additions are kept.
- ⚠️ Still open: `sos.resolved`/`location.*` push priorities and bypass need the delivery stream's
  outbox policy (until then a recipient with notifications off or in quiet hours rings until the
  5-minute timeout after "I'm safe"); the first outbox push of `sos.raised` has no `sosId`/`at`
  (outbox.ts is delivery's); no network-free fast clear for `sos.resolved` in the push handler; the
  refresh-reuse grace window is delivery's; Home shows a banner instead of the design's "📍 Live" chip;
  the partner gets no "is sharing" notice for a panic share whose SOS never got through.


## Emulator verification of the merged round (2026-10-26)
All three streams merged into one branch (plus the input-box, chat-scroll and lock-card fixes found while
testing). Backend 338/338 tests; Android 269 unit tests, lint 0 errors. Verified on the Android 16 emulator
against a local server running this code with push enabled, the app process killed for every "closed" case:

| Area | Result |
|---|---|
| Chat box while 5 messages arrive | ✅ typed text kept exactly; box empty after sending, nothing comes back |
| New messages in an open chat | ✅ open at the newest, new arrivals scroll in, reading position kept when scrolled up (bug found and fixed) |
| Widget with the app closed | ✅ "Us" widget updated 8–10 s after the partner's message (push +2 s, sync +6 s, drawn +8 s), glowing "New" |
| Partner updates "Their widget" | ✅ widget redraws and a quiet "updated your widget" notification appears |
| Gift on the lock-screen card | ✅ "A gift from …" only while unseen; calm line (no gift, no time) after opening; calms ~4 s after leaving |
| SOS: notifications OFF, ringer silent, app killed | ✅ alarm service started from the push, alarm sound on the alarm stream, volume raised to max and restored, alarm vibration; opening Risaina shows the alarm screen; "I'm on it" stops it; sender sees delivered + acknowledged |
| SOS with the screen asleep (notifications on) | ✅ full-screen alarm wakes the screen, after giving the alarm notification its own group (Android auto-grouping had suppressed it) |
| Ping with the app open | ✅ 1.25 s heartbeat vibration in the app, no notification |
| Live location | ✅ permission flow, partner sees position/accuracy/battery/expiry, stop deletes it on the server (manual updates every 60 s by design) |
| Panic | ✅ a tap sends nothing, a 2 s hold sends; panic location updates every 15 s; "I'm safe now" resolves the SOS and deletes the location |
| Games | ✅ tic-tac-toe (win → truth-or-dare prize), trivia duel with live Open Trivia DB questions and attribution, This or That ("In sync: 5 of 7") |
| Delivery health card | ✅ build id, last push and priority, widget draws, test push ("It took 0.2 s") |

Not verifiable here: Realme/ColorOS background behaviour on Raina's phone, a real lock screen with a PIN and
fingerprint, OS lock-screen widgets. **The server changes are not live until main is pushed (Render deploys it).**


## Google Play readiness (2026-10-26, branch `play/ready`)
Goal: the internal testing track on Google Play. A policy audit, an independent check of that audit, a
release-build check and a review of the changes (each finding verified again) drove this list.

- **Release build (R8):** Glance created the widget ping action by reflection and R8 had removed its
  constructor; R8 had also merged the 8 widget classes into one, so every widget redrew with the wrong
  layout. Both kept by `proguard-rules.pro`. A release build now refuses a non-`https://` server address
  (checked only when an APK or bundle is written).
- **Permissions Play refuses for this app, removed:** `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  the `systemExempted` service type. The SOS alarm starts as `specialUse` only; the alarm-clock retry uses
  `SCHEDULE_EXACT_ALARM`, which the readiness card now asks for. Battery buttons open Android's list of apps.
- **Disclosures:** privacy policy and account-deletion pages served by the backend at `/privacy` and
  `/delete-account`, linked from Settings, Privacy and About; Open-Meteo credited; location disclosure says
  sharing continues while the app is closed (and says so honestly when notifications are off: panic and SOS
  setup now ask for them but never block on them). Privacy & account and About open from the pairing
  screen, so an account that never paired can delete itself.
- **Upload key:** `docs/private/risaina-upload.jks` (gitignored), read through `android/keystore.properties`.
- **Review accounts:** two paired accounts on the live server for Play's "App access" form
  (`docs/private/play-review-accounts.txt`).

Release build `0.2.0+8f18d57` (versionCode 69) tested on the emulator against the live server: sign-in,
sync, weather, message and ping notifications with the app in the background, SOS ringing in silent mode
with the `specialUse` service and stopped by "I'm on it" (sender saw delivered and acknowledged), and an
unpaired account deleting itself from the pairing screen (email erased on the server). Backend 341/341,
Android 270 unit tests. **Still to do:** the developer name and contact email in
`backend/src/modules/legal/routes.ts`, push so Render serves the pages, then Play Console.
