# Mercury for iOS

Mercury is an unofficial, 100% free and open-source iOS companion for
[Hermes Agent](https://hermes-agent.nousresearch.com). It connects to **your own**
Hermes backend — a self-hosted `hermes serve` instance or Hermes Cloud — using the
**official Hermes endpoints only**. No server-side changes, no custom routes.

Product boundary and protocol contracts are shared with the Android client and
documented in [`../AGENTS.md`](../AGENTS.md) plus the repo's project-local skills.
The upcoming MercuryKit-style protocol layer ports those Android contracts
(origin normalization in `Mercury/ServerOrigin.swift`, origin-scoped Keychain
credentials in `Mercury/CredentialStore.swift`) to Swift.

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

```bash
xcodebuild -project Mercury.xcodeproj -scheme Mercury \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build test
```

## Notes

- Zero third-party dependencies — Apple frameworks only (SwiftUI, Combine,
  Security, XCTest).
- Minimum deployment target: iOS 17.0.
- AMOLED-first dark theme: pure `#000000` background (`Theme.swift`), raised
  surfaces as subtle gray steps. All colors live in `Theme.swift`.
- Credentials are stored in the Keychain scoped to the normalized server origin;
  token material is never logged.
