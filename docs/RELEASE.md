# Release checklist

## Signing
1. Create a keystore (keep it out of git): `keytool -genkeypair -v -keystore risaina-release.jks -alias risaina -keyalg RSA -keysize 4096 -validity 10000`
2. Create `android/keystore.properties` (gitignored):
   ```
   storeFile=/absolute/path/risaina-release.jks
   storePassword=...
   keyAlias=risaina
   keyPassword=...
   ```
3. Add a `signingConfigs.release` block to `android/app/build.gradle.kts` that reads that file
   (left out deliberately so the project builds without a keystore).
4. `./gradlew :app:assembleRelease` (or `bundleRelease` for Play).

## Before any public release
- [ ] `risaina.apiBaseUrl` points at the production API over HTTPS
- [ ] `google-services.json` present; push verified end-to-end on two devices
- [ ] Backend: `NODE_ENV=production`, R2 driver, Atlas, strong secret, health green
- [ ] All backend tests pass; Android unit tests and lint pass; instrumented tests run on a device
- [ ] Two-device journey in `docs/TESTING.md` completed
- [ ] Privacy policy reviewed (`docs/PRIVACY_POLICY_DRAFT.md`)
- [ ] Permissions review: INTERNET, ACCESS_NETWORK_STATE, VIBRATE, POST_NOTIFICATIONS,
      RECORD_AUDIO (runtime, on tap), SET_WALLPAPER — nothing else
- [ ] Known limitations acknowledged (`docs/KNOWN_LIMITATIONS.md`)
- [ ] Version code/name bumped in `app/build.gradle.kts`

## Debug APK (the current target)
`cd android && ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Install on both phones with `adb install -r`.
