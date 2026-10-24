# Troubleshooting

**App shows "You're offline" but the phone has internet.**
The API base URL is wrong or unreachable. Emulator → host is `http://10.0.2.2:8080`; a real phone
needs your machine's LAN IP. Set `risaina.apiBaseUrl` in `android/local.properties` and rebuild.
Plain HTTP works in debug builds only through Android's default cleartext rules: if blocked, add a
`network_security_config` for your LAN host or use HTTPS.

**Pings only arrive when the app is open.**
Push is not configured (Settings → Notifications says so). Follow `docs/FIREBASE_SETUP.md`.

**"Push not configured" even though google-services.json exists.**
Rebuild after adding the file: the Gradle script reads it at configuration time.

**Partner accepted the code but my screen still says Waiting.**
Tap "Check again" (pulls `/me`). The live update needs the WebSocket, which only runs while the app
is in the foreground.

**Voice recording fails to start.**
Microphone permission was denied; grant it in system settings. On emulators without a mic the
recorder reports "Couldn't start recording".

**Widgets look stale.**
Open the app once (sync + refresh), or remove and re-add the widget. Android batches updates.

**Backend: `Local storage routes must not be registered in production`.**
You set `NODE_ENV=production` with `STORAGE_DRIVER=local`. Use R2 in production.

**Backend tests cannot start MongoDB.**
Install `mongodb-community` (Homebrew) or let `mongodb-memory-server` download a binary (needs
network and `allowBuilds` for its postinstall, which is disabled by default in this repo).
