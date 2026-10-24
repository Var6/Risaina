# Firebase Cloud Messaging setup

FCM is used only as a transport for background wake-ups. The payload is data-only and contains no
content. The app builds without Firebase; this guide enables background notifications.

## 1. Create the project
1. https://console.firebase.google.com → Add project (Analytics not needed; decline it).
2. Add an Android app with package name **`com.risaina.app`**. For debug builds using the
   `.debug` suffix is *not* configured, so one package name is enough.
3. Download `google-services.json` and place it at `android/app/google-services.json`.
   It is gitignored. The Gradle script applies the Google Services plugin automatically when the
   file exists and sets `BuildConfig.HAS_GOOGLE_SERVICES = true`.

## 2. Backend credentials
1. Project settings → Service accounts → Generate new private key (JSON).
2. Provide it to the backend **without committing it**:
   - `FIREBASE_SERVICE_ACCOUNT_JSON=/absolute/path/to/service-account.json`, or
   - `FIREBASE_SERVICE_ACCOUNT_JSON=<base64 of the JSON>` (for hosts that only support env vars).
3. Restart the backend. `GET /api/v1/health` should report `push.status = "ok"`.

## 3. Verify
- Install the app on a device with Google Play services, sign in and pair.
- Settings → Notifications should no longer show "Push not configured".
- Close the app on device B; send a ping from device A. B should receive a notification within a
  few seconds; the outbox event in MongoDB shows `push.state = "sent"`.

## Notes
- Devices without Play services (some Chinese OEMs, pure AOSP) will not receive background pushes;
  the app still syncs on open and over the WebSocket while visible.
- The Firebase Emulator Suite is not used because FCM has no emulator.
