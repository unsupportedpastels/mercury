# Task 7 report — Product UI and user documentation

**Status:** DONE_WITH_CONCERNS  
**Branch:** `docs/external-ssh-tunnel-session-auth`  
**Base:** `9d540b8` (Task 6)  
**HEAD:** `d503c6a`  
Not pushed.

## Commits

| Hash | Subject |
|---|---|
| `22dfb53` | feat(ui): add External SSH tunnel setup, Test tunnel, and recovery copy |
| `28c7723` | docs: describe External SSH tunnel setup, warnings, and recovery |
| `5e32490` | test: add screenshot previews for tunnel setup and recovery |
| `a02ecdf` | docs: limit Nous sign-in to Cloud and Server URL |
| `5c45ceb` | fix(connection): classify gated tunnel by dedicated message |
| `90bacfd` | test: apply large-text scale and pin credential-rejected actions |
| `d503c6a` | test: pin testTunnel as a non-mutating classified handshake |

## What shipped

### Connection type selector

Three explicit choices on the server settings screen: Hermes Cloud, Server URL, **External SSH tunnel**. Compact uses filter chips; medium/expanded uses segmented buttons. Draft origins are isolated (`serverUrlDraft` vs `tunnelOriginDraft`); switching modes does not leak the previous origin.

Save evaluates origin/transport with the **chosen** connection mode, so legal tunnel HTTP `http://127.0.0.1:9119` can be saved. Save stays disabled while validation fails (including Direct cleartext).

### Tunnel setup

- Forward is outside this app; Termius is a vendor-neutral example.
- Default origin `http://127.0.0.1:9119`.
- Numeric loopback only; `localhost` rejected with the Task 6 IPv4/IPv6 explanation.
- Any valid local port.
- Never asks to paste a token.
- **Test tunnel** runs a non-mutating handshake (`testTunnel`); does not save the catalog or keep the token.
- Setup checklist: SSH connected, host key pinned in the SSH app, forward active, browser `/api/status` returns Hermes JSON, battery/background for the SSH app.
- Experimental label plus “Why experimental?” reasons.
- Shared-loopback warning **before** Save: any other app on this device can reach the forwarded port and obtain the same token. Copy never calls Android loopback app-private or sandboxed.

### Recovery

`ConnectionRecoveryBanner` on session list and session detail. Distinct copy/actions for TunnelUnavailable, CredentialRejected (wording from the answers doc; Retry / Connection setup / Cancel), NotHermesEndpoint, BootstrapRejected, gated OAuth, ProtocolIncompatible, InvalidTunnelOrigin, CleartextPolicyBlocked, InstallationChanged (Accept new server / Cancel).

If the snapshot is Recovering **and** the failure is InstallationChanged, the title is “Hermes installation changed” with accept/cancel — not generic reconnect.

Tunnel unavailable: this app cannot start the tunnel; Retry retries this app only.

Cached session lists and transcripts stay visible while offline and stay labelled as cached.

Rejected mutation shows the operation error plus an explicit **Retry action** button; it is never silently replayed.

### Documentation

- `README.md` — three connection modes and a short SSH/Tailscale note.
- `docs/requirements.md` — External SSH tunnel product bullet.
- `docs/testing.md` — tunnel + recovery matrix.
- `docs/external-ssh-tunnel-setup.md` — vendor-neutral forward fields, `127.0.0.1`, Test tunnel, experimental + security warnings, recovery, battery caveats, and that HAM neither controls SSH nor stores SSH credentials or the session token.

No new exported component. Session token still not persisted. No SSH inside the app. No copy that this app can restore the tunnel.

## Tests

UI tests in `ExternalSshTunnelSetupTest` (12) at compact, medium, expanded, and large text: setup instructions and URL validation, Test tunnel success, tunnel-unavailable actions, wrong-service vs bootstrap vs gated, InstallationChanged, mode switch without leakage, process restoration, Retry action.

`TunnelRecoveryCopyTest` (8), including Recovering + InstallationChanged winning over generic reconnect.

Existing `HermesAppTest` cleartext save assertion updated: Save is disabled and the HTTPS message is shown.

Verification strategy: unit/UI tests (no device). Screenshot validation run; goldens not updated.

## Verification

```
./gradlew --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks testDebugUnitTest
./gradlew --no-daemon lintDebug assembleDebug
git diff --check
./gradlew --no-daemon validateDebugScreenshotTest
```

| Gate | Result |
|---|---|
| `testDebugUnitTest` | 959 tests, 7 failed — all `NativeOAuthTest` (environmental; not chased) |
| Task 7 tests | 12 + 8 + related settings/cloud tests green; `HermesAppTest` 108/108 |
| `lintDebug` | pass |
| `assembleDebug` | pass |
| `git diff --check` | pass |
| `validateDebugScreenshotTest` | 27 tests, 12 failed — see screenshot status |

