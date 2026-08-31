# Cursor continuation brief: external SSH tunnel authentication

> **Superseded where decisions differ:** read `docs/EXTERNAL_SSH_TUNNEL_QUESTIONS_ANSWERS.md` first. It is the authoritative continuation contract. In particular, REST credential refresh is triggered by `401` only; `403` is an operation/policy denial and must not trigger token refresh or automatic replay.

## Objective

Continue the Android client work for external SSH port forwarding to an unchanged remote `hermes serve` instance.

The Android app must connect to a phone-local forwarded endpoint (for example, `http://127.0.0.1:<port>`), authenticate with the dashboard bootstrap session token, and remain safe when the tunnel, origin, port mapping, or remote Hermes process changes.

Do **not** embed an SSH client. The user manages the SSH local port-forward separately.

## Branch and current checkpoint

- Branch: `docs/external-ssh-tunnel-session-auth`
- Design and Task 1 are already committed.
- The current checkpoint contains implemented but incomplete Task 4A hardening. It is intentionally committed so work can resume from a reproducible state.
- Before changing code, read:
  - `AGENTS.md`
  - `docs/design/external-ssh-tunnel-session-auth.md`
  - `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesCredential.kt`
  - `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/LoopbackSessionBootstrapClient.kt`
  - `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionClient.kt`
  - `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionViewModel.kt`

## Implemented and verified

1. `ServerConnectionMode.ExternalSshTunnel` is persisted and only permits loopback origins.
2. The loopback dashboard bootstrap retrieves the session token from the root HTML; the token remains origin-bound and process-memory-only.
3. `HermesCredential` distinguishes:
   - `None` — no auth header/query;
   - `NativeBearer` — OAuth bearer authorization;
   - `LoopbackSession` — `X-Hermes-Session-Token`, bound to one normalized origin.
4. Protected REST APIs were migrated to accept `HermesCredential`; public routes remain unauthenticated.
5. Chat and speech WebSocket authentication routes are credential-aware:
   - OAuth uses a fresh ticket query parameter;
   - loopback session uses only its session-token query parameter;
   - `None` uses no auth query.
6. The tunnel connection flow has explicit unavailable/bootstrap-rejected classifications and avoids persisting loopback secrets.
7. Retry policy intention:
   - idempotent REST reads may bootstrap once and retry once after a rejected loopback credential;
   - mutations and controller-like operations must not replay;
   - stale generation/origin completions must not modify current state.

## Current blocking gaps — do not claim Task 4A complete

A strict review found these material defects in the current checkpoint.

### A. Complete protected REST `401/403` classification

`HttpHermesConnectionClient` must convert every credential-bearing protected `401` or `403` to `HermesAuthenticationRejectedException` *before* fallback handling. Public routes must not use this classifier.

Known misses to audit/fix:

- `listElevenLabsVoices`: early `emptyList()` return happens before classification.
- `deleteSession`.
- `setProfileReasoningEffort`.
- `setProfileModelReasoningOverride`.
- `loadTranscript`: currently handles only `401` and throws `HermesUnauthorizedException`; it must handle both `401` and `403` with the common rejection type.
- Host file/directory/image methods: loading, reading, downloading/streaming, listing directories, creating directories, and image download.
- Voice capability/config reads currently classify, but broad fallback logic swallows the common rejection exception. Preserve the rejection type so the ViewModel can refresh the tunnel credential.

Add table-driven tests covering `401` and `403` for all protected endpoint families, plus regression tests that public status/provider discovery never classifies as credential rejection.

### B. Route all idempotent REST reads through the single retry helper

Use the established `withCurrentHermesRestRead` / `withHermesRestRead` path for every idempotent protected ViewModel read. It should acquire the current credential, retry a rejected **loopback** credential once after shared bootstrap, then check origin/generation freshness.

Known direct-read gaps:

- management current-model, reasoning effort/default/override reads;
- management profile-session listing;
- draft reasoning-default hydration;
- recovery transcript reload.

Do not wrap mutations in this helper. Mutations remain single-shot even when their credential is rejected.

### C. Preserve OAuth behavior when transcript taxonomy is corrected

Correcting transcript `401/403` to the common rejection exception must not break native OAuth reconnect/refresh behavior. Update the OAuth path deliberately and add a regression test.

### D. Guard stale mutation completions

Session delete and session update need post-response origin/generation/profile guards before changing cache or snapshot state. A non-cooperative old-origin response must not alter the newly selected scope.

### E. Close test gaps

Add focused RED → GREEN tests for:

- production transcript `401/403` → one loopback bootstrap → one retry;
- host-file/directory/image rejection and retry;
- voice capability/config rejection propagation;
- ElevenLabs rejection propagation;
- second rejection produces `CredentialRejected` and stops after one retry;
- genuinely concurrent rejected reads share one successful bootstrap;
- stale rebootstrap after origin/generation/mode/port change;
- stale session delete/update completion after scope change;
- auth-free direct requests use `HermesCredential.None`;
- mutation rejection uses the production `HermesAuthenticationRejectedException` type but never replays.

## Non-goals for this continuation

Do not change the server, add custom server routes/plugins, embed SSH, persist loopback secrets, alter unrelated UI, or weaken existing assertions to make the build green.

Do not touch later slices unless separately requested:

- WebSocket/speech close-code recovery policy;
- lifecycle reducer/backoff behavior;
- endpoint-identity / wrong-service taxonomy;
- settings UX and user documentation beyond changes needed for correctness.

## Verification required before each commit

Use JDK/SDK configured for this repository, then run:

```bash
./gradlew --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks testDebugUnitTest
./gradlew --no-daemon lintDebug assembleDebug
git diff --check
```

Run focused tests first for each defect. Request an independent spec and security review before claiming Task 4A is complete. Keep commits small and state residual risks honestly in the PR.
