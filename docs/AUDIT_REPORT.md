# Risaina — Architecture Review Board & QA Audit

_Audit of the implementation as built on 2026-10-25. Findings are against the actual code and
test output, not the plan. Severity: **Critical** (data exposure / wrong-state claims),
**High** (feature broken or misleading), **Medium** (quality, robustness), **Low** (polish)._

Status legend: ✅ fixed and re-verified · 🟡 fixed, verification pending · ⬜ open

## Scope note
The repository was empty when this work started, so the audit ran continuously alongside
implementation: each build/test cycle was treated as an audit pass. This document records the
findings that survived long enough to matter, plus the residual risks.

---

## 1. Visual quality
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| V1 | Default Material theme would have read as a template | High | Initial scaffold used stock `MaterialTheme` | Custom tokens: plum/ivory palette with AA-checked text colours, Fraunces display serif + Inter, 4-dp spacing, single radius scale, hero panels, tactile pills | ✅ Screens reviewed in code; 🟡 on-device screenshots pending emulator |
| V2 | Empty states as gaps | Medium | — | Every list has a serif empty state with a next action (`EmptyState`) | ✅ UI test `emptyAndErrorStates_exposeRetry` written; 🟡 run pending |

## 2. Motion
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| M1 | Magic-number durations scattered | Medium | — | `MotionTokens` (instant/fast/standard/expressive/ambient, springs) used everywhere; reduced motion from OS animator scale **or** in-app toggle collapses to fades | ✅ code; 🟡 device |
| M2 | Animation as the only state signal | High | Ping button | State is also rendered as text and as `stateDescription` for TalkBack | ✅ `touchPing_*` UI tests written |
| M3 | Spring specs typed as `AnimationSpec` broke `slideInVertically` | Low | compile error build 4 | Return `FiniteAnimationSpec` | ✅ build 6 green |

## 3. Android correctness
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| A1 | compileSdk 36 rejected by current AndroidX (requires 37) | High | `checkDebugAarMetadata`: 25 issues | compileSdk 37 (platform 37.2 installed), targetSdk 36 | ✅ build 6 |
| A2 | Cleartext HTTP to a local backend blocked on API 28+ | High | Platform default | Debug-only `network_security_config` permitting cleartext; release untouched | 🟡 emulator run |
| A3 | Hilt `default()` provider is a Java keyword | Medium | KSP error build 2 | Renamed | ✅ |
| A4 | Lint crashes in `ExperimentalDetector` on Kotlin 2.4 UAST | Medium | `Unexpected owner function: null` | Disabled that detector only; compiler `-opt-in` flags still enforce opt-ins | 🟡 lint rerun pending |
| A5 | NavHost start destination changed with gate → graph swap | Medium | Code review | Start fixed at first non-loading gate; gate changes reset the stack only when leaving the gate's own route family (deep links preserved) | 🟡 |
| A6 | Vibration: `createPredefined` only on Q+ | Low | — | Fallback `createOneShot` for API 26–28 | ✅ code |
| A7 | WorkManager default initializer conflicts with Hilt worker factory | Medium | — | Initializer removed in manifest; `Configuration.Provider` on the app | ✅ build |
| A8 | Widgets must not touch the network / make privacy decisions | High | design | Widgets read a privacy-filtered `WidgetSnapshot`; refreshed via `updateAll` after events; `updatePeriodMillis=0` | 🟡 launcher test pending |
| A9 | Wallpaper applied silently | Critical if present | — | Confirmation dialog with explicit target (home/lock/both); `isSetWallpaperAllowed` checked | ✅ code |
| A10 | Secret recording | Critical if present | — | `MediaRecorder` starts only from the recorder sheet's tap after runtime permission; cancel deletes the file; 3-minute hard cap | ✅ code |

## 4. Backend
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| B1 | Authorization boundary | — | `plugins/authenticate.ts`: live session required; `requireCouple` derives couple from the user document, never from input | n/a | ✅ 63/63 tests incl. isolation suite |
| B2 | Indexes vs. queries | — | `backend/docs/DATA_MODEL.md`; `ensureIndexes()` at boot | n/a | ✅ boot log |
| B3 | Retries | — | Outbox: lease + exponential backoff up to 5, unregistered tokens pruned | n/a | ✅ outbox suite |
| B4 | Error handling | — | Envelope `{error:{code,message}}`, Zod → 400, 429 with Retry-After, no stack traces | n/a | ✅ health-openapi + rate-limit suites |
| B5 | Domain write + event insert not transactional | Medium | `publisher.ts` | Documented (standalone Mongo); client dedupes by seq; acceptable for two users | ⬜ accept |

