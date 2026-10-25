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

### More bugs found on the emulator (fixed)
- Opening Timeline crashed (`ClassCastException` from a leftover top-level property). All such leftovers removed.
- Home recomputed its whole state every second even when idle; now 1 s only during countdowns, 30 s otherwise.
- Backend crashed at start on Atlas because the DB user lacks `collMod`; now warns and continues. **Action: grant `dbAdmin`** (see MY_SETUP Step 1). Until then, list changes sync live but are not replayed to a phone that was offline.

### Known, not fixed
- Home briefly shows "—" and "Good morning, you" for a moment on a slow cold start before data loads.
- The emulator raised one "not responding" dialog; the trace showed the main thread waiting on the software GPU, with no app code on the stack. Re-check on a real phone.
