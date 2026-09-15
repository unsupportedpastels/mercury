# Privacy policy

**Effective date:** August 15, 2026

Mercury — a Hermes companion — is a native Android and iOS client for a Hermes Agent server selected and controlled by the user.

## Data Mercury handles

To operate, Mercury may handle:

- the server address you choose;
- authentication cookies or tokens issued by that server;
- project, session, prompt, response, tool-status, and transcript data returned by that server; and
- files or images you explicitly attach for upload to that server.

Mercury stores connection and session metadata locally on your device, scoped to the normalized server origin, selected profile, and durable session ID. This metadata cache is bounded, expires after 30 days, and may be shown with a cached/offline marker until Hermes Serve reconciles it. Authentication material is stored using Android Keystore-backed encrypted storage on Android and in the iOS Keychain on iOS, as device-only items keyed by server origin that are never synced to iCloud. Fresh WebSocket tickets remain in memory only.

Transcript tails are not stored unless you opt in under Settings. Opted-in transcript tails are encrypted with an Android Keystore-backed key (Android) or a random AES-256 key held in the device Keychain (iOS) and bounded to 200 messages per session, 128 KiB per message body, 100 sessions, and 4 MiB total. Tails associated with an origin are cleared on logout or when that origin is removed; all tails are cleared by the explicit cache control and application data removal. Mercury never caches access or refresh tokens, tickets, transient runtime IDs, secret input, attachments, or connection strings. Android Auto Backup is disabled.

Host-file contents are downloaded only after an explicit preview, play, save, or share action. Save uses Android's user-selected document destination. Share creates a bounded temporary file in app-private cache and exposes only that file through a one-time Android content-URI grant; Android may later evict the cache file.

On iOS, content you send to Mercury from another app through the **Add to Mercury** share extension is staged in the app's private App Group container (`group.com.unsupportedpastels.mercury`) until you review it in the composer; the extension itself holds no server credentials and sends nothing. Relay pairings on iOS (the relay origin, the host's public key, and this device's pairing key) are kept in their own Keychain service, separate from server credentials, and are deleted when you remove the pairing.

## Voice

