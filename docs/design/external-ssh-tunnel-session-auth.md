# External SSH Tunnel Session Authorization

**Status:** Proposed  
**Audience:** HAM maintainers and Android implementers  
**Analyzed revision:** `56950106ae7fd335d29195eae3b46af87849aaca` (`v0.2.1`)  
**Server contract:** unchanged upstream `NousResearch/hermes-agent`

## Summary

Add an explicit **External SSH tunnel** connection mode to HAM. The user configures local port forwarding in Termius or another SSH application; HAM connects only to the resulting Android loopback origin, adopts the legacy Hermes dashboard session token advertised by the loopback server, uses that token for protected REST and WebSocket traffic, and automatically reacquires it after process death, tunnel recovery, or Hermes restart.

The app does **not** implement SSH, store SSH keys, invoke Termius, start remote commands, or require server changes. The external SSH client remains responsible for host-key verification, SSH authentication, tunnel lifetime, and reconnecting the forward.

The core decision is:

- persist the **connection mode and loopback origin**;
- keep the legacy session token **in memory only**;
- reacquire the token from the local tunneled dashboard whenever a connection generation starts or a credential is rejected;
- preserve the existing OAuth/native-PKCE path for gated remote servers.

## Motivation and current failure

A correctly configured SSH local forward can make the remote loopback dashboard reachable at an Android loopback URL:

```text
HAM
  -> http://127.0.0.1:<local-port>
  -> external SSH app local forward
  -> SSH host
  -> remote 127.0.0.1:9119
  -> hermes serve / hermes dashboard
```

The current HAM probe treats `auth_required:false` as meaning that protected APIs need no credential. That is not the current Hermes loopback contract:

- `GET /api/status` is public and returns `200` with `auth_required:false`.
- Protected REST routes require `X-Hermes-Session-Token` (legacy `Bearer` is also accepted).
- `/api/ws` and other protected WebSockets require `?token=<session-token>`.
- The loopback dashboard injects the token into its shell HTML as `window.__HERMES_SESSION_TOKEN__="..."`.
- Invalid WebSocket credentials close with code `4401`.

Observed against Hermes `v0.20.4` on 2026-08-21:

```text
GET /api/status                         -> 200
GET /api/profiles/sessions (no token)  -> 401
GET /api/profiles/sessions (header)    -> 200
GET /api/auth/me (legacy token)         -> 401
```

This explains why the same Termius forward works in a browser but HAM reports `Could not reach Hermes Serve`: the browser dashboard adopts the bootstrap token; HAM does not.

Relevant current code:

- `ServerOrigin.kt` accepts HTTP/HTTPS origins and derives WS/WSS.
- `HermesConnectionClient.kt::probe` loads sessions without credentials when `authRequired == false`.
- `HermesConnectionViewModel.kt::connect` only models no-auth or persisted native OAuth tokens.
- `NativeTokenStore.kt` stores OAuth token sets with Android Keystore-backed AEAD.
- `HermesChatGateway.kt` always mints a single-use OAuth WebSocket ticket and only builds `?ticket=` URLs.
- `StreamingSpeechTransport.kt` follows the same ticket-only assumption.

## Goals

1. Connect HAM through a local SSH port forward created by Termius or another Android SSH client.
2. Support current upstream Hermes loopback REST and WebSocket authorization without server changes.
3. Persist enough configuration to reconnect automatically after app/process restart without asking for a token.
4. Recover automatically when Hermes rotates its session token or the external tunnel temporarily disappears.
5. Keep OAuth behavior unchanged for non-loopback/gated servers.
6. Scope all connection state and credentials to the normalized server origin.
7. Produce specific, actionable tunnel/authentication errors instead of a single generic reachability message.
8. Never log, display, export, back up, or persist the adopted session token.

## Non-goals

