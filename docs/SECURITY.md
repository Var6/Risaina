# Security

## Model
The backend is the only authorization boundary. The Android app holds no privileged credential:
no MongoDB URI, no R2 keys, no Firebase service account. Identity is a short-lived access token;
couple membership is derived server-side from the authenticated user on every request.

## Controls implemented

| Area | Control |
|---|---|
| Passwords | argon2id, minimum 10 characters, never logged |
| Sessions | HS256 access tokens (15 min, `sub`, `sid`); opaque 256-bit refresh tokens stored as SHA-256; rotation on every refresh; reuse detection revokes the session family; logout revokes session + device token |
| Authorization | `authenticate` requires a live session; `requireCouple` loads the *active* couple from the user document; couple/user IDs in request bodies are never trusted |
| Invitations | 10-char Crockford base32, stored hashed, 24 h expiry, single use (atomic), self-accept rejected, 5 attempts / 15 min per user+IP |
| Pings | idempotency keys; server cooldown ≥ 15 s and ≤ 40/hour; only the recipient may set delivered/seen |
| Media | server-generated object keys; presigned PUT 10 min / GET 15 min; `complete` verifies HEAD size + Content-Type + magic bytes; cross-couple access denied; owner-only delete; abandoned uploads cleaned up; private bucket |
| Push | data-only FCM payload `{type,eventId,coupleId,seq}`; no content; tokens never returned by any endpoint |
| Realtime | token sent in the first frame (never in the URL); 5 s auth timeout; 8 KB frame limit; per-couple fan-out; bounded per-socket queue; revoked on unlink/logout |
| HTTP | helmet security headers, CORS allow-list, global + per-route rate limits, under-pressure, body limits, request IDs, structured error envelope without stack traces |
| Logging | pino redaction of tokens, passwords, emails, message text, invitation codes, signed URLs; query strings stripped from request logs |
| Android | tokens in app-private DataStore excluded from cloud backup and device transfer; notification public version reveals nothing; media cache private; microphone used only on explicit tap; wallpaper only after confirmation |
| SOS | 3 per sender per 10 min (durable); an idempotency key makes a retry return the first SOS (never a second alarm); only the recipient acknowledges or confirms delivery, only the sender says "I'm safe"; the alarm screen shows no message on a locked phone when previews are off; the app never changes the ringer mode, Do Not Disturb, the wallpaper or the lock screen |
| Live location | one overwrite document per sharer, no history; fixes reach only the partner as WebSocket frames that are never stored; coordinates never in events, pushes or logs (redacted); 12 fixes/min and 20 manual starts/hour per user; stop needs no couple and wins a race with its own start (tombstone); sign-out, refresh-token reuse, unlink and account deletion delete the session |

## Live location safeguards (coercive control)
- Only the sharer can start sharing, from a visible screen on their own phone. There is no way to
  request or switch on the partner's location, and no background-location permission.
- A notification with "Stop sharing" is visible on the sharer's phone for the whole time.
- Stopping deletes the position on the server at once; nothing is kept as history or in the export
  beyond the sharer's own current session.
- Stopping works even without a couple (after unlink) and when the server is unreachable (it is
  delivered later, and before any new start).

## Not implemented / honest limits
- **No end-to-end encryption.** Transport is TLS; at-rest encryption is the provider's (Atlas, R2).
  The server can read content. A separate design review (key storage, device change, backup,
  recovery) is required before any E2EE claim.
- Android tokens are not additionally encrypted with the Keystore (open item O-03).
- Password reset needs an email provider; none is configured (console mailer in development).
- Single-instance backend: the WebSocket hub and invitation attempt limiter are in-process.
- Domain write + event insert are not in a multi-document transaction (standalone MongoDB). For SOS,
  a retry with the same idempotency key emits a missing `sos.raised` (see D-12).
- Live location is not end-to-end encrypted: the server sees the latest position while sharing.

## Reporting
This is a private, two-user application. Report issues to the repository owner directly.
