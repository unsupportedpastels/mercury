# :shared:ssh-lifecycle

Hermes Desktop's **SSH** connection kind, ported to Kotlin Multiplatform.

Desktop describes it as *"A Hermes install reached over SSH. The app opens the
tunnel and starts the dashboard for you"* — this module reproduces exactly that
against an unchanged remote Hermes, so a Mercury user does not have to bind
`hermes serve` to a LAN/Tailscale address and expose a dashboard first.

## Why a separate module

`commonMain` has **no dependencies at all** (no coroutines, serialization or
crypto provider): the lifecycle is a small state machine over injected services,
so it compiles for every target — including the gated Apple ones — and is
testable without Android APIs. The only platform-specific piece is the
transport, and Android's is in `androidMain`.

## The shape

```
probe ─► ssh -M -N -f (one Session) ─► mint token + spawn nonce
      ─► marker gate: atomic .connect.lock reservation, setsid/nohup detached
         `hermes serve --isolated --host 127.0.0.1 --port 0
              --ssh-session-token-file <16hex>.token --ssh-owner-nonce <16hex>`
      ─► atomic backend.lock.json publish (tmp + mv -f)
      ─► scrape "HERMES_DASHBOARD_READY port=<n>" from the merged log
      ─► forward 127.0.0.1:<local> → 127.0.0.1:<n>   (loopback only)
      ─► adopt the token the dashboard actually serves (GET / , parse
         window.__HERMES_SESSION_TOKEN__); refuse a foreign backend
      ─► republish the lockfile with the resolved port + served fingerprint
      ─► later connects reuse (remote prints EXISTING; pid+port from the lockfile)
      ─► teardown stops only a backend the lockfile proves is ours
```

Android substitutes for the CLI: one `Session` replaces `ControlMaster`,
`setPortForwardingL` replaces `ssh -O forward`, `TofuHostKeyRepository` replaces
`StrictHostKeyChecking=accept-new` (unknown ⇒ persist+accept, changed ⇒ refuse).

## Using it

```kotlin
val target = SshTarget.parse("user@203.0.113.10:22")
val transport = JschSshTransport(target, knownHostsFile = File(filesDir, "ssh_known_hosts").absolutePath)
val lifecycle = SshBackendLifecycle(
    target = target, transport = transport,
    portPicker = AndroidPortPicker(), http = AndroidHttpFetcher(),
    clock = AndroidClock(), random = AndroidRandom(), hasher = AndroidHasher(),
    sleeper = AndroidSleeper(),
)

transport.openMaster()
val probe = lifecycle.probe()                       // uname, hermes path/version,
                                                    // ownership-capability gate, profiles
val conn = lifecycle.connect(probe, ownershipId, spawnNonce, profile = "default")
// conn.baseUrl → http://127.0.0.1:<localPort>, conn.servedToken → WS ticket auth
// ... later
lifecycle.disconnect(conn)
```

All calls block: run them off the main thread.

## Verifying

The module's host tests are JUnit 4 (host-test convention of this project) and
cover both payload shape and real behaviour:

```bash
bash tools/ssh-lifecycle-verify/fetch-libs.sh          # one-time, network
bash tools/ssh-lifecycle-verify/fixture-sshd.sh start  # throwaway sshd on :2222
bash tools/ssh-lifecycle-verify/build.sh               # 35 tests
bash tools/ssh-lifecycle-verify/fixture-sshd.sh stop
```

That harness compiles these three source sets as plain JVM code (no Gradle, no
Android SDK) — see `docs/VERIFICATION.md` in the standalone port for what each
test proves, the six real bugs the exercise caught, and the honest list of what
is *not* covered (on-device Android behaviour, iOS, the real update-marker judge).

## Deliberate divergences from upstream Desktop

1. The update-marker *judge* (257 lines of Python upstream) is replaced by a
   `flock --close` gate: mutual exclusion plus "refuse while an update marker
   exists". Port the judge before shipping if install-update interplay matters.
2. No multi-connection supersede/drain coordinator (single active connection).
3. No Windows path (no ControlMaster there upstream) and no `ssh -tt` remote
   terminal.

Licence: MIT. The ported logic derives from `NousResearch/hermes-agent`
(MIT, © 2025 Nous Research) — keep that notice.