# Repository Instructions

## Product boundary

This is a native Android client for the official interfaces of an unchanged shared `hermes serve` process. Do not add, require, or assume custom server routes, plugins, forks, dashboard extensions, or gateway workers.

Released Hermes compatibility is conservative: observe durable/live metadata without implicit transport takeover. Resume or activate another remote connected client's runtime only after explicit user action. Never close a shared runtime merely because this client disconnects. Capability-gate multi-subscriber streaming until a safe released transport advertises it.

## Android architecture

- Kotlin, single-activity Jetpack Compose, Material 3.
- Navigation 3 with serializable `NavKey`s and saveable back stacks.
- Adaptive list/detail uses `ListDetailSceneStrategy` from `adaptive-navigation3`; do not use legacy `ListDetailPaneScaffold` or `NavigableListDetailPaneScaffold`.
- Make decisions from current window metrics/posture, never device model names or orientation alone.
- Edge-to-edge is mandatory. Apply insets at individual screen/list/composer boundaries and avoid double IME/system-bar padding.
- Keep durable stored-session IDs separate from transient live runtime-session IDs.
- Keep observer and controller roles explicit.

## Security

- Scope credentials, cookies, trust decisions, cached transcripts, and connection settings by normalized server origin.
- Treat WebSocket tickets as fresh, single-use, in-memory values.
- Never log credentials, tokens, cookies, tickets, prompts, transcripts, attachments, secrets, sudo/terminal input, or connection strings.
- Export only the launcher activity. Add no exported component without an explicit threat model and tests.
- Do not commit `local.properties`, signing material, credentials, server URLs, or generated build output.

## Development workflow

Use proportionate RED -> GREEN -> REFACTOR. Reproducible bug fixes and testable behavior changes should start with a focused regression test that fails for the expected reason, especially for reducers, protocol parsing, reconciliation, authentication/security, lifecycle/concurrency, and policy decisions. Keep those decisions platform-independent where practical and test them locally.

Do not manufacture failing tests for documentation, `.gitignore`, configuration, generated code, dependency-only changes, exploratory spikes, purely visual work, or behavior that can only be meaningfully exercised on a device. Use the most relevant evidence instead: existing tests before and after a refactor, builds and validators for configuration, screenshot and interaction checks for UI, or device/integration verification for platform behavior. State which verification strategy was used and do not claim strict TDD when RED was not observed.

Required gates for changed Android code:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For adaptive UI changes, also run the configured screenshot/UI test gates and test compact, medium, and expanded windows. Do not update screenshot references without visual review.

## Official project-local skills

The current Google-authored skills live under `.agents/skills/`. Consult matching skills before changes, especially `adaptive`, `navigation-3`, `edge-to-edge`, `testing-setup`, `android-intent-security`, and `android-cli`.

## Learned User Preferences

- Build and sideload locally compiled APKs only; do not keep, install, or trust pre-signed or prebuilt APKs.
- Do not install onto an ADB device until the user confirms it is their phone.
- Prefer real-device checks over reviewing screenshot goldens when both would work. When asking the user to test on hardware, give in-app steps and keep ADB connected for logs and screenshots.

## Learned Workspace Facts

- `docs/EXTERNAL_SSH_TUNNEL_QUESTIONS_ANSWERS.md` is the authoritative spec for external SSH tunnel session-auth. User setup is `docs/external-ssh-tunnel-setup.md`. Design is `docs/design/external-ssh-tunnel-session-auth.md`. Do not use cancelled continuation briefs.
- Only HTTP 401 on a credential-bearing protected REST request is credential rejection. Handshake HTTP 403 must never invalidate the token or be mapped to close code 4401.
- Tunnel-mode loopback hosts are only `127.0.0.1` and `[::1]`; reject `localhost` because of IPv4/IPv6 ambiguity. Direct mode is unchanged.
- Tunnel and bootstrap reconnect belong in one platform-independent lifecycle reducer; do not add a parallel recovery machine.
- Do not enable global cleartext HTTP traffic.
- The application id is `com.unsupportedpastels.hermesandroid`.
