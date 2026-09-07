# Security policy

## Supported versions

Security fixes are applied to the latest development version of Mercury. The first public Play release has not yet been published.

## Reporting a vulnerability

Please **do not** open a public issue for a suspected vulnerability, credential exposure, authentication flaw, or sensitive privacy report.

After this repository is public, use GitHub’s private vulnerability-reporting flow once it has been enabled. Until then, contact the repository owner privately through their [GitHub profile](https://github.com/unsupportedpastels). Include:

- a concise description of the impact;
- reproducible steps or a minimal proof of concept;
- the affected Mercury version or commit; and
- any mitigation you identified.

Do not include real passwords, access tokens, refresh tokens, cookies, WebSocket tickets, private server addresses, prompts, transcripts, or attachments in the report.

## Local offline cache

Mercury caches a bounded set of session metadata in app-private storage so the session list can paint while Hermes Serve is unreachable. Cached metadata is never treated as authoritative and is marked cached/offline until the server replaces it.

Transcript tails are **disabled by default**. A user may explicitly opt in from Settings. When enabled, transcript tails are encrypted with an Android Keystore-backed Tink key on Android, or with a random AES-256-GCM key stored as a device-only Keychain item on iOS, and are bounded to 200 messages per session, 128 KiB per message body, 100 sessions, 4 MiB total, and 30 days. The cache authority boundary is normalized server origin + profile + durable session ID.

The cache never stores access or refresh tokens, WebSocket tickets, transient runtime IDs, secret or sudo input, attachments, or connection strings. Corrupt or tampered cache rows are ignored and removed without exposing their contents. Transcript tails associated with an origin are cleared on logout or origin removal; explicit cache clearing removes all rows, and deleting a server session removes its local row. Android Auto Backup remains disabled.

Managed artifact downloads remain bounded by the client transport. Explicit sharing writes only the selected artifact to app-private cache and grants read access through a non-exported `FileProvider` content URI. Mercury does not execute or render HTML/SVG artifacts in a WebView.

## Credential storage

- **Android:** access/refresh tokens, cookies, and Relay pairings are encrypted with Tink AEAD keys held in the Android Keystore, each store under its own master key, scoped to the normalized server origin. Auto Backup is disabled.
- **iOS:** access/refresh tokens, the server catalog and Relay pairings live in the Keychain as `kSecClassGenericPassword` items marked `ThisDeviceOnly` (never synced to iCloud): direct-mode tokens under one service keyed by origin, the server catalog under another, and Relay pairings under a third (`relay-targets`), so removing or renaming one side can never touch the other's keys. Basic-auth session cookies are different: they are held by the system `HTTPCookieStorage`, scoped by cookie domain rather than by normalized origin, and are not Keychain-protected. Sign out clears them. No `keychain-access-groups` entitlement is declared, so app extensions cannot read the Keychain. The **Add to Mercury** share extension exchanges only staged attachment files with the app through the App Group container and holds no credentials.

## Mercury Relay

Relay is an optional transport, never a requirement for direct mode, and it never changes Hermes itself. Pairing starts from a one-time QR code produced by the Mercury Relay host plugin; the host operator approves the device by comparing a short fingerprint on both screens before it is admitted. Chat then runs end-to-end encrypted with Noise XK between the phone and the host; the hosted router sees only opaque routing metadata plus ciphertext and cannot read session content. Relay framing, Noise state, and target validation are implemented once in `shared/mercury-core` and validated on both platforms against the public interop vectors from <https://github.com/unsupportedpastels/mercury-relay-plugin> (`tools/check-relay-vectors.sh` fails CI if the vendored vectors drift from the pinned release). Relay pairings, keys, and connection state are isolated from direct-mode credentials and the server catalog on both platforms. Reports about the host plugin or router belong to that repository; reports about the client side of the channel belong here.

## Voice

Voice features use only released Hermes routes (`/api/audio/transcribe`, `/api/audio/speak`, `/api/audio/speak-stream`, `/api/audio/elevenlabs/voices`) over the same authenticated origin-scoped transport; the streaming speech WebSocket authenticates with a fresh single-use ticket per connection, never a bearer token in a URL. Servers without the audio routes simply hide voice controls. Microphone recordings and synthesized audio are held in memory or short-lived app-cache temporary files, deleted on completion, and are excluded from the offline cache, logs, and error messages (errors are bounded category messages without audio data URLs, prompt text, config bodies, or tickets). The opt-in screen-off voice service is non-exported, uses `foregroundServiceType="microphone"`, always shows a stoppable notification without transcript content, and never starts or restarts from the background.

## Scope

Mercury is a native client for released Hermes interfaces. Reports affecting Hermes Agent itself, a server deployment, or another upstream dependency may need coordinated disclosure with that project or vendor. We will acknowledge valid reports, assess the client impact, and coordinate a fix where appropriate.
