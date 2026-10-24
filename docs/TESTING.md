# Testing

## Android
```bash
cd android
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:lintDebug                # Android lint
./gradlew :app:connectedDebugAndroidTest  # instrumented tests (device/emulator required)
```
Unit tests cover: quiet-hours windows (incl. midnight crossing and time zones), countdown and
"together" calculations, the Touch Ping state machine and cooldown, emoji/message validation,
message and voice validation. Instrumented tests: see `app/src/androidTest`.

## Backend
```bash
cd backend
pnpm typecheck && pnpm lint && pnpm test
```
Vitest with `mongodb-memory-server` (uses `/opt/homebrew/bin/mongod` when present). Suites:
auth, rate-limit, invitations, pings, messages, media, isolation (cross-couple), events-ws,
outbox, devices-account, memories-milestones, health-openapi.

## Security tests (backend)
- User C cannot read couple AB's pings, messages, memories, media URLs or events.
- A third user cannot accept an accepted invitation; expired and revoked codes are rejected.
- The sender cannot mark their own ping delivered/seen; the recipient can; it is idempotent.
- A non-member cannot obtain a media URL; signed URLs expire.
- Device tokens never appear in any response body.
- Account deletion revokes sessions, ends the couple and emits `couple.unlinked`.

## Two-device journey (manual)
1. Backend running; both phones on the same network with `risaina.apiBaseUrl` set.
2. A: sign up → Pairing → Create code. B: sign up → Enter code → Join.
3. A sees "Paired with B" after the `couple.updated` event; both reach Home.
4. B taps Touch Ping → A (app open) sees the reveal; A's app acknowledges → B sees "Delivered".
5. A opens the ping → B sees "Seen".
6. Messages: text, photo, voice note both ways; read receipts; delete own message.
7. Memories: add, pin → home card and photo widget update.
8. Timeline: start date, next visit → countdown widget.
9. Settings: quiet hours → ping during window arrives silently; previews off → lock screen shows "New activity".
