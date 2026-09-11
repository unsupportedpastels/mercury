# Relay APNs (iOS)

Optional native Apple push registration supplements the existing direct-mode local notifications. Android APNs support is intentionally deferred: APNs is an Apple platform delivery mechanism; this change does not redesign Android notifications or Hermes APIs.

## Contract and privacy

After an approved paired Relay admission, Mercury reads `relay.status`. Only an explicit JSON boolean `capabilities.push_notifications_v1: true` enables the encrypted RPC:

- `relay.push.register`: `device_token` (lowercase hexadecimal), `environment: "sandbox"`.
- Successful response: `registered: true`, `wake_handle` (43 base64url characters).
- `relay.push.unregister`: empty parameters; successful response `registered: false`.

APNs alerts contain only `aps.alert.title: "Mercury"`, `aps.alert.body: "An update is available. Open Mercury to continue."`, `aps.sound: "default"`, and the opaque `mercury_wake` handle. No session identifiers, titles, text, prompts, or credentials are sent in the alert. Publisher signing keys never belong in the app.

The private local handle binding includes pairing UUID, relay origin, installation, device identity, and host public key. A tap must resolve to an existing approved pairing; unknown, removed, pending, or malformed bindings do nothing. A valid tap returns to that host's home list. It does not open/resume a conversation or answer pending input. Foreground generic pushes are suppressed; direct local banners retain their existing behavior. Successfully registered Relay targets use APNs-owned delivery rather than also posting local completion notifications.

Token changes, fresh connections, and target selections reconcile registration. Generation checks reject stale asynchronous results. Disabling clears local bindings immediately, unregisters Apple remote delivery, and queues host unregistration behind any in-flight registration. Pair removal clears routing immediately and attempts an isolated, dedicated cleanup admission before discarding pairing keys. Host-side unregistration is best effort while offline; it is not a claim that an unreachable host acknowledged revocation.

An Apple registration failure invalidates the in-memory token, persisted wake bindings, and pending registration generation immediately, restoring local delivery without waiting for the network. Host unregistration is queued after pending work and retried on reconnect while Apple registration remains unavailable. A stale RPC completion cannot reclaim delivery. Only a later successful Apple token callback (even if Apple returns the same bytes) permits registration again; that work follows queued cleanup.

## Environments

XcodeGen sets the app's `aps-environment` to `development` for Debug and `production` for Release. The current server supports sandbox only. Release builds explicitly report this limitation and **never send a production device token as a sandbox token**. Visible alerts do not require the silent-push background mode.

Enable notification permission plus completion and attention alerts in Settings. The generic v1 push cannot distinguish category preferences remotely, so selective category choices stay on the existing local path rather than overriding an opt-out. Failure/cancellation events retain their local path even when APNs owns successful completion and attention alerts. Direct mode and older Relay hosts continue using the existing best-effort local notifications. The status describes registration, not a guarantee that Apple delivered a banner.

## Explicit simulator diagnostics (Debug only)

For an operator-run registration-status check, launch the simulator app with:

- `-debug-apns-register`: explicitly request notification permission and enable the master notification preference. This opt-in is not used during normal launches.
- `-debug-apns-diagnostics`: allow APNs callbacks/registration status to write `Library/Application Support/apns-diagnostics.json` **inside the app's private simulator data container**, mode `0600`, with file protection. The only fields are `status`, `environment`, `registered`, `enabled`, and `has_token`. Status strings are app-defined; token presence is a boolean, not a token value. No raw APNs token, wake handle, pairing identity, or connection information is exported.

The next explicit diagnostic write removes any legacy diagnostic file before writing the allowlisted state, so old secret-bearing contents are not preserved if replacement fails. This is not a migration that scans or alters user files at startup. There is no simulator diagnostic export in Release or on physical devices. Diagnostics cannot supply credentials for a hosted fixture or prove APNs delivery; no alternate raw-token logging/export is provided. The app must be paired to the intended supported host for an end-to-end register/wake test; the flags do not bypass pairing or authentication.

## Regression gates

`RelayPushTests` exercises capability gating, wire fields, token rotation/reconnect, production-token rejection, scoped routing, unregistration, and stale completions. Regression cases cover Apple failure after success and during delayed registration, stale-token rejection across reconnect/enable, fresh-callback recovery, and the actual diagnostic JSON file replacing a synthetic legacy artifact without exporting secrets. `RelayPushUITests` uses `-uitest-push` (and optionally `-uitest-push-unsupported`) with an in-memory RPC substitute to exercise native delegate routing, foreground suppression, direct fallback, and unsupported/disabled no-op routing. The fixture performs no live sign-in or session mutation.

`RelayPushHostedIntegrationTests` is an operator-only simulator gate: it runs only when a private `Library/Application Support/apns-e2e.json` fixture is installed. The fixture supplies an explicitly approved ephemeral test pairing and an Apple-issued simulator token. The test exercises the production Swift connection pool, Noise transport, capability check, registration RPC, and persisted wake binding against a real host/Worker. Remove the private fixture after testing; never commit it or print its fields. An external bounded host harness can then inject synthetic completion/input events into the retained controller to verify real APNs banners.

Hermetic tests and `simctl push` are **not proof of real APNs delivery**. Real evidence requires Apple registration and a provider request through the intended environment, followed by observing the resulting simulator/device notification. Keep that result separate from injected presentation tests.