## Screenshot / human-review status

**Owner visual-review blocker.** Goldens were not written or updated.

Missing references (candidates rendered under `app/build/outputs/screenshotTest-results/preview/debug/rendered/`):

- External SSH tunnel setup — compact / medium / expanded
- Tunnel unavailable — compact / medium / expanded
- Installation changed — compact / medium / expanded

Outdated references (image comparison):

- Compact server settings — expected: three-option selector and extra tunnel-policy copy
- Compact active session workspace — rendered screen looks like the existing workspace; likely pixel-level vs golden
- Compact dark markdown table — same class of mismatch

Report: `app/build/reports/screenshotTest/preview/debug/index.html`

Compact tunnel setup at 400×900 now keeps connection type, origin, Experimental, the shared-loopback warning, Test tunnel, and Save on the first screen (Compose-asserted, no scrolling). Checklist sits after Save. Owner still reviews screenshot goldens before replacing references.

## Concerns

1. Screenshot gate cannot close without owner approval of new and changed goldens.
2. NativeOAuthTest (7) environmental failures — same class as Task 6; not chased.
3. Handshake HTTP 403 is still not 4401 — left as instructed.
4. Compact tunnel setup Compose test now keeps warning, Test tunnel, and Save on the first screen; owner still must review screenshot goldens.
5. Compact active session and markdown table mismatches may be renderer flake rather than a product change; owner should confirm before replacing those goldens.

## Out of scope

- Task 8 except the gates this task requires
- Updating screenshot reference images
- Push, SSH inside the app, token persistence, new exported components

## Fix round — spec review Important + cheap Minors

Addressed the spec-review Important README lead-in and the five cheap Minors. Did not start Task 8. Did not update screenshot goldens. Not pushed.

| Finding | Resolution |
|---|---|
| README Nous sign-in covers every mode | Nous is limited to Hermes Cloud and Server URL. Tunnel points at the setup doc and the scrape/in-memory token story. Copy says HAM does not start or restore the tunnel. Loopback is not described as private. |
| Large-text UI test was compact-only | `largeTextSetupShowsInstructionsAndRejectsLocalhost` now applies `DeviceConfigurationOverride.FontScale(1.5f)`. |
| No ViewModel `testTunnel` test | Success does not save, select, or store a token. Localhost and wrong-service stay classified. |
| Credential-rejected banner had no Compose test | Banner shows Retry / Connection setup / Cancel and fires those callbacks. |
| Leftover `assertTrue(true)` | Deleted. |
| Gated vs bootstrap used `contains("OAuth")` | Compares to `GATED_TUNNEL_TARGET_MESSAGE`. A bootstrap error that merely mentions OAuth stays Bootstrap unavailable. |

Verification (fresh):

```
./gradlew --no-daemon --no-configuration-cache --no-build-cache testDebugUnitTest --tests '*ExternalSshTunnelSetup*' --tests '*TunnelRecoveryCopy*' --tests '*HermesConnectionViewModel*'
```

139 tests, 0 failed: ExternalSshTunnelSetup 13, TunnelRecoveryCopy 8, HermesConnectionViewModel 118. NativeOAuthTest not run. Screenshot goldens not updated.

Remaining concerns unchanged: owner visual-review of screenshot goldens, NativeOAuthTest environmental failures, compact setup below the fold, HTTP 403 still not 4401.

## Compact above-the-fold follow-up

HEAD after this note is on `docs/external-ssh-tunnel-session-auth`. Did not push. Did not update screenshot goldens. Did not claim device verification.

On compact width, tunnel setup now puts Experimental, the shared-loopback warning, Test tunnel, and Save **above the checklist**. The checklist and Termius/setup copy sit after Save so they cannot push the warning below Save. Compact 400×900 Compose test asserts connection type, origin, Experimental, warning, Test tunnel, and Save are displayed without scrolling, with warning and Test tunnel above Save. Medium/expanded still show the full guide before Save.

Also pinned protocol-incompatible recovery banner copy in Compose (distinct from wrong-service / bootstrap / gated).

Verification:

```
./gradlew --no-daemon --no-configuration-cache --no-build-cache testDebugUnitTest --tests '*ExternalSshTunnelSetup*' --tests '*TunnelRecoveryCopy*'
./gradlew --no-daemon lintDebug
```

15 ExternalSshTunnelSetup + 8 TunnelRecoveryCopy, 0 failed. lintDebug pass. NativeOAuthTest not run. Screenshot goldens not written. Owner still reviews compact/medium/expanded pixels and the device matrix.