- Implementing an SSH stack inside HAM.
- Importing, generating, or storing SSH private keys.
- Controlling Termius or another SSH application through undocumented intents or accessibility automation.
- Starting, installing, updating, or reconfiguring Hermes on the SSH host.
- Bypassing SSH host-key verification.
- Adding custom Hermes routes, plugins, dashboard extensions, or server forks.
- Supporting a legacy session token over arbitrary LAN or public origins.
- Treating Android loopback as an app-isolated security boundary.

## User setup contract

### Prerequisites on the remote host

The remote host must run an unchanged released Hermes backend on loopback, for example:

```text
127.0.0.1:9119
```

Either `hermes serve` or `hermes dashboard --no-open` is acceptable when it exposes the official dashboard/API/WebSocket contracts. The process must remain supervised independently of HAM.

### External SSH application

HAM setup must explain that port forwarding is configured outside HAM. Termius is the primary example, but the language must remain vendor-neutral.

Example rule:

```text
Type:                    Local port forward
SSH host:                existing working VPS host
SSH port:                normally 22
Local bind address:      127.0.0.1
Local port:              9119 (or another unused local port)
Remote destination host: 127.0.0.1
Remote destination port: 9119
```

If local port `9119` is occupied, any unused local port is valid. For example, a forward from Android `127.0.0.1:19119` to remote `127.0.0.1:9119` is configured in HAM as:

```text
http://127.0.0.1:19119
```

HAM does not need or store the SSH hostname, username, password, key, or remote port. Those remain inside the SSH app.

### In-app setup

Add a connection type selector:

- **Hermes Cloud** — existing discovery and OAuth flow.
- **Server URL** — existing direct HTTP(S)/OAuth flow.
- **External SSH tunnel** — new explicit mode.

For External SSH tunnel:

1. Explain that Termius/another SSH app must already have an active local forward.
2. Default the origin to `http://127.0.0.1:9119`.
3. Permit only literal loopback hosts: `127.0.0.1`, `[::1]`, and optionally `localhost`.
4. Recommend literal `127.0.0.1` to avoid Android IPv4/IPv6 `localhost` ambiguity.
5. Accept any valid local port.
6. Provide a **Test tunnel** action that performs the non-mutating handshake described below.
7. Never ask the user to paste the session token.

The setup page should include a concise checklist:

- SSH connection succeeds in the external app.
- SSH host key has been reviewed and pinned by that app.
- Local forwarding rule is active.
- Browser test `http://127.0.0.1:<port>/api/status` returns Hermes JSON.
- Battery/background restrictions are configured for the SSH app when persistent access is expected.

## Authorization modes

Introduce an explicit, non-secret authorization abstraction rather than continuing to pass an ambiguous `String accessToken` through every layer.

```kotlin
sealed interface HermesCredential {
    class NativeBearer private constructor(/* secret */) : HermesCredential
    class LoopbackSession private constructor(/* secret */) : HermesCredential
}
```

Requirements:

- Secret values are private and bounded.
- `toString()` is always redacted.
- Equality/debug output must not reveal values.
- The type exposes narrowly scoped operations such as applying REST auth and producing a WebSocket auth strategy; callers do not read raw values casually.

REST mapping:

```text
NativeBearer    -> Authorization: Bearer <access-token>
LoopbackSession -> X-Hermes-Session-Token: <session-token>
```

WebSocket mapping:

```text
NativeBearer    -> POST /api/auth/ws-ticket -> /api/ws?ticket=<single-use-ticket>
LoopbackSession -> /api/ws?token=<session-token>
```

The same mapping applies to authenticated speech WebSockets: OAuth continues to use fresh tickets; loopback session mode uses `?token=`.

Do not attempt `/api/auth/me` with a loopback session token. The current server rejects that route with `401`; successful protected session listing is the authorization proof.

## Persisted model

Extend `ServerCatalogEntry` with local, non-secret connection metadata:

```kotlin
enum class ServerConnectionMode {
    Direct,
    ExternalSshTunnel,
}
```

