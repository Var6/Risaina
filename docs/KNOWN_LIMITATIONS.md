# Known limitations

- **Push requires Firebase configuration** (`google-services.json` + service account). Without it,
  nothing reaches a closed app; the app says so in Settings → Notifications.
- **Password reset emails are not delivered** until an email provider is wired into the mailer
  interface (console mailer only).
- **No end-to-end encryption**; see SECURITY.md.
- **Single backend instance** assumed for WebSocket fan-out.
- **Widgets update when Android allows.** Updates are requested after events and on the hourly sync;
  launchers may coalesce or delay them. Custom fonts are not available in widgets (RemoteViews),
  so widgets use the system serif.
- **Lock-screen wallpaper** application depends on the OEM; some skins ignore `FLAG_LOCK` or crop
  differently. The app always asks before applying.
- **Voice notes** use AAC/MP4; iOS interoperability is irrelevant (Android only) but some very old
  devices may lack hardware AAC encoders.
- **HEIC uploads** are accepted by the server but the app always re-encodes to JPEG before upload.
- **Devices without Google Play services** get no background push.
- **Export** is bounded to 1000 items per collection.
- **Device testing**: no physical device was attached during development; see PROGRESS.md for what
  was verified on the emulator.
