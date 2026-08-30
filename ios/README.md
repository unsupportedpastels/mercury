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
credentials (`Mercury/MercuryKit/Relay/`). The protocol contract and canonical
interop vectors live in the private `mercury-relay` repository; the vectors are
vendored under `MercuryTests/Fixtures/RelayProtocol/`.

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