Persist `connection_mode` in `PersistedServerCatalogEntry`. Existing entries migrate to `Direct`.

Rules:

- `ExternalSshTunnel` requires a loopback origin.
- `Direct` retains current URL and OAuth behavior.
- Connection mode is part of local metadata, not a credential.
- Tokens remain outside `ServerCatalog`, preserving its credential-free invariant.
- Removing an entry clears any in-memory credential and origin-scoped caches according to existing removal policy.

### Token persistence decision

Do **not** persist the legacy session token, even with Android Keystore encryption.

Reasons:

1. Hermes generates a new token after restart unless the operator pins `HERMES_DASHBOARD_SESSION_TOKEN`.
2. The token is already recoverable through the active local tunnel.
3. Persisting it creates stale-secret and backup/migration complexity without improving reconnect reliability.
4. Process recreation can reacquire it quickly and safely from the same loopback endpoint.

“Remember authorization” therefore means persisting `ExternalSshTunnel + origin` and automatically reacquiring the current token, not storing the token itself.

Maintain an in-memory credential record keyed by normalized origin and connection generation. Serialize acquisition with a `Mutex`/shared deferred so concurrent REST, metadata, chat, and speech startup cannot scrape or rotate credentials independently.

## Bootstrap protocol

### Successful cold connection

```text
HAM                     local forward                  Hermes loopback
 |                            |                               |
 | GET /api/status ---------->|------------------------------>|
 |<---------------------------| auth_required:false           |
 |                            |                               |
 | GET / -------------------->|------------------------------>|
 |<---------------------------| shell HTML with token         |
 |                            |                               |
 | GET /api/profiles/sessions                                |
 | X-Hermes-Session-Token ----------------------------------->|
 |<------------------------------------------------------- 200|
 |                            |                               |
 | WS /api/ws?token=... ------------------------------------->|
 |<------------------------------------------------ connected|
```

Algorithm:

1. Require explicit `ExternalSshTunnel` mode and a loopback origin.
2. Fetch `GET /api/status` without credentials.
3. Validate bounded JSON and the expected Hermes shape/version.
4. If `auth_required:true`, do **not** scrape the shell. Route to the existing advertised OAuth flow or report that the tunnel target is a gated server.
5. Fetch `GET /` with redirects disabled and a bounded response (64 KiB is sufficient for the current 1.7 KiB shell and leaves compatibility headroom).
6. Extract only the exact `window.__HERMES_SESSION_TOKEN__=<JSON string>` assignment. Do not execute JavaScript and do not use a WebView.
7. JSON-decode the string, enforce a conservative maximum length, reject blank/control-character values, and wrap it immediately in `LoopbackSession`.
8. Verify the credential with a protected, read-only sessions request using `X-Hermes-Session-Token`.
9. Publish `ConnectionState.Connected` and `AuthenticationState.Authenticated` only after verification.
10. Open chat/speech WebSockets with the legacy `token` query parameter.

The parser must tolerate surrounding/minified HTML but must fail closed when the exact assignment is absent, duplicated ambiguously, malformed, or oversized.

### Backward compatibility for direct no-auth servers

Keep the current direct-mode behavior for older servers that genuinely return sessions without a credential:

- `Direct + auth_required:false`: retain one unauthenticated protected-read attempt.
- `ExternalSshTunnel + auth_required:false`: always bootstrap the session token first.

Do not silently switch a saved Direct connection into tunnel mode. The user must select the trust model explicitly.

## Reconnection and lifecycle

### Credential rotation

A Hermes restart can invalidate the in-memory session token while leaving the SSH tunnel alive.

Treat either of these as legacy credential rejection:

- protected REST returns `401` while using `LoopbackSession`;
- authenticated WebSocket closes with `4401`.

Recovery:

1. Invalidate the in-memory loopback credential for the current origin/generation.
2. Close affected metadata/chat/speech sockets without closing remote runtime sessions.
3. Re-run bootstrap once.
4. Retry/reconcile the failed operation using existing generation guards.
5. If a second credential rejection occurs in the same recovery attempt, stop and surface a specific error. Never loop indefinitely.

