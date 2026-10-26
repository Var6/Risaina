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
