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
