# Risaina — Progress

Legend: ✅ done and verified · 🟡 implemented, not yet verified · ⬜ not started · ❌ blocked

_Last updated: 2026-10-25 (Phases 1–5 implemented; verification in progress)_

## Environment
- ✅ JDK 17 installed (Homebrew `openjdk@17`)
- ✅ Android command-line tools, platform 36, build-tools 36.0.0, platform-tools installed
- ✅ Gradle 9.6.0 distribution fetched for wrapper bootstrap
- ✅ Node 26.5 / pnpm 11 present; MongoDB 8.3 running locally
- ❌ No Android device or emulator attached (device testing pending)
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
- 🟡 Unit tests written (quiet hours, countdown, ping rules, message rules) — run pending
- 🟡 Lint — run pending

## Phase 2 — Backend foundation
- ✅ Server boots with validated env; `/api/v1/health` → mongo ok, storage local, push disabled
- ✅ Auth (argon2id, JWT access, rotating refresh with reuse detection), `authenticate` + `requireCouple` guards
- ✅ OpenAPI generated from Zod schemas, served at `/docs` (35 paths)
- ✅ `pnpm typecheck` clean (TS 7.0.2); `pnpm test` → 12 files, **63/63 passed** (verified by me after the implementation agent)
- ✅ Security tests: cross-couple isolation, invitation reuse/expiry/revoke/self, sender cannot mark delivered/seen, media non-member 403 + signed URL expiry, device token never in any response, deletion cascade, login 429

## Phase 3 — Pairing & notifications
- ✅ Backend: invitations (hashed, single-use, 24 h), devices, pings (idempotent, cooldown, recipient-only delivered/seen), events log + WS hub + outbox worker with quiet hours — tested
- ✅ Android: network layer with token refresh, session store, WebSocket sync manager, event reducer, FCM service (data-only), notification channels incl. quiet-hours silent channels, haptics, pairing flow, Touch Ping end-to-end code path
- 🟡 Two-device journey — not yet run (no devices; emulator being installed)

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
_(appended after each verified run)_
