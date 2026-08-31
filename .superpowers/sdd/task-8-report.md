# Task 8 report — Release verification (agent preparation only)

**Status:** DONE_WITH_CONCERNS  
**Branch:** `docs/external-ssh-tunnel-session-auth`  
**HEAD:** `754e844` — docs: record owner passes for Hermes restart and tunnel stop/start  
**Not pushed. Not merged.**  
**Device matrix:** partial owner coverage only (items 1, 3–7). Never claim full device pass.

## Commits

| Hash | Subject |
|---|---|
| `90438f7` | test: pin release merged manifests against global cleartext |
| `99dcbb2` | feat(ui): keep compact tunnel warning, Test, and Save on screen |
| `53c2deb` | docs: record compact tunnel setup above-the-fold follow-up |
| `983643c` | docs: record partial owner device observations for tunnel matrix |
| `754e844` | docs: record owner passes for Hermes restart and tunnel stop/start |

Screenshot goldens were not updated and were not committed.

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
./gradlew --no-daemon validateDebugScreenshotTest   # at 53c2deb; goldens not updated
```

| Gate | Result |
|---|---|
| `testDebugUnitTest` (full, HEAD `754e844`, 2026-08-31) | **965 tests, 7 failed** — all `NativeOAuthTest` (environmental socket bind / assertion; not chased) |
| `MergedManifestCleartextTest` | 5/5 |
| `lintDebug` | pass |
| `assembleDebug` | pass |
| Release merged manifest | no `usesCleartextTraffic="true"`; `networkSecurityConfig` present; no `src/release` overlay (pinned by `MergedManifestCleartextTest`) |
| `validateDebugScreenshotTest` (at `53c2deb`, post compact-fold) | **27 tests, 12 failed** — see screenshot status. Goldens not updated |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` (refreshed 2026-08-31) |
| Protocol smoke | ran at agent prep (see below) |
| Device matrix | **partial owner only** — items 1, 3–7 reported pass; 2, 8–12 and adaptive hardware still open |

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

**Owner visual-review blocker.** Goldens were not written or updated.

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

## Owner blockers (remaining)

1. **Screenshot goldens** — 9 missing + 3 compact diffs. Owner visual review required. Do not ship the screenshot gate as green. Candidates at `.superpowers/sdd/screenshot-candidates/`.
2. **Device matrix (incomplete)** — owner-reported pass on items 1, 3–7 only. Still open: item 2 (second SSH client), items 8–12 (network transitions, lock/battery, reboot, wrong service, two-port isolation), and adaptive layout on real compact/medium/expanded hardware. Checklist: `docs/external-ssh-tunnel-device-checklist.md`. An agent must not mark untested rows pass.
3. **IPv6 `::1` NSC matching** — unverified on physical device (prefer `127.0.0.1` until confirmed).

## Residual risks (for a future PR)

Keep the pull request honest. These are structural, not cosmetic:

- **HTML-scrape coupling.** The session token is adopted from `window.__HERMES_SESSION_TOKEN__` in the loopback root HTML. There is no first-class local-client adoption endpoint. If upstream Hermes publishes one, retire the scrape rather than maintain it. This is why the mode is Experimental.
- **Shared-loopback exposure.** Any other app on the device can reach the forwarded port and obtain the same token. Android loopback is not app-private. Setup UI and user docs already warn; do not describe it as sandboxed.
- **Handshake HTTP 403 vs 4401.** A rejected-token WebSocket *upgrade* on live `v0.20.4` is HTTP 403 with an empty body, not a WebSocket close `4401`. Credential recovery is proven for an already-open socket whose peer then closes `4401`, and for REST `401`. A Hermes restart that drops sockets and refuses the next upgrade with 403 may not enter the `4401` branch until a REST `401` also arrives. Distinguishing `4401` from `4403` is impossible from handshake 403 alone. Left as instructed; do not map HTTP 403 to credential refresh.
- **Screenshot goldens are not approved.** Nine new tunnel/recovery previews have no references. Three compact existing goldens mismatch. Owner must review pixels before updating references.
- **Device matrix is incomplete.** Owner reported pass on items 1, 3–7 (cold/test handshake, chat, background/reopen, kill/restart, Hermes restart, tunnel stop/start). Items 2, 8–12 and adaptive hardware remain untested. Agent partial screenshot corroborates item 7 chrome only. This report is not a full device pass.

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

**Still untested by owner (as of this note):** second SSH client (item 2), network transitions (item 8), lock/battery (item 9), device reboot (item 10), wrong local service (item 11), two-port isolation (item 12), adaptive layout on hardware, screenshot golden approval.

Screenshot goldens remain **not approved**. Device matrix remains **not complete**.

## Agent observed (2026-08-31 ~15:02)

Agent-captured device screenshot. **Not owner prose.** Partial only — matrix **not complete**.

| Observation | Detail |
|---|---|
| Surface | Session list with tunnel down |
| Banner | **SSH tunnel unavailable** with **Retry** |
| Cached list | Still visible |
| Body copy | This app cannot start the tunnel |
| Owner follow-up | Restore forward + Retry confirmed (item 7 pass) |

Screenshot goldens remain **not approved**. Device matrix remains **not complete**.

## Out of scope

- Updating screenshot reference images
- Device/ADB/SSH E2E
- Mapping handshake HTTP 403 to 4401
- Push, merge, PR creation
- Independent complete-feature spec/security reviews (not requested in this dispatch)
- Restoring global cleartext
