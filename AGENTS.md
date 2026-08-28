# Repository Instructions

## Product boundary

This is a native Android client for the official interfaces of an unchanged shared `hermes serve` process. Direct mode must never add, require, or assume custom server routes, forks, dashboard extensions, or gateway workers, and must keep working with no Mercury plugin installed.

One scoped exception exists: **Mercury Relay** is an optional, separately paired transport (iOS first) that carries the same official Hermes JSON-RPC session contract end-to-end encrypted through the Mercury Relay host plugin and an opaque hosted router. Relay code must stay isolated from direct-mode connection, credential, and catalog state; it never changes Hermes itself, tunnels no private Hermes route, and is never a requirement for any direct-mode feature.

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