Do not blindly replay prompts, approvals, secrets, terminal input, or other controller operations. Reuse HAM’s existing status/inflight/durable-transcript reconciliation.

### Tunnel unavailable

Connection refusal, timeout, or reset at a loopback origin in External SSH tunnel mode should produce:

```text
SSH tunnel unavailable
Start or reconnect the local port forward in Termius or your SSH app, then retry.
```

HAM cannot start the third-party tunnel. A button may retry HAM’s connection, but must not imply that it controls Termius.

### Automatic retry policy

- On app foreground: retry immediately using the existing foreground-reconnect path.
- While foreground and waiting for a configured tunnel: bounded exponential backoff with jitter, for example `1s, 2s, 5s, 10s, 30s` capped at 30 seconds.
- While background with no active turn: stop polling; reconnect when foregrounded.
- While an accepted turn is active and HAM’s existing foreground notification/service keeps recovery work alive: continue bounded reconnect/reconciliation without replaying the turn.
- On Android network availability change: an immediate retry may supplement the timer, but network state is not proof that the loopback listener exists.
- Manual **Retry** remains available and resets the waiting interval.

Only one connection/rebootstrap job may run for an origin generation. Existing stale-generation checks must continue preventing old tunnel/token results from publishing after server switching.

### Process recreation and device reboot

- App process recreation: DataStore restores origin and connection mode; HAM reacquires the token and reconnects automatically.
- Force-stop/clear data: connection metadata is unavailable or intentionally cleared; setup is required again.
- Device reboot: HAM can restore its configuration, but the external SSH application may not have recreated the tunnel. HAM waits in the specific tunnel-unavailable state.
- Hermes restart: HAM handles `401`/`4401` by reacquiring the new bootstrap token.

### Background restrictions

HAM should not add a permanent foreground service solely to keep an idle tunnel connection alive. The external SSH app owns tunnel persistence and may require:

- permission to run in the background;
- exclusion from aggressive battery optimization;
- an ongoing notification, depending on the app and Android version.

HAM must document the same practical consideration for its own active-turn notification/service, without promising uninterrupted execution on vendor-modified Android builds.

## Connection state and errors

Retain the existing top-level `ConnectionState` values, but classify failures internally so UI copy and retry behavior are specific.

Suggested categories:

```text
TunnelUnavailable        local connection refused/timed out/reset
NotHermesEndpoint        status response missing/invalid or wrong service on port
BootstrapUnavailable     Hermes reached, but bootstrap token absent/malformed
CredentialRejected       protected REST 401 or WS 4401 after one rebootstrap
GatedServer              auth_required:true; use advertised OAuth path
CleartextPolicyBlocked   loopback HTTP blocked by merged Android policy
TransportFailure         other transient HTTP/WS failure
ProtocolIncompatible     released contract shape/version unsupported
```

The current catch-all `Could not reach Hermes Serve` must not erase these distinctions.

## Device and tunnel considerations

