# Verification report — 2026-10-24

Environment: this machine (Linux, OpenSSH 10.0p2 client+server, util-linux
2.41.5, OpenJDK 21, Kotlin 2.1.0 compiler out of the Gradle cache, JSch
0.2.20). Target of the port: **Kotlin Multiplatform** client (mercury shape).

## Result

```
bash tools/build.sh
[        35 tests successful      ]
[         0 tests failed          ]
[         0 tests skipped         ]
```

Nothing is skipped: the fixture sshd was up, so every integration case really
ran. Longest cases: the hung-probe watchdog (2.09 s, i.e. it really waited for
its own kill), the full lifecycle (1.36 s).

## What is verified (and how)

| # | Claim | How it was verified |
|---|---|---|
| 1 | ControlMaster policy (`-M -N -f`, ControlPath, ControlPersist 300, BatchMode, keepalives) works against a real sshd | `openMaster()` + `-O check` success |
| 2 | **`-O` control calls must repeat host args** | verified by *breaking* it: without `-p 2222` the ControlPath `%p` expands to 22 and the socket silently misses |
| 3 | Dynamic forward binds loopback only and carries real HTTP | `-O forward -L 127.0.0.1:L:127.0.0.1:R` then an HTTP GET through the tunnel returns 200 |
| 4 | Remote watchdog kills a hung probe and reports TIMEOUT | `execProbe("sleep 30", 2)` returns in ~2 s with `kind == TIMEOUT`; the remote shell logs `Killed` |
| 5 | A non-zero probe verdict is data, not an error | `execProbe("exit 1")` returns `""` instead of throwing (needed by `locate hermes`) |
| 6 | SSH error taxonomy ordering | host-key-change banner wins over the `Permission denied` inside it; Tailscale login → `INTERACTIVE_AUTH`; refused/resolve → `UNREACHABLE` |
| 7 | ToFU host keys + changed-key detection (library path) | `TofuHostKeyRepository`: unknown → OK & persisted, same → OK, different → CHANGED |
| 8 | Library transport (the Android route) matches the CLI transport | JSch: `uname -s` = Linux, local forward + HTTP GET through it |
| 9 | Capability gate | `serve --help` must expose both ownership flags; the fake CLI does, and the gate returns YES |
| 10 | Ownership material shape matches the **server-side validator** | token path is `~/.hermes/desktop-ssh/<32hex>/<16hex>.token`, ownership dir `0700` (server requires absolute path, that exact shape, parent 0700, owner match, no symlinks) |
| 11 | Spawn contract | the payload contains `serve --isolated --host 127.0.0.1 --port 0`, `--ssh-session-token-file`, `--ssh-owner-nonce`, `ulimit -n 65536`, `setsid`/`nohup` fallback, detached stdio, merged log |
| 12 | Reuse instead of respawn | second `connect()` on the same ownership id prints `EXISTING` and returns the *same* pid and port taken from the lockfile |
| 13 | Lockfile republished after readiness | it carries the resolved port and **the served token's** fingerprint (not the minted one) |
| 14 | Teardown is ownership-proofed | only a live pid matching the lockfile is stopped; skew aborts instead of reaping; then token+lock+dir removed (dir verified gone) |
| 15 | Served-token adoption + foreign-backend refusal | index HTML `window.__HERMES_SESSION_TOKEN__` parsed (incl. escapes/`\uXXXX`); served ≠ ours **and** child dead → refuse; child alive → benign drift (unit-tested, and exercised end-to-end by the reuse path) |

## Bugs found by verification (each would have shipped)

1. **`-o` options appended *after* the destination become remote-command arguments.**
   `ssh … -- host '<cmd>' -o UserKnownHostsFile=…` makes the remote run
   `'<cmd>' -o UserKnownHostsFile=…`; the watchdog's `exit $__htrc` then died with
   `exit: too many arguments`. Fixed by injecting options **before** `--`.
   *Any* client that adds per-connection options (known-hosts file, identity,
   jump host) hits this.
2. **`ssh -O check` writes "Master running" to stderr, not stdout.**
   A stdout-`contains` check reports a healthy master as dead. Trust the exit
   status. (This is what made the first integration run fail.)
3. **Upstream's `eval "exec $1>&-"` is only safe with upstream's Python gate.**
   Without that gate `$1` is empty, so the prefix becomes `exec >&-` — stdout is
   closed, the inner `echo $!` dies with an I/O error, the pid comes back empty
   and the lockfile is published as `"pid":,` (invalid JSON). Fixed by dropping
   the prefix and adding a `case … exit 77` guard so an empty pid can never be
   published.
4. **util-linux `flock` passes the lock fd to the command's children.**
   The spawned backend inherited it and held the gate **for its whole life** —
   the next connection sat out the full 60 s `-w` timeout (visible as one 60.1 s
   test run) and every later ssh call in the same ownership scope failed with an
   empty-stderr `SshException`. Fixed with `flock --close` (feature-detected).
5. **Library contract mismatch (JSch):** `HostKeyRepository.check(host, byte[])`
   takes raw bytes (not a `HostKey`), `add(HostKey, UserInfo)`, and there are
   **two** `remove` overloads — all abstract members must be implemented.
6. **"Lockfile absent" vs "lockfile empty"** must be distinguished: absent is
   simply no record, empty is skew (fail-closed, never reap). Solved with an
   explicit missing sentinel from the remote command.

## What is NOT verified (honest limits)

- **Android runtime.** No device or emulator was used: Doze/background kills,
  vendor ROM differences, the app's own service lifecycle and secure storage are
  untested. The library transport is verified on the JVM, which is the closest
  thing to Android without a device (`org.apache`/JSch code paths are the same
  Java APIs), but it is not proof about the phone.
- **iOS** (`darwin` transport) and the Windows path.
- **The real `hermes` binary.** Everything ran against `fixtures/fake-hermes`,
  which implements the flags, the token file, the READY line and the three HTTP
  routes the lifecycle touches. That is deliberate (the fixture is a contract,
  not a reimplementation) but it means the real server was never spawned.
- **The real update-marker judge** (see divergence 1 in the README).
- **Multi-connection supersede/drain, crash forensics, remote terminal,
  `hermes update` interplay.**
- **The user's own desktop ownership dirs were left untouched** (18 pre-existing
  `~/.hermes/desktop-ssh/*` dirs belong to their Desktop app); tests use a
  dedicated 32-hex id and clean it up.

## How to reproduce

```bash
bash tools/fetch-libs.sh
bash tools/fixture-sshd.sh start
bash tools/build.sh              # 35/35
bash tools/fixture-sshd.sh stop
```

The fixture sshd is a throwaway user-owned instance on 127.0.0.1:2222 with its
own host key and its own `authorized_keys` (a copy of the operator's public
key). The system sshd and `~/.ssh/authorized_keys` are never modified, which is
why `ssh localhost` (port 22) still answers `Permission denied` on this box.