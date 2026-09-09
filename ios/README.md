# Mercury for iOS

Mercury is an unofficial, 100% free and open-source iOS companion for
[Hermes Agent](https://hermes-agent.nousresearch.com). It connects to **your own**
Hermes backend — a self-hosted `hermes serve` instance or Hermes Cloud — using the
**official Hermes endpoints only** in direct mode. No server-side changes, no
custom routes, and no Mercury plugin are required for direct mode.

**Mercury Relay** is an optional third connection mode: if you install the
Mercury Relay plugin on your Hermes host, the app pairs by scanning a QR code,
the host operator approves the device by comparing a short fingerprint on both
screens, and chat then runs end-to-end encrypted (Noise XK) through an opaque
hosted router that only ever sees ciphertext. Relay carries the same official
Hermes JSON-RPC session contract — Hermes itself is unchanged — and relay
pairings, keys, and state live fully apart from direct-mode servers and
credentials. Relay protocol, framing, Noise state, and target validation are
implemented once in `shared/mercury-core`; iOS keeps Keychain, URLSession, and
SwiftUI integration native. The host plugin, protocol contract, and canonical
interop vectors are open source at
<https://github.com/unsupportedpastels/mercury-relay-plugin>; the vectors are
vendored once under `shared/mercury-core/src/commonTest/resources/relay-protocol/` and
`tools/check-relay-vectors.sh` fails CI if they drift from the pinned release.

Product boundary and protocol contracts are shared with the Android client and
documented in [`../AGENTS.md`](../AGENTS.md) plus the repo's project-local skills.

## Regenerating the Xcode project

The project is defined by [`project.yml`](project.yml) and generated with
[XcodeGen](https://github.com/yonaskolb/XcodeGen). The `.xcodeproj` is **never**
committed:

```bash
brew install xcodegen
cd ios
xcodegen
```

## Building & testing

Building needs a JDK on `PATH` (or `JAVA_HOME` set): the `Mercury` target's
pre-build phase runs `./gradlew :shared:mercury-core:link…Framework…` to
produce the static `MercuryCore` framework. `brew install openjdk@17` and
follow Homebrew's caveat to put it on your `PATH`.

```bash
xcodebuild -project Mercury.xcodeproj -scheme Mercury \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build test
```

This is the required iOS gate from [`../AGENTS.md`](../AGENTS.md). It also runs
in CI on macOS (`.github/workflows/ios-ci.yml`) for any change under `ios/`,
`shared/`, or the Gradle build files; the workflow falls back to the newest
available iPhone simulator when the runner image lacks an iPhone 17 Pro.

Version numbers come from `project.yml` (`MARKETING_VERSION`,
`CURRENT_PROJECT_VERSION` under `settings.base`) and track the Android
`versionName`; see [`../docs/release-readiness.md`](../docs/release-readiness.md).

## Notes

- Active chat work has one slim activity line inside the composer, with a ticking
  right-aligned timer when the turn start is known. Tap it for current reasoning,
  tools, reported milestones, background tasks, and process observations. Completed
  turns fold their intermediate activity beneath the final answer; the model picker
  and context ring stay visible. Saved activity remains reachable through the context
  ring → Session activity, including while disconnected. Read-only progress updates
  read a bounded transcript page without resuming, interrupting, or submitting work.
  Activity priorities, quiet-label hold, turn folding, and progress reconciliation
  use the same shared core as Android.

- Startup restores the last successfully used direct server or relay. With no
  saved choice, one usable configured connection starts automatically; multiple
  configured connections show a picker. A failed or unavailable saved choice
  never silently switches to another host. Settings → Servers lists both kinds.
- New Project browses existing host folders and creates new folders within the
  host's managed-files permissions. Direct mode uses the official Hermes file
  API; Relay uses the encrypted, versioned `folders` capability advertised by
  the Mercury Relay host plugin. Older hosts receive upgrade guidance and can
  still register an existing folder by its absolute path. No direct credentials
  are used as a Relay fallback, and registering a project does not create a folder.
- These native startup/toolbar changes are iOS-scoped. Android's corresponding
  native UI changes are deferred rather than changing its navigation as part of
  the reported iOS fixes. Shared profile-name policy is consumed by both clients.
- Native UI, networking, and secure storage use Apple frameworks; the shared
  KMP core uses `cryptography-kotlin`'s CryptoKit provider for Relay primitives.
- Minimum deployment target: iOS 17.0.
- Adaptive light/dark theme: every color is defined once in `Theme.swift` as
  a light/dark pair (`Color.token(light:dark:)`) that resolves from the
  system appearance via `UIColor` dynamic providers. The roles mirror the
  Android Material 3 scheme (`background`/`surfaceContainer*`, teal primary,
  gold "active", green "healthy", Material error) so both apps share one
  palette; the dark canvas is pure `#000000` with neutral gray surface tiers
  for OLED screens. No hardcoded hex outside `Theme.swift`.
- Credentials are stored in the Keychain scoped to the normalized server origin;
  token material is never logged.