| Scenario | Required behavior / guidance |
|---|---|
| Termius or SSH app not running | Show `TunnelUnavailable`; retry on foreground/manual/backoff. |
| SSH host key changed | The SSH app must fail closed; HAM reports only that the tunnel is unavailable. Never advise bypassing host-key verification. |
| SSH password/key expires | Resolved in the SSH app; HAM never requests or stores it. |
| Local port already occupied | Wrong/foreign service should fail Hermes status validation; ask user to choose another local port. |
| Multiple Hermes hosts | Use a distinct local port and catalog entry per active forward. Credentials remain origin-scoped. |
| `localhost` resolves to IPv6 | Recommend `127.0.0.1`; support `[::1]` only when the SSH app binds IPv6 loopback. |
| Wi-Fi to mobile transition | SSH app reconnects tunnel; HAM retries and reconciles after listener returns. |
| Tailscale enabled/disabled | Relevant only to how the SSH app reaches the host; HAM still connects to Android loopback. |
| Screen locked / Doze | Tunnel may be suspended; do not replay work. Reconnect and reconcile on foreground or active-turn recovery. |
| Device reboot | User/SSH app may need to restart the forward; HAM configuration remains. |
| Hermes restart | Discard token, reacquire from `/`, reconnect REST/WS. |
| Local malicious app | Android loopback is shared; another app can reach the forwarded port and obtain the same token. Clearly disclose this residual risk. |
| Captive portal/no upstream network | Loopback listener may exist while SSH is disconnected; protected handshake or WS fails and enters tunnel recovery. |
| Debug proxy/VPN | Do not trust injected certificates for HTTP loopback; direct remote HTTPS retains existing TLS validation. |

## Android cleartext policy

The repository requirements say production cleartext should be rejected by default, while the current manifest permits cleartext globally. This feature must resolve that mismatch rather than relying on a global exception.

Target policy:

- direct non-loopback origins require HTTPS in production;
- explicit External SSH tunnel mode permits HTTP only for literal loopback hosts;
- no private/LAN/Tailscale IP receives a cleartext exception;
- release merged-manifest/network-security behavior is verified by tests and on-device checks.

Implementation may use a narrowly scoped Android Network Security Config plus application-level validation. The security property must not depend on the manifest alone: `ServerOrigin` plus `ServerConnectionMode` must reject a non-loopback HTTP origin before any request.

Update `docs/requirements.md`, `README.md`, setup copy, and tests so documentation and the shipped manifest agree.

## Security analysis

### Security boundary

The effective chain is:

```text
SSH app host-key verification
  -> SSH user/key/password authentication
  -> encrypted SSH tunnel
  -> remote loopback Hermes token
  -> HAM REST/WS authorization
```

HAM verifies only the final Hermes protocol and token. It cannot prove which SSH host the external app selected.

### Residual risks

1. **Android loopback is device-wide, not app-private.** Another local app can potentially connect to the forwarded port and read the bootstrap token. The feature assumes a trusted/non-compromised device.
2. **Local-port impersonation.** A malicious process can bind the configured port before the SSH app and imitate enough protocol to deceive HAM. Validating Hermes response shape and optionally remembering `install_id` can detect accidents, but `install_id` is not cryptographic authentication and must not be presented as such.
3. **Credential in WebSocket URL.** The upstream legacy contract requires `?token=`. URLs and exceptions containing it must never be logged, persisted, included in crash reports, or displayed.
4. **HTML bootstrap parsing.** Parse a bounded exact assignment without executing HTML/JavaScript. Redirects are forbidden.
5. **Token lifetime.** Treat tokens as ephemeral and invalidate on origin change, credential rejection, app shutdown, or generation replacement.
6. **No implicit transport takeover.** Reconnecting must not close a shared runtime or resume/control another client’s runtime without existing explicit user actions.

### Logging and diagnostics

Permitted diagnostics:

- normalized origin with local port optionally redacted in user-visible text;
- phase (`status`, `bootstrap`, `protected-read`, `websocket`);
- HTTP status or WS close code;
- exception class;
- retry count and connection generation.

Forbidden diagnostics:

- session token, OAuth access/refresh token, WS ticket;
- complete credential-bearing URLs;
- root HTML containing the token;
- SSH host credentials or connection strings;
- prompts, transcripts, attachments, approvals, secret/sudo input.

## Proposed code changes

### Connection metadata and validation

- Modify `connection/ServerCatalog.kt`
  - add `ServerConnectionMode` to `ServerCatalogEntry`;
  - validate tunnel mode against a loopback origin.
- Modify `connection/ServerSettingsRepository.kt`
  - persist/migrate `connection_mode` with `Direct` default.
- Modify `connection/ServerOrigin.kt`
  - add explicit loopback-host classification;
  - keep origin free of credentials, paths, queries, and tokens.