## 5. Storage
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| S1 | R2 objects private, short-lived URLs | — | `media/service.ts`: server-generated keys, presign PUT 10 min / GET 15 min, HEAD + magic-byte verification, cross-couple 403 | n/a | ✅ media suite (local driver); ⬜ not exercised against live R2 (no credentials) |
| S2 | Local dev driver path traversal | High if present | `local.ts`: `isSafeStorageKey` + `path.resolve` containment; HMAC covers key/expiry/method | n/a | ✅ code review |

## 6. Realtime reliability
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| R1 | Durability | — | Events written with per-couple atomic `seq` before fan-out; replay via `sync{since}` and `GET /events` | n/a | ✅ events-ws suite |
| R2 | Duplicates | — | Android reducer ignores `seq ≤ cursor`; idempotency keys on pings/messages | n/a | ✅ unit (server); 🟡 device |
| R3 | Single-instance hub | Medium | `hub.ts` in-process | Documented in D-04 / DEPLOYMENT | ⬜ accept |

## 7. Security (cross-user access)
Verified by backend tests: user C cannot read AB's pings/messages/memories/media/events; third user
cannot join; expired/revoked/own invitations rejected; sender cannot set delivered/seen; device
tokens never serialized; account deletion cascades. ✅

## 8. Privacy
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| P1 | Notification content on lock screen | High | — | `previewInNotifications` toggle; `VISIBILITY_PRIVATE` with a public version that says only "New activity"; quiet-hours silent channels | 🟡 device |
| P2 | Logs | High | Ktor `Logging` at INFO logs method+URL only in debug; backend pino redaction list + query-string stripping | n/a | ✅ code review |
| P3 | Backups | Medium | — | DataStore, Room, media cache excluded from cloud backup & device transfer | ✅ manifest/xml |
| P4 | Photo metadata | Medium | — | `ImageCompressor` re-encodes to JPEG (EXIF incl. GPS dropped) | ✅ code |

## 9. Performance
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| F1 | Downloading every image when the timeline opens | Medium | — | Grid items resolve/download lazily on composition; cached files reused; widgets downsample to ≤720 px | 🟡 device |
| F2 | Upload size | Medium | — | Photos resized to 2048 px JPEG q85 before upload; voice 64 kbps mono AAC | ✅ code |
| F3 | Debug APK 24.7 MB | Low | build output | Expected for debug (no shrinking); release has R8 + resource shrinking | ⬜ measure release |

## 10. Testing — what is actually verified
- **Backend:** `pnpm typecheck` clean; `pnpm test` 12 files / 63 tests passed (run by the implementation agent and independently re-run by the reviewer). Server boots; `/health` and `/docs/json` respond.
- **Android:** `assembleDebug` BUILD SUCCESSFUL. Unit tests: 19/20 passed on first run; the failing emoji-cluster test exposed a real portability bug (JVM vs ICU BreakIterator) → fixed with a deterministic counter; rerun pending at time of writing (see PROGRESS.md for the final numbers).
- **Not yet verified:** instrumented UI tests, two-device journey, widgets on a launcher, wallpaper apply, FCM delivery (no Firebase project), R2 (no credentials).

## Update — emulator verification (2026-10-25)
| # | Finding | Severity | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| E1 | Sign-up screen crashed on open | Critical | logcat `Padding must be non-negative` at AuthScreens.kt:47 | `Modifier.offset` | ✅ sign-up completed on emulator |
| E2 | Sender's Delivered/Seen vanished after 8 s | High (untruthful-by-omission feedback) | screenshot: "Next ping in 4s" after partner opened | status line persists; live region | ✅ "Mira saw it" visible 25 s later |
| E3 | Greeting punctuation | Low | "Still up?, Asha" | `Format.greetingFor` | ✅ unit test |
| E4 | Incoming-ping card overlaps the top of Home for 9 s | Low | screenshot | acceptable; dismiss with "Later" | — |

The independent 10-dimension multi-agent board review was started but did not complete before the
session ended; its findings are therefore **not** included here. Re-run it on request.
