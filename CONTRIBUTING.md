# Contributing to Mercury

Thanks for helping improve Mercury — a Hermes companion.

## Product boundary

Mercury is a native Android and iOS client for the official interfaces of an unchanged shared `hermes serve` process. Direct mode must never add, require, or assume custom server routes, forks, dashboard extensions, or gateway workers, and must keep working with no Mercury plugin installed.

One scoped exception exists: **Mercury Relay** is an optional, separately paired transport (iOS first) that carries the same official Hermes JSON-RPC session contract end-to-end encrypted through the Mercury Relay host plugin and an opaque hosted router. Relay code must stay isolated from direct-mode connection, credential, and catalog state; it never changes Hermes itself, tunnels no private Hermes route, and is never a requirement for any direct-mode feature.

Both apps sit on one shared Kotlin Multiplatform core (`shared/mercury-core`). Deterministic client decisions (protocol decoding, transcript reduction, attachment and origin policy, Relay framing) belong there, once; UI, navigation, credential storage, notifications, and process lifecycle stay native in Compose and SwiftUI. A behavior change in one app's native layer lands in the other app in the same PR, or the PR says which platform is deferred and why. See [AGENTS.md](AGENTS.md) for the full rule.

The app must remain an observer by default: it may not resume, take control of, or close another remote client’s runtime without explicit user action.

## Before you start

1. Search existing issues and discussions before opening a duplicate.
2. For a significant change, open an issue describing the user problem and the released Hermes contract it relies on.
3. Keep a change focused. Avoid unrelated reformatting or generated build artifacts.
4. Never commit `local.properties`, signing material, credentials, server URLs, captured prompts/transcripts, or build output.

## Local development

See [docs/setup.md](docs/setup.md) for host setup on Linux (Android) and macOS (Android and iOS).

**Android.** Install JDK 17 and the required Android SDK, add your SDK path to an untracked `local.properties`, then run at least:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Add `validateDebugScreenshotTest` when UI or screenshot references change.

**Shared core.** For changes touching `shared/mercury-core` (the Kotlin Multiplatform contract core), also run:

```bash
./gradlew :shared:mercury-core:check
```

Its common tests run on Linux via the Android host-test target; the Apple framework builds only on macOS, so a green Linux build does not prove the generated framework is usable from Swift.

**iOS.** On a Mac with Xcode, XcodeGen (`brew install xcodegen`), and a JDK on `PATH` (`brew install openjdk@17` — Xcode's pre-build phase invokes Gradle to produce the `MercuryCore` framework), run:

```bash
cd ios && xcodegen generate && xcodebuild -project Mercury.xcodeproj -scheme Mercury \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build test
```

This is required for any change under `ios/` or `shared/`; CI runs it on macOS as well.

Use proportionate RED → GREEN → REFACTOR for reproducible bugs and testable behavior. Documentation, build configuration, and purely visual changes should use the most relevant validation instead—for example, a build/resource validation or inspected screenshots.

## Pull requests

- Explain the problem, approach, and verification in the pull-request template.
- Add focused tests for behavior, protocol parsing, state reconciliation, lifecycle, or security changes. A shared-core change ships with a common test; a platform change ships with the matching test on each platform it touches.
- For adaptive UI changes, verify compact, medium, and expanded windows. Do not update screenshot references without visual review.
- Keep all authentication data, WebSocket tickets, prompts, transcripts, attachments, and connection strings out of source, tests, logs, and screenshots.

## Code of conduct

Be constructive and respectful. Harassment, discrimination, and sharing another person’s private data are not acceptable.