- Modify `ui/HermesApp.kt`
  - add connection-type UI, tunnel instructions, test action, and specific recovery copy.

### Credential and bootstrap layer

- Create `connection/HermesCredential.kt`
  - sealed credential types;
  - redacted representation;
  - REST application rules.
- Create `connection/LoopbackSessionBootstrapClient.kt`
  - bounded status/shell fetch;
  - exact token parser;
  - protected-read verification;
  - typed failure classification.
- Do not reuse `NativeTokenStore` for loopback tokens.

### REST client

- Modify `connection/HermesConnectionClient.kt`
  - separate public status discovery from protected metadata load;
  - replace ambiguous `String? accessToken` parameters with `HermesCredential?`;
  - consistently apply Bearer or `X-Hermes-Session-Token` to every protected route;
  - preserve explicitly public probes where appropriate;
  - classify credential rejection separately from reachability failures.

This is intentionally a compile-guided broad signature change. A global Ktor default-header interceptor is not recommended because one client serves multiple origins/generations and stale credentials must never leak across them.

### WebSocket transport

- Modify `gateway/HermesChatGateway.kt`
  - make WS authentication strategy credential-aware;
  - OAuth path keeps fresh ticket minting;
  - loopback path builds `/api/ws?token=` without calling the ticket endpoint;
  - expose WS close code `4401` as credential rejection.
- Modify `voice/StreamingSpeechTransport.kt`
  - apply the same credential-aware ticket/token strategy.
- Ensure URL builders and exceptions never stringify credential-bearing URLs.

### Connection orchestration

- Modify `connection/HermesConnectionViewModel.kt`
  - choose authorization flow from `ServerConnectionMode` plus status discovery;
  - maintain one in-memory credential per active origin generation;
  - coordinate bootstrap with a mutex/shared deferred;
  - automatically rebootstrap once on REST `401` or WS `4401`;
  - use specific tunnel/bootstrap/auth errors;
  - preserve existing foreground reconnect, controller-generation, reconciliation, and no-close-on-disconnect rules.

### Documentation

- Update `README.md` self-hosting and Tailscale/SSH guidance.
- Update `docs/requirements.md` for explicit loopback cleartext exception and the new authorization mode.
- Update `docs/testing.md` with the tunnel/auth recovery matrix.
- Add user-facing setup documentation with Termius as an example and vendor-neutral field definitions.

## Testing strategy

Follow the repository’s fake-first local testing approach and required gate.

### Unit tests

Create focused tests for:

- loopback origin classification (`127.0.0.1`, `[::1]`, `localhost`, and rejected lookalikes);
- connection-mode persistence and migration to `Direct`;
- rejection of External SSH tunnel mode with non-loopback HTTP(S) origins;
- bounded bootstrap HTML parsing, JSON string decoding, missing/duplicate/malformed/oversized token handling;
- credential redaction and maximum sizes;
- REST header selection with no cross-origin leakage;
- OAuth ticket URL versus loopback token URL;
- URL encoding without token disclosure in errors;
- `401` and `4401` credential-rotation classification;
- one rebootstrap shared across concurrent callers;
- stale generation results discarded after switching servers.

Likely files:

- `connection/ServerOriginTest.kt`
- `connection/ServerCatalogRepositoryTest.kt`
- `connection/HermesConnectionClientTest.kt`
- new `connection/LoopbackSessionBootstrapClientTest.kt`
- `connection/HermesConnectionViewModelTest.kt`
- `gateway/HermesChatGatewayTest.kt`
- speech transport tests

### Mock integration flows

Use Ktor `MockEngine` and fake sockets to verify exact request order:

