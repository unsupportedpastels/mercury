# Task 8 report — Release verification (agent preparation only)

**Status:** CLOSED (owner 2026-08-31) — connection/setup verified; deferred follow-ups not blockers  
**Branch:** `docs/external-ssh-tunnel-session-auth`  
**HEAD:** `eb91a92` — docs(sdd): record PASS spec review for tunnel layout fix (includes layout fix `ad5d7de`)  
**Not pushed. Not merged.**  
**Device matrix:** partial owner coverage only (items 1, 3–7). Owner closed the goal 2026-08-31. Never claim full device pass. Do not claim items 2, 8–12, adaptive hardware, or IPv6 `::1` were tested.

## Commits

| Hash | Subject |
|---|---|
| `90438f7` | test: pin release merged manifests against global cleartext |
| `99dcbb2` | feat(ui): keep compact tunnel warning, Test, and Save on screen |
| `53c2deb` | docs: record compact tunnel setup above-the-fold follow-up |
| `983643c` | docs: record partial owner device observations for tunnel matrix |
| `754e844` | docs: record owner passes for Hermes restart and tunnel stop/start |
| `ad5d7de` | fix(ui): screenshot-review layout bugs for tunnel setup and installation changed |
| `abd1791` | docs(sdd): independent security review for task-7 layout fix |
| `eb91a92` | docs(sdd): PASS spec review for tunnel layout fix |

Screenshot goldens owner-approved 2026-08-31 (approve all 12: 9 missing + 3 compact mismatches). Updated via `updateDebugScreenshotTest`; `validateDebugScreenshotTest` 27/27 pass.

## What this session did

Agent preparation only. No physical phone. No ADB device. No SSH credentials.

1. Full unit / lint / assemble / whitespace gates.
2. Task 6 leftover: release merged-manifest assertion (plus `processReleaseMainManifest` inspection). Did not restore global cleartext.
3. Screenshot validation. Did not update references. Collected rendered candidates and the three compact diffs.
4. Debug APK for the owner matrix.
5. Owner checklist with diagnostics: `docs/external-ssh-tunnel-device-checklist.md`.
6. Optional protocol smoke against local Hermes `v0.20.4` on `127.0.0.1:9119`. Token values were never printed.
7. Residual-risks paragraph for a future PR (below).

Whole-feature spec review PASS (`.superpowers/sdd/task-8-feature-spec-review.md`, `90438f7`). Whole-feature security review PASS (`.superpowers/sdd/task-8-feature-security-review.md`, `90438f7`). Compact-fold follow-up spec PASS and security PASS (`task-7-compact-fold-review.md`, `task-7-compact-fold-security.md`, `53c2deb`).

## Verification

```
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew --no-daemon testDebugUnitTest --tests '*MergedManifestCleartextTest'
./gradlew --no-daemon validateDebugScreenshotTest   # at HEAD post ad5d7de; goldens not updated
```