- **Device dictation.** Composer dictation uses Apple's Speech framework on iOS and the configured Android speech-recognition service on Android. Recognition is not guaranteed to stay on-device; audio may be processed by the platform speech provider under its policies. Mercury receives recognized text in the composer. Audio capture starts only after you choose dictation and grant the required permissions.
- **Server-backed voice.** When you choose a server-backed voice feature, Mercury sends recorded audio to your configured Hermes server for transcription (`/api/audio/transcribe`). That server may forward the audio to its configured speech-to-text provider. Recording stops according to the selected feature's stop control, silence/server limits, and lifecycle policy.
- **Spoken replies.** Text you ask Mercury to read aloud (or that auto-speak reads, when the server's `voice.auto_tts` is enabled) is sent to your configured Hermes server for synthesis (`/api/audio/speak` and `/api/audio/speak-stream`), which may forward it to its configured text-to-speech provider.
- **No voice persistence.** Microphone recordings, transcripts in flight, and generated speech audio live in memory or short-lived app-cache temporary files that are deleted when playback or transcription finishes. They are never written to the offline transcript cache, saved state, logs, notifications, or any analytics (Mercury has none).
- **Lifecycle default.** Voice capture and playback stop when you switch sessions or profiles, log out, lock the device, or leave the app.
- **Screen-off continuation (opt-in).** If you enable "Continue voice with screen off" in Settings, an already-started voice conversation keeps running behind a non-dismissable notification with a Stop action, using Android's microphone foreground service; Android's microphone indicator stays visible, and a partial wake lock keeps capture and network alive. The notification shows only the loop phase (Listening/Thinking/Speaking), never transcript text. After the process is killed, voice never restarts on its own.
- **Server-side processing** of voice audio and speech text — including provider choice, logging, and retention — is controlled by your server's operator and configuration.

## Where data goes

Mercury sends conversation data to the Hermes server origin you configure in the app or, when you explicitly enable Relay, through the end-to-end encrypted route described below. Optional Relay notifications additionally use Apple's Push Notification service (APNs) and Mercury's hosted notification router as described under iOS notifications. Mercury does not include analytics, advertising, tracking, or telemetry SDKs.

If you explicitly pair the optional **Mercury Relay** mode on Android or iOS, traffic to your host additionally passes through a hosted relay router. For session transport, that router sees opaque routing metadata (a random installation route and connection role) plus end-to-end encrypted ciphertext; it cannot read prompts, transcripts, credentials, or session content. Optional push notifications require additional delivery records described below, not stored conversation plaintext. Relay mode is never required and its device-only encrypted pairings can be removed at any time.

When you explicitly choose device dictation, the platform speech-recognition service may capture and process audio under its own privacy policy. Mercury does not persist the recording.

Your chosen server’s operator and configuration determine how server-side data is processed, retained, logged, and secured. Review that server’s policies before connecting, especially if it is operated by someone else.

## Android permissions

- **Internet** — connect to your configured Hermes server.
- **Notifications** — optional alerts for completed turns and requests requiring your attention, plus the persistent voice-conversation status notification.
- **Foreground service / data sync** — maintain truthful, visible status while an active Mercury-started turn is running.
- **Microphone (`RECORD_AUDIO`)** — voice dictation, voice conversations, and barge-in; requested only when you first start a voice feature, used only while one is active.
- **Foreground service / microphone and wake lock** — only for the opt-in screen-off voice continuation described above.

On Android, Mercury does not request location, contacts, camera, or storage-wide file permissions. Relay QR scanning delegates the camera surface to Google Play services' on-device code scanner; Mercury receives only the decoded QR text and never receives camera frames. Attachments use Android’s user-mediated document picker. Device dictation and server-backed voice use Mercury's explicitly requested `RECORD_AUDIO` permission; the configured Android speech-recognition service handles device dictation.

## iOS permissions

- **Camera** — requested only when you choose to scan a Mercury Relay pairing QR code; the frames are decoded on the device and are never stored or uploaded.
- **Microphone and speech recognition** — voice dictation into the composer, requested only when you first start dictating.
- **Notifications** — optional; see below.
- **Local network** — plain-HTTP connections are allowed only to local-network servers you enter explicitly; cleartext to public hosts stays blocked.
- **Background app refresh** — best-effort session reconciliation so local notifications can be posted. Optional Relay push delivery is a separate mechanism.

## iOS notifications

- **Direct-mode local alerts.** Without optional Relay push, alerts are generated on your device from your selected server's data while Mercury is running or during a background-refresh window. Delivery after iOS suspends the app is best effort, not guaranteed.
- **Optional Relay push.** When you enable Relay push notifications, Mercury obtains an APNs device token from Apple and registers it through your paired host with the hosted Mercury notification router. The router retains the token, APNs environment, opaque installation and wake identifiers, bounded delivery/deduplication state, and optional encrypted-preview metadata. Registration records have a 30-day expiry and may be renewed. They are used to deliver notifications, not advertising or tracking. The host does not persist the APNs device token in its registration state.
- **Encrypted previews are separately optional.** Your host can encrypt a conversation title, response excerpt, and notification route for your device. The router and Apple receive the encrypted payload and a generic fallback alert, not the preview key or readable conversation content. The preview key is stored in a device-only Keychain group shared with Mercury's notification extension. It becomes accessible after the first unlock following a restart, so the extension can decrypt previews while the device is locked; whether that content appears on the Lock Screen follows your iOS Show Previews setting for Mercury (Always, When Unlocked, or Never). Until the first unlock after a restart, the notification remains generic. Disabling excerpts preserves your choice not to include response text.
- **Removal and retention.** Disabling push or removing a pairing removes local delivery ownership and requests remote unregistration. Remote cleanup is best effort while the host/router is unavailable; pending cleanup is retained without preview keys, and router records still expire. Apple may retain or deliver already-queued notifications under its own service policies. Local preview replay records are bounded; authenticated tap-route records expire and are bounded separately.
- **Permission is asked only when you choose.** Mercury never shows the notification permission prompt at launch; it appears only when you tap Enable in Settings → Notifications.
- **Notification taps** use locally authenticated routes or resolve opaque wake identifiers through the paired host. Opening a notification never bypasses authentication — if the target server requires sign-in, you sign in first.

## Security

Cleartext network traffic is disabled. Mercury scopes credentials, connection settings, and cached transcript data by server origin, and it does not carry credentials to a newly selected origin.

No software can guarantee absolute security. Do not connect to a server you do not trust, and protect your device with a screen lock.

## Changes and contact

Material updates to this policy will be documented in this repository. For a security-sensitive privacy concern, follow [SECURITY.md](SECURITY.md) rather than opening a public issue.
