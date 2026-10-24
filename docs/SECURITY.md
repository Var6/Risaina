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

## Not implemented / honest limits
- **No end-to-end encryption.** Transport is TLS; at-rest encryption is the provider's (Atlas, R2).
  The server can read content. A separate design review (key storage, device change, backup,
  recovery) is required before any E2EE claim.
- Android tokens are not additionally encrypted with the Keystore (open item O-03).
- Password reset needs an email provider; none is configured (console mailer in development).
- Single-instance backend: the WebSocket hub and invitation attempt limiter are in-process.
- Domain write + event insert are not in a multi-document transaction (standalone MongoDB).

## Reporting
This is a private, two-user application. Report issues to the repository owner directly.