| Gate | Result |
|---|---|
| `testDebugUnitTest` (full, HEAD `3e9a3df`, 2026-08-31) | **967 tests, 7 failed** — all `NativeOAuthTest` (environmental socket bind / assertion; not chased) |
| `testDebugUnitTest` (full, HEAD `754e844`, 2026-08-31, prior) | **965 tests, 7 failed** — all `NativeOAuthTest` (environmental socket bind / assertion; not chased) |
| `MergedManifestCleartextTest` | 5/5 |
| `lintDebug` | pass |
| `assembleDebug` | pass |
| Release merged manifest | no `usesCleartextTraffic="true"`; `networkSecurityConfig` present; no `src/release` overlay (pinned by `MergedManifestCleartextTest`) |
| `validateDebugScreenshotTest` (pre-update, 2026-08-31) | **27 tests, 12 failed** (9 missing + 3 mismatched) |
| `updateDebugScreenshotTest` + `validateDebugScreenshotTest` (owner-approved goldens, 2026-08-31) | **27/27 pass** (JVM preview path; no emulator) |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` (refreshed 2026-08-31) |
| Protocol smoke | ran at agent prep (see below) |
| Device matrix | **partial owner only** — items 1, 3–7 reported pass; owner closed goal 2026-08-31; items 2, 8–12, adaptive hardware, IPv6 `::1` deferred (not blockers) |

NativeOAuthTest failures (7):

- `nativeLoginTimesOutAStalledRequestAndContinuesToValidCallback` — `SocketException`
- `nativeLoginBoundsRequestLineBeforeContinuingToValidCallback` — `SocketException`
- `nativeLoginShowsFailurePageForOAuthErrorCallback` — `AssertionError` at NativeOAuthTest.kt:340
- `nativeLoginRejectsMalformedRequestsAndContinuesToValidCallback` — `SocketException`
- `nativeLoginIgnoresClientDisconnectBeforeRequestLine` — `SocketException`
- `nativeLoginAcknowledgesCallbackBeforeTokenExchangeFailure` — `AssertionError` at NativeOAuthTest.kt:370
- `nativeLoginWaitsForBrowserReturnBeforeRedeemingCode` — `SocketException`

Same environmental class as Tasks 6–7. Not chased.

The two leftover tests were added **after** the 961-test `--rerun-tasks` run. They were verified in a focused 5/5 run, not by repeating the full `--rerun-tasks` suite. TDD: **no RED observed** for the leftover. The release merge was already clean; the tests pin that.

## Screenshot / human-review status

**Owner-approved 2026-08-31 (approve all).** Twelve goldens recorded: 9 new tunnel/recovery previews + 3 compact replacements (active session, markdown table, server settings). `validateDebugScreenshotTest`: 27/27 pass.

Report: `app/build/reports/screenshotTest/preview/debug/index.html`

Rendered candidates: `app/build/outputs/screenshotTest-results/preview/debug/rendered/`

Image diffs (existing goldens that did not match): `app/build/outputs/screenshotTest-results/preview/debug/diffs/`

### Missing goldens (9) — `ScreenshotImageNotFoundException`

- Compact / medium / expanded External SSH tunnel setup
- Compact / medium / expanded tunnel unavailable
- Compact / medium / expanded installation changed

Rendered files exist for all nine under `.../rendered/com/unsupportedpastels/hermesandroid/ui/HermesAppScreenshotTestKt/`.

### Compact mismatches (3) — `ImageComparisonAssertionError`

Diffs:

- Compact server settings
- Compact active session workspace
- Compact dark markdown table

Compact tunnel setup at 400×900 (post `99dcbb2`): warning, Test tunnel, and Save are on the first screen; checklist starts after Save and may be partially cut off. Confirm pixels when approving goldens (see `.superpowers/sdd/screenshot-candidates/README.md`).

## Protocol smoke

`hermes dashboard --no-open --port 9119` was available. Live Hermes **v0.20.4**. Token values were never printed. Dashboard stopped afterward.

| Probe | Result |
|---|---|
| `GET /api/status` | 200, version `0.20.4`, `auth_required=false`, `install_id` present (value not recorded) |
| `GET /` | 200, 1719 bytes, one `__HERMES_SESSION_TOKEN__` assignment |
| sessions, no token | 401 `{"detail":"Unauthorized"}` |
| sessions, wrong session header | 401 `{"detail":"Unauthorized"}` |
| sessions, valid session header | 200 |
| WS rejected token (pre-accept) | **HTTP 403**, empty body — still not close `4401` |
| WS valid token | HTTP 101 |

## Owner close (2026-08-31)

Owner explicitly closed the External SSH tunnel goal: **connection/setup works.** Remaining matrix rows are **deferred as unrelated follow-up, not blockers:**

- Item 2 (second SSH client)
- Items 8–12 (network transitions, lock/battery, reboot, wrong service, two-port isolation)
- Adaptive layout on real compact/medium/expanded hardware
- IPv6 `::1` NSC matching on physical device (prefer `127.0.0.1` until confirmed)

An agent must not invent a device pass or claim those deferred rows were tested. Checklist: `docs/external-ssh-tunnel-device-checklist.md`.

Screenshot goldens: **no longer a blocker** (owner-approved 2026-08-31).

## Residual risks (for a future PR)

Keep the pull request honest. These are structural, not cosmetic:

- **HTML-scrape coupling.** The session token is adopted from `window.__HERMES_SESSION_TOKEN__` in the loopback root HTML. There is no first-class local-client adoption endpoint. If upstream Hermes publishes one, retire the scrape rather than maintain it. This is why the mode is Experimental.
- **Shared-loopback exposure.** Any other app on the device can reach the forwarded port and obtain the same token. Android loopback is not app-private. Setup UI and user docs already warn; do not describe it as sandboxed.
- **Handshake HTTP 403 vs 4401.** A rejected-token WebSocket *upgrade* on live `v0.20.4` is HTTP 403 with an empty body, not a WebSocket close `4401`. Credential recovery is proven for an already-open socket whose peer then closes `4401`, and for REST `401`. A Hermes restart that drops sockets and refuses the next upgrade with 403 may not enter the `4401` branch until a REST `401` also arrives. Distinguishing `4401` from `4403` is impossible from handshake 403 alone. Left as instructed; do not map HTTP 403 to credential refresh.
- **Device matrix is partial, not complete.** Owner reported pass on items 1, 3–7 (cold/test handshake, chat, background/reopen, kill/restart, Hermes restart, tunnel stop/start). Owner closed the goal 2026-08-31; items 2, 8–12, adaptive hardware, and IPv6 `::1` are deferred follow-ups, not blockers. Agent partial screenshot corroborates item 7 chrome only. This report is not a full device pass.

Also still true from earlier tasks: NativeOAuthTest environmental failures (7); IPv6 `::1` NSC matching unverified on device; no `src/release` overlay today, but a future overlay is what the new test is for.

## Owner observed (2026-08-31)

Owner-reported on a physical device. **Not agent-observed.** Partial matrix only — not complete.

| Area | Owner report |
|---|---|
| Tunnel setup Test handshake | Succeeded (earlier session) |
| Chat over tunnel | Works, including images |
| Background → reopen | Cached → connected; reconnect works |
| Swipe from Recents → reopen | Same as background |
| Settings Force stop → reopen | Same as background |
| Hermes restart (item 6) | Recovered without pasting a token |
| Tunnel stop/start (item 7) | Stop Termius forward → tunnel unavailable (not stuck connected); restore forward + Retry |

**Deferred by owner (2026-08-31, not blockers):** second SSH client (item 2), network transitions (item 8), lock/battery (item 9), device reboot (item 10), wrong local service (item 11), two-port isolation (item 12), adaptive layout on hardware, IPv6 `::1` NSC matching. **Not tested — do not claim pass.**

Owner closed the External SSH tunnel goal 2026-08-31: connection/setup works. Screenshot goldens: **owner-approved 2026-08-31**. Device matrix remains **not complete**.

## Agent observed (2026-08-31 ~15:02)

Agent-captured device screenshot. **Not owner prose.** Partial only — matrix **not complete**.

| Observation | Detail |
|---|---|
| Surface | Session list with tunnel down |
| Banner | **SSH tunnel unavailable** with **Retry** |
| Cached list | Still visible |
| Body copy | This app cannot start the tunnel |
| Owner follow-up | Restore forward + Retry confirmed (item 7 pass) |

Screenshot goldens: **owner-approved 2026-08-31**. Device matrix remains **not complete**.

## Gate refresh (2026-08-31, HEAD `3e9a3df`)

`./gradlew testDebugUnitTest lintDebug assembleDebug` — **lint pass, assemble pass, unit 967/7 failed** (all `NativeOAuthTest`, environmental; not chased). Screenshot goldens owner-approved; `validateDebugScreenshotTest` 27/27 at this commit (not re-run this dispatch).

## Screenshot golden update (2026-08-31, owner-approved)

Owner waived visual review and approved all 12 goldens (9 missing + 3 compact mismatches). Commands: `./gradlew --no-daemon updateDebugScreenshotTest` then `./gradlew --no-daemon validateDebugScreenshotTest`. JVM preview path; no emulator. Before: **27 tests, 12 failed**. After: **27/27 pass**. `git diff --check` clean on changed files. Full `testDebugUnitTest` not re-run this session.

## Out of scope

- Device/ADB/SSH E2E
- Mapping handshake HTTP 403 to 4401
- Push, merge, PR creation
- Independent complete-feature spec/security reviews (not requested in this dispatch)
- Restoring global cleartext
