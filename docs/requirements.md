# Requirements

## Runtime and build

| Requirement | Selected baseline |
|---|---|
| JDK | 17 |
| Android Gradle Plugin | 9.3.1 (required for current AndroidX artifacts compiled against API 37) |
| Gradle wrapper | 9.5.0 with pinned SHA-256 (AGP 9.3.1 supported baseline) |
| Kotlin / Compose compiler plugin | 2.4.10 |
| AndroidX Core | 1.19.0 |
| Lifecycle / ViewModel | 2.11.0 |
| Compile / target SDK | 37 / 36 (current AndroidX requires API 37 to compile; stable API 36 runtime behavior remains the target) |
| Minimum SDK | 29 (working baseline; review before publication) |
| Compose BOM | 2026.06.01 |
| Navigation 3 | 1.1.5 |
| Material 3 Adaptive Navigation 3 | 1.3.0-rc01 (no stable `adaptive-navigation3` release exists; required by the August 2026 official adaptive skill) |
| Coroutines | 1.11.0 |
| Kotlin serialization JSON | 1.11.0 |
| Ktor client / OkHttp engine / WebSockets | 3.5.2 |
| DataStore Preferences | 1.2.1 |
| Tink Android | 1.23.0 |
| Turbine | 1.2.1 |

The version catalog is authoritative for resolved library versions. `local.properties` is machine-local and untracked.

## Product

- Connect to official REST and ticketed JSON-RPC/WebSocket endpoints exposed by an unchanged `hermes serve` process.
- Let users configure and edit a canonical HTTPS server origin without embedding credentials, endpoint paths, queries, fragments, or WebSocket tickets.
- Persist only the normalized origin as connection metadata; derive REST and WebSocket endpoint paths in the client.
- Keep authentication, storage, cached data, and TLS/trust choices isolated per normalized server origin.
- Browse durable stored sessions separately from transient process-local live runtimes.
- Released-server default is observer mode. Any action that transfers a live session transport requires explicit confirmation.
- Never send `session.close` as generic connection or lifecycle cleanup.
- Controller-only operations include prompt submission, approvals, clarification, secret/sudo/terminal input, steering, and interruption.
- Reconnect through status, active-session, inflight, and durable-transcript reconciliation rather than blind replay.

## Foldable and adaptive UI

Primary physical target: standard/non-Ultra Samsung Galaxy Z Fold 8.

- No device-name, orientation-only, or fixed-resolution branching.
- Compact windows use one pane; suitable wider windows use list/detail scenes automatically.
- Preserve selected session, navigation stack, composer draft, scroll position, inflight state, and origin across fold/unfold, resize, rotation, multi-window, and process recreation.
- Support cover screen, unfolded portrait/landscape, split screen, freeform windows, DeX, keyboard, mouse/trackpad, touch, and predictive back.
- Respect separating hinges and display features provided by adaptive APIs.
- Use edge-to-edge drawing with correct system bar, cutout, and IME insets.

## Local security

- No plaintext credentials in files, preferences, logs, backups, or crash reports.
- Native access/refresh tokens persist only in Android Keystore-backed encrypted storage scoped to the normalized HTTPS origin; refresh rotation replaces the encrypted record atomically.
- Never persist authorization codes, PKCE verifiers, WebSocket tickets, or credential-bearing URLs.
- Disable backup for secret-bearing state unless a reviewed encrypted backup design is introduced.
- Export only the launcher activity.
- Production network security rejects cleartext by default. Direct non-loopback origins require HTTPS. Explicit External SSH tunnel mode permits HTTP only for `127.0.0.1` and `[::1]`. There is no private, LAN, or Tailscale cleartext exception in debug or release.
- Debug builds share the same network security config as release. Local HTTP loopback still works in tunnel mode; a non-loopback LAN Hermes must use HTTPS.
- Debug-only development trust exceptions, if needed, must remain in debug resources and never contain a private server address.
