# Risaina — Always a Little Closer.

A private, native Android app for two people in a long-distance relationship: one-tap **Touch
Pings** that land on your partner's phone, notes, photos and voice notes, a shared timeline,
home-screen widgets, and a Wallpaper Studio. Backed by a small Node.js/TypeScript API with
MongoDB and Cloudflare R2.

> Status: Release A–C functionality is implemented and builds; see `docs/PROGRESS.md` for what has
> actually been verified and what has not. Nothing is deployed.

## Repository layout

| Path | What |
|---|---|
| `android/` | Kotlin + Jetpack Compose app (single `:app` module, AGP 9.4, Kotlin 2.4) |
| `backend/` | Fastify 5 + TypeScript API, MongoDB, R2, FCM, WebSocket, outbox worker |
| `docs/` | Decisions, architecture, API contract, setup, security, release, progress |

## Quick start (local development)

### Prerequisites
- JDK 17, Android SDK (platform 37, build-tools 37.0.0). On this machine they live under
  `/opt/homebrew/opt/openjdk@17` and `/opt/homebrew/share/android-commandlinetools`.
- Node 26 (LTS line) and pnpm 11.
- A MongoDB server (local `mongod`, or Atlas).

### Backend
```bash
cd backend
cp .env.example .env          # edit JWT_SECRET (≥32 chars); defaults use local storage + no push
pnpm install
pnpm test                      # vitest, uses mongodb-memory-server (local mongod binary if present)
pnpm dev                       # http://localhost:8080, OpenAPI UI at /docs
```

### Android
```bash
cd android
# point the app at your backend (emulator → host): default is http://10.0.2.2:8080
echo "risaina.apiBaseUrl=http://10.0.2.2:8080" >> local.properties
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
For a physical phone on the same Wi-Fi use your machine's LAN IP, e.g. `http://192.168.1.20:8080`.
Debug builds allow cleartext HTTP to that host only through `usesCleartextTraffic` being off by
default; see `docs/TROUBLESHOOTING.md` if the app cannot reach the API.

### Push notifications (optional for development)
Background delivery needs Firebase Cloud Messaging. Without it, pings still arrive in real time
while the app is open (WebSocket) and on the next sync. See `docs/FIREBASE_SETUP.md`.

### Media storage
Development uses a local filesystem driver with signed, expiring URLs. Production uses a private
Cloudflare R2 bucket. See `docs/R2_SETUP.md`.

## Documentation
- `docs/COUNCIL_DECISIONS.md` — the decision record (alternatives, trade-offs, open items)
- `docs/ARCHITECTURE.md` — system and code architecture
- `docs/API.md` — the API contract; the running backend serves generated OpenAPI at `/docs`
- `docs/SECURITY.md` — threat model, controls, what is and is not encrypted
- `docs/FIREBASE_SETUP.md`, `docs/R2_SETUP.md`, `docs/MONGODB_SETUP.md`, `docs/DEPLOYMENT.md`
- `docs/TESTING.md` — how to run every test layer and what each covers
- `docs/RELEASE.md` — release checklist and signing
- `docs/KNOWN_LIMITATIONS.md`, `docs/TROUBLESHOOTING.md`
- `docs/PRIVACY_POLICY_DRAFT.md` — where the published privacy policy and account-deletion page live
- `docs/PROGRESS.md` — what is done and verified

## Licences
Fraunces and Inter are bundled under the SIL Open Font License 1.1 (`android/licenses/`).
Material icon path data is Apache 2.0.
