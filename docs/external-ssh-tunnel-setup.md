# External SSH tunnel setup

HAM can reach a remote `hermes serve` through a **local port forward** that you create in an SSH app on this phone. HAM does not run SSH, does not store SSH credentials, and does not store the Hermes session token.

This mode ships as **Experimental**. It depends on scraping a session token from the dashboard HTML rather than a published API, on a third-party SSH app staying alive, on a loopback port that every app on the device can use, and on there being no capability flag to negotiate against.

## Security warning

Any other app on this device can reach the forwarded port and obtain the same session token. Android loopback is not exclusive to HAM and is not a sandbox around this forward.

Only use this mode on a device you trust.

## What you configure in the SSH app

The forward lives entirely in Termius or another SSH client. HAM never asks you to paste a token.

Vendor-neutral local-forward fields (Termius is an example, not a requirement):

| Field | Typical value |
|---|---|
| Type | Local port forward |
| SSH host | your existing working host |
| SSH port | normally 22 |
| Local bind address | `127.0.0.1` |
| Local port | `9119`, or any unused local port |
| Remote destination host | `127.0.0.1` |
| Remote destination port | `9119` |

If local port 9119 is taken, pick another unused port in the SSH app and enter that same port in HAM, for example `http://127.0.0.1:19119`.

Use **`127.0.0.1`**, not `localhost`. `localhost` is rejected because it is ambiguous between IPv4 and IPv6 on Android.

## Checklist before opening HAM

1. SSH is connected in the SSH app.
2. The SSH host key is reviewed and pinned **there**. HAM cannot verify the SSH host.
3. The local forwarding rule is active.
4. A browser on the phone can open `http://127.0.0.1:<port>/api/status` and see Hermes JSON.
5. Battery and background restrictions for the SSH app are set if you expect the tunnel to survive idle time. HAM cannot keep that app alive.

## In HAM

1. Open Settings → Servers.
2. Choose **External SSH tunnel**.
3. Confirm the origin, default `http://127.0.0.1:9119`.
4. Read the shared-loopback warning. It appears **before** the mode is saved.
5. Tap **Test tunnel**. That handshake only reads status, the root page, and a session list. It does not save the server and does not change Hermes.
6. Save. HAM will adopt the current dashboard token into memory and connect. You are never asked to paste that token.

## Recovery

| What happened | What HAM shows | What you do |
|---|---|---|
| SSH app stopped, forward died, or the local port refused | SSH tunnel unavailable. HAM cannot start the tunnel. Retry reconnects **this app only**. | Restore the forward in the SSH app, then Retry. |
| Hermes restarted and the token was rejected twice | Authorization failed, with Retry, Connection setup, and Cancel. | Confirm the tunnel still points at the expected Hermes, then Retry. |
| Another process bound the local port | Wrong service on this port — choose another local port. | Change the local port in the SSH app and in HAM. |
| Hermes reached but the token handshake failed | Bootstrap unavailable. | Confirm `/` still serves the dashboard token page. |
| The tunnel points at a gated OAuth server | Gated Hermes server. | Use Hermes Cloud or Server URL instead. |
| Unsupported Hermes version | Protocol incompatible. | Upgrade Hermes to 0.20.4 or newer. |
| `install_id` changed on the same port | Hermes installation changed — Accept new server or Cancel. | Accept only if you intended a different Hermes on that port. |

Cached session lists and transcripts stay visible while offline and are labelled as cached.

If a send or other write is rejected, HAM shows that operation's error and an explicit **Retry action**. It does not silently replay the write.

## After a Hermes restart or a tunnel restart

- Hermes restart: HAM discards the in-memory token and reads a new one from the local dashboard. You should not need to paste anything.
- Tunnel restart: HAM waits and retries its own connection. You restore the forward in the SSH app. HAM cannot restore the tunnel.
- Phone reboot: HAM remembers the origin and mode. The SSH app may need to recreate the forward before HAM can connect again.

## Battery and background

The SSH app owns the tunnel. Vendor battery savers, Doze, and missing background permission can drop it. Exclude the SSH app from aggressive optimization if you need the forward while HAM is in the background. HAM does not add a permanent foreground service just to keep an idle tunnel open.

## What HAM does not do

- Control Termius or any other SSH app.
- Store SSH hostnames, usernames, passwords, or keys.
- Store the Hermes dashboard session token.
- Start, stop, or reconfigure `hermes serve` on the remote host.
