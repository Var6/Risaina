# Going live — step by step

This is the shortest path from "runs on my Mac" to "two Android phones using Risaina".
No secret values belong in this file. Real values live only in `backend/.env` (gitignored) and
`android/local.properties` (gitignored).

## What already works
- Backend runs locally and is connected to **your MongoDB Atlas cluster** (`/api/v1/health` → mongo ok).
- Android app builds; sign-up → pairing → Home verified on the emulator against this backend.

## The cheapest setup (recommended)

| Piece | Service | Cost |
|---|---|---|
| Database | MongoDB Atlas M0 | free (done) |
| Photos / voice notes | Cloudflare R2 | free tier is enough for two people (Cloudflare may ask for a card to enable R2) |
| Sign-in | Google sign-in | free; Google already verifies the email, so no email service is needed for those users |
| Push notifications | Firebase Cloud Messaging | free |
| Backend hosting | see step 5 | free for testing, a few dollars a month for always-on |
| App distribution | install the APK directly | free |
| Email codes (optional) | Resend | free tier, but needs a domain you own to email anyone but yourself |

There is no separate web frontend: the Android app *is* the frontend.

---

## Step 0 — Database permissions (Atlas)
In Atlas → Database Access, give the app's user the **dbAdmin** role on its database (keep its
read/write role). Without it the server logs a warning, Google-only sign-ups fail, and shared-list
changes are not stored in the event history.

## Step 1 — Google sign-in (≈10 minutes, makes "Get started" one tap)
1. Open https://console.cloud.google.com → create a project "Risaina".
2. **APIs & Services → OAuth consent screen**: External, app name "Risaina", your email as support
   and developer contact. Add yourself and your partner as **test users** while the app is in testing.
3. **Credentials → Create credentials → OAuth client ID → Web application**, name "Risaina server".
   Copy the client ID (ends in `.apps.googleusercontent.com`). It is not secret.
4. **Create credentials → OAuth client ID → Android**:
   - Package name: `com.risaina.app`
   - SHA-1 (debug builds from this Mac): `46:04:CA:27:9F:D3:78:B0:E4:1D:78:9C:7C:EA:03:CB:7D:C5:BA:E7`
   - Add another Android client later with your **release** key's SHA-1.
5. Put the **Web** client ID in both places:
   - `backend/.env`: `GOOGLE_CLIENT_IDS=<web client id>`
   - `android/local.properties`: `risaina.googleWebClientId=<web client id>`
6. Rebuild the app. The Welcome screen now shows **Continue with Google**.

## Step 2 — Cloudflare R2 for photos and voice notes (≈10 minutes)
1. https://dash.cloudflare.com → R2 → enable R2 → **Create bucket** `risaina-media`. Keep it private.
2. R2 → **Manage API tokens** → Create token → *Object Read & Write*, limited to that bucket.
3. Add to `backend/.env`:
   ```
   STORAGE_DRIVER=r2
   R2_ACCOUNT_ID=...
   R2_ACCESS_KEY_ID=...
   R2_SECRET_ACCESS_KEY=...
   R2_BUCKET=risaina-media
   R2_ENDPOINT=https://<account id>.r2.cloudflarestorage.com
   ```
4. Restart the backend; `/api/v1/health` should say `storage.driver: "r2"`.

## Step 3 — Push notifications (≈10 minutes)
Follow `docs/FIREBASE_SETUP.md`: add an Android app `com.risaina.app` in Firebase, put
`google-services.json` in `android/app/`, and give the backend the service-account JSON via
`FIREBASE_SERVICE_ACCOUNT_JSON`. Without this, pings still arrive while the app is open.

## Step 4 — Email codes (optional)
Only needed for people who sign up with email instead of Google.
1. https://resend.com → sign up → add and verify a domain you own (DNS records).
2. `backend/.env`: `MAILER=resend`, `RESEND_API_KEY=...`, `MAIL_FROM=Risaina <hello@yourdomain>`.
Without this, the backend prints codes to its own log in development, and email users can still use
the app; they just see a gentle "Confirm your email" card.

## Step 5 — Put the backend on the internet
The backend must keep running (it holds live connections). Pick one:

**A. Free, for testing with your phone today — a tunnel from your Mac**
```bash
brew install cloudflared
cloudflared tunnel --url http://localhost:8080
```
It prints an `https://<random>.trycloudflare.com` address. Set `PUBLIC_BASE_URL` in `backend/.env`
to that address, restart the backend, and use the same address as `risaina.apiBaseUrl` in
`android/local.properties`. It works only while your Mac is awake and the command is running.

**B. Always on — a small hosted container** (Railway, Render, Fly.io, or a tiny VPS such as Hetzner).
- Build: `cd backend && pnpm install --frozen-lockfile && pnpm build`; start: `node dist/server.js`.
- Health check path: `/api/v1/health/ready`.
- Copy every variable from your `backend/.env` into the host's secret settings, then set
  `NODE_ENV=production`, `HOST=0.0.0.0`, `PUBLIC_BASE_URL=https://<your host>`.
- Avoid free tiers that sleep when idle: a sleeping server delays pings until it wakes.
- In Atlas → Network Access, allow the host's outbound IP.
See `docs/DEPLOYMENT.md` for a Dockerfile.

## Step 6 — Install on your Android phone and check it live
1. Phone: Settings → About phone → tap **Build number** 7 times → Developer options → **USB debugging** on.
2. Connect by USB, accept the prompt on the phone, then:
   ```bash
   cd android
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Or send `app-debug.apk` to the phone and open it (allow "install unknown apps").
3. Your partner installs the same APK on their phone.
4. Live checks:
   - Open `https://<backend>/api/v1/health` in the phone's browser: you should see `"status":"ok"`.
   - Watch the app's network log while you use it: `adb logcat -s RisainaHttp RisainaWs`.
   - Watch the server log (`pnpm dev` output, or your host's log view).
   - Journey: both sign in with Google → one creates a code → the other joins → tap the big button →
     the other phone shows the ping → the sender's status moves Sent → Delivered → Seen.

**Same Wi-Fi only (no tunnel):** set `HOST=0.0.0.0` and `PUBLIC_BASE_URL=http://<Mac LAN IP>:8080`
in `backend/.env`, and `risaina.apiBaseUrl=http://<Mac LAN IP>:8080` in `android/local.properties`.
Debug builds allow plain HTTP; release builds require HTTPS.

## Step 7 — Before calling it "done"
Work through `docs/RELEASE.md`: release signing key, HTTPS API URL, two-phone journey, privacy
policy review.
