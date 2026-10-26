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