1. Status → shell → protected sessions → WS token success.
2. Status succeeds but shell missing token.
3. Wrong service occupies local port.
4. REST returns `401` → one bootstrap → retry succeeds.
5. WS closes `4401` → one bootstrap → socket/reconciliation succeeds.
6. Second rejection stops without spinning.
7. Tunnel disappears, returns, and reconnects without prompt replay.
8. App foreground triggers immediate recovery; background idle state does not poll.
9. Origin changes while bootstrap is in flight; old token is never published.
10. OAuth connection remains ticket-based and unchanged.

### UI tests

Cover compact, medium, and expanded widths plus large text:

- External SSH tunnel setup instructions and URL validation;
- Test tunnel success;
- actionable `TunnelUnavailable` screen;
- wrong-service/bootstrap-incompatible errors;
- connection-mode switching without leaking old state;
- state restoration of selected server/mode after process recreation.

### Manifest/security tests

- release build rejects non-loopback HTTP origins;
- explicit loopback HTTP works in External SSH tunnel mode;
- merged release manifest does not permit global cleartext;
- no new exported Android components;
- static/logging regression checks ensure credentials are redacted.

### Manual device/E2E matrix

On Android 10+ and a physical target device:

1. Configure Termius local forwarding and connect HAM.
2. Repeat with another SSH client when available.
3. Send a prompt and verify REST + `/api/ws?token=` behavior.
4. Background/foreground HAM with the tunnel alive.
5. Kill/restart HAM; verify automatic token reacquisition.
6. Restart Hermes; verify `401/4401` rotation recovery.
7. Stop/restart Termius; verify specific waiting state and automatic recovery.
8. Switch Wi-Fi/mobile/Tailscale reachability while Termius reconnects.
9. Lock/unlock the device and test vendor battery optimization.
10. Reboot the device; verify HAM restores configuration and waits for the external tunnel.
11. Bind the wrong service to the local port and verify fail-closed endpoint validation.
12. Configure two servers on distinct local ports and verify origin isolation.

Run the required project gate after implementation:

```bash
git diff --check && \
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest
```

## Rollout

1. Ship behind the explicit External SSH tunnel connection mode; no automatic migration.
2. Mark the feature experimental in the first release because it depends on a legacy upstream bootstrap contract and third-party tunnel lifecycle.
3. Preserve OAuth as the preferred supported path for publicly reachable/gated servers.
4. Capture only non-sensitive failure categories if diagnostics are later introduced; no telemetry is required for the initial implementation.
5. Reassess when upstream Hermes exposes a first-class local-client token adoption endpoint or a platform-neutral SSH connection API.

## Acceptance criteria

The feature is complete when all of the following are true:

- A user can configure a Termius/other-app local forward and connect HAM to `http://127.0.0.1:<port>` without entering a Hermes token.
- HAM automatically adopts the current loopback session token and authorizes protected REST routes.
- Chat and speech WebSockets use the legacy `token` credential; OAuth connections still use single-use tickets.
- The session token is never persisted, logged, displayed, backed up, or included in an exception.
- App process recreation reconnects without user reauthentication when the external tunnel is available.
- Hermes token rotation triggers one bounded automatic rebootstrap and reconciliation.
- A stopped tunnel produces a specific actionable state and reconnects after the external SSH app restores it.
- Non-loopback cleartext remains rejected in production.
- Multiple origins/ports remain isolated, and stale generations cannot publish credentials or state.
- Existing observer/controller and shared-runtime safety rules remain unchanged.
- The full repository test, lint, build, and screenshot gates pass.

## Open questions

1. Should first release support literal `localhost`, or only `127.0.0.1` and `[::1]` to avoid resolver ambiguity?
2. Should the UI store and compare Hermes `install_id` as a non-cryptographic wrong-endpoint warning, or omit it to avoid implying stronger identity guarantees?
3. Should active-turn recovery retry indefinitely at the capped interval while the foreground notification is present, or stop after a bounded elapsed time and require manual retry?
4. Does the project want a separate `External tunnel` mode, as proposed, or a more generic future-proof `Local relay` name? The security copy must still explicitly describe SSH and loopback for this implementation.
