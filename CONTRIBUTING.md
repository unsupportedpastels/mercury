# Contributing to Mercury

Thanks for helping improve Mercury — a Hermes companion.

## Product boundary

Mercury's Android app is a native client for the released, official interfaces of an unchanged shared `hermes serve` process. Contributions must not require a custom server route, dashboard extension, plugin, gateway worker, or Hermes fork.

The app must remain an observer by default: it may not resume, take control of, or close another remote client’s runtime without explicit user action.

## Before you start

1. Search existing issues and discussions before opening a duplicate.
2. For a significant change, open an issue describing the user problem and the released Hermes contract it relies on.
3. Keep a change focused. Avoid unrelated reformatting or generated build artifacts.
4. Never commit `local.properties`, signing material, credentials, server URLs, captured prompts/transcripts, or build output.

## Local development

Install JDK 17 and the required Android SDK, add your SDK path to an untracked `local.properties`, then run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest :shared:mercury-core:check
```

For Android-code changes, run at least:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For changes touching `shared/mercury-core` (the Kotlin Multiplatform contract
core), also run:

```bash
./gradlew :shared:mercury-core:check
```

Its common tests run on Linux; the Apple framework builds only on macOS, where
building the iOS app additionally requires a JDK (Xcode invokes Gradle to
produce the `MercuryCore` framework — without a JDK the spike compiles out and
the app builds as before).

Use proportionate RED → GREEN → REFACTOR for reproducible bugs and testable behavior. Documentation, build configuration, and purely visual changes should use the most relevant validation instead—for example, a build/resource validation or inspected screenshots.

## Pull requests

- Explain the problem, approach, and verification in the pull-request template.
- Add focused tests for behavior, protocol parsing, state reconciliation, lifecycle, or security changes.
- For adaptive UI changes, verify compact, medium, and expanded windows. Do not update screenshot references without visual review.
- Keep all authentication data, WebSocket tickets, prompts, transcripts, attachments, and connection strings out of source, tests, logs, and screenshots.

## Code of conduct

Be constructive and respectful. Harassment, discrimination, and sharing another person’s private data are not acceptable.
