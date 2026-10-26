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
- **An SOS cannot ring on a phone where Risaina was force-stopped** (or swiped away on phones that
  treat that as a force-stop): Android delivers no push until Risaina is opened again. Without a
  delivered receipt the sender soon sees "Not reaching <name>'s phone yet. Call <name> directly."
- **Do Not Disturb**: "Total silence" blocks every sound. In priority mode without alarms, the SOS
  sounds only with Do Not Disturb access granted, and on phones whose silent mode also mutes alarms
  it can only vibrate in silent mode (the SOS readiness card says so).
- **Live location stops when Android stops Risaina** and does not restart by itself after a reboot
  or a force-stop (there is no background location permission). Reopening Risaina shows a Paused
  banner; a panic share resumes by itself. Phone makers' battery managers (realme/ColorOS) can stop
  it sooner unless Risaina may run in the background.
- **The first panic position depends on GPS**: indoors it can take up to 20 seconds or be coarse
  (the accuracy is shown to the partner).
- **This app version needs the updated server for panic alerts.** On an older server an SOS still
  goes out, as a plain SOS (no panic flag, no retry key).
- **Export** is bounded to 1000 items per collection.
- **Device testing**: no physical device was attached during development; see PROGRESS.md for what
  was verified on the emulator.
