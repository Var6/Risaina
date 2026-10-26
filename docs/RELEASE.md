# Release checklist

## Signing (Google Play upload key)
1. The upload key lives outside git (`docs/private/` is gitignored). To make a new one:
   `keytool -genkeypair -v -keystore risaina-upload.jks -storetype PKCS12 -alias upload -keyalg RSA -keysize 4096 -validity 10000`
   Back it up. If it is ever lost, Play Console → Setup → App signing can reset the upload key; the app
   signing key itself is held by Google (Play App Signing).
2. `android/keystore.properties` (gitignored) points Gradle at it:
   ```
   storeFile=/absolute/path/risaina-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
   `app/build.gradle.kts` reads this file; without it a release build is unsigned.
3. `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` (upload this to
   Play). `:app:assembleRelease` makes a signed APK of the same build for testing on a phone.

## Version numbers
versionCode is the number of commits on the branch you build from, versionName is `0.2.0+<commit>`.
Play refuses a versionCode that is not higher than the last upload, so build every upload from `main`
(or a branch ahead of it), and never squash or force-push `main`.

## Before any Play upload
- [ ] `risaina.apiBaseUrl` is the production API over `https://` (a release build refuses anything else)
- [ ] `google-services.json` present; push verified end to end on two devices
- [ ] Backend: `NODE_ENV=production`, R2 driver, Atlas, strong secret, health green, and the deployed
      version serves `/privacy` and `/delete-account`
- [ ] All backend tests pass; Android unit tests and lint pass
- [ ] The R8 release build tried on a phone: sign-in, chat with a photo, a ping from a widget, an SOS
      test, live location start and stop
- [ ] Permissions review. Allowed on Play: INTERNET, ACCESS_NETWORK_STATE, VIBRATE, POST_NOTIFICATIONS,
      RECORD_AUDIO (on tap), ACCESS_NOTIFICATION_POLICY, USE_FULL_SCREEN_INTENT (declared in Play
      Console), FOREGROUND_SERVICE with SPECIAL_USE (SOS) and LOCATION (live location), WAKE_LOCK,
      SCHEDULE_EXACT_ALARM, ACCESS_FINE/COARSE_LOCATION. Not allowed for this app: USE_EXACT_ALARM,
      REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, the systemExempted service type, ACCESS_BACKGROUND_LOCATION
- [ ] Known limitations acknowledged (`docs/KNOWN_LIMITATIONS.md`)

## Debug APK (sideloading)
`cd android && ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
A debug build cannot update a Play install (different signing key): uninstall one before the other.
