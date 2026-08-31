# External SSH tunnel — owner device checklist

Use this on a physical phone with a working SSH app. An agent cannot pass this matrix. Record what you actually saw. Do not mark a row pass unless you observed it.

**APK:** after Task 8 assemble, install the debug APK from `app/build/outputs/apk/debug/app-debug.apk`.

**Mode:** External SSH tunnel. Origin must be `http://127.0.0.1:<port>` (or `[::1]`). `localhost` is rejected. Do not paste a session token. HAM does not start SSH and does not store SSH credentials.

**Hermes:** 0.20.4 or newer, unchanged `hermes serve` / dashboard, reached only through your local forward.

---

## Before you start

1. Install the debug APK (do not wipe an authenticated production install if you care about its data).
2. In Termius or another SSH app, create a **local** port forward:
   - Local bind: `127.0.0.1`
   - Local port: unused, often `9119`
   - Remote host: `127.0.0.1`
   - Remote port: Hermes dashboard port (often `9119`)
3. Connect SSH and confirm the forward is active.
4. On the phone browser, open `http://127.0.0.1:<port>/api/status` and confirm Hermes JSON (version, `auth_required`, etc.). If that fails, HAM cannot succeed.
5. Leave battery/background exceptions for the **SSH app** as you would in real use. HAM cannot keep that app alive.

Date: ____________  
Device / Android: ____________  
SSH app: ____________  
Hermes version: ____________  
HAM build / commit: ____________

---

## Matrix

For each row: do the steps, check the expected result, then record Pass / Fail / Skipped and a short note. Screenshots of UI states are optional; never attach tokens, credential-bearing URLs, prompts, transcripts, attachments, or SSH connection details (see Diagnostics).

### 1. Cold connect through a local forward

**Steps:** Force-stop HAM. Confirm the SSH forward is up. Open HAM → Settings → Servers → **External SSH tunnel**. Confirm origin (default `http://127.0.0.1:9119`). Read the shared-loopback warning. Tap **Test tunnel**. Then Save.

**Expected:** Experimental label is visible. Warning appears **before** Save. Test tunnel succeeds without saving the server and without asking for a token. After Save, HAM connects and can list sessions. You never typed a dashboard token.

| Result | Observed |
|---|---|
| Pass | Test tunnel handshake succeeded (owner, earlier session). |

### 2. Second SSH client (if you have one)

**Steps:** Repeat item 1 with a different SSH app (same local port or a new unused port). If you only have one SSH app, skip and say so.

**Expected:** Same as item 1. HAM does not care which SSH app owns the forward.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 3. Prompt, request, and WebSocket

**Steps:** With the tunnel connected, send a normal prompt. If you use voice, start a short speech turn as well.

**Expected:** Chat completes normally. Speech (if used) works over the same loopback credential. No token paste. Cached lists remain labelled cached only when actually offline.

| Result | Observed |
|---|---|
| Pass | Chat over tunnel works, including images (owner, 2026-08-31). |

### 4. Background and foreground while the tunnel stays up

**Steps:** Start a turn or leave a connected session. Send HAM to background for at least a minute. Return to the foreground.

**Expected:** Session is still usable. HAM does not ask you to sign in again. If the SSH app dropped the forward, that is item 7, not a pass here.

| Result | Observed |
|---|---|
| Pass | Background then reopen: cached → connected; reconnect works (owner, 2026-08-31). |

### 5. Kill and restart HAM (token reacquired)

**Steps:** With the tunnel still up, force-stop HAM (or swipe it from Recents). Reopen HAM.

**Expected:** Origin and External SSH tunnel mode are still selected. HAM reconnects **without** asking you to paste a token or re-enter SSH details. Sessions load again.

| Result | Observed |
|---|---|
| Pass | Swipe from Recents then reopen: same as item 4. Settings Force stop then reopen: same (owner, 2026-08-31). |

### 6. Restart Hermes (token rotation)

**Steps:** Restart `hermes serve` / dashboard on the remote host. Keep the SSH forward up. Wait or tap Retry if needed.

**Expected:** HAM discards the in-memory token and adopts the new one automatically, at most one bounded refresh. You should not paste a token. If authorization fails twice, you see **Authorization failed** with Retry, Connection setup, and Cancel — then Retry after confirming the tunnel still points at this Hermes.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 7. Stop and restart the tunnel

**Steps:** Disconnect or stop the local forward in the SSH app. Watch HAM. Restore the forward. Tap Retry if the banner says to.

**Expected:** Banner title **SSH tunnel unavailable**. Body says this app cannot start the tunnel; Retry reconnects **this app only**. After you restore the forward, Retry (or automatic recovery within the budget) reconnects. HAM never claims it restarted Termius.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 8. Network transitions while the SSH app heals

**Steps:** With a live tunnel, switch among Wi-Fi, mobile data, and Tailscale (or whatever you actually use to reach the SSH host). Let the SSH app reconnect. Watch HAM.

**Expected:** While the forward is down, same waiting state as item 7. When the SSH app restores the forward, HAM recovers without a new token paste. Do not treat a Tailscale-only address as a HAM cleartext origin — HAM still talks to `127.0.0.1`.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 9. Lock screen and vendor battery

**Steps:** Lock the phone for several minutes with the tunnel up. Unlock. If you use aggressive battery optimization, also: leave HAM and the SSH app under the vendor’s default battery policy once, then again with the SSH app exempted.

**Expected:** Unlock returns to a usable session if the SSH app kept the forward. If the vendor killed the SSH app, you get tunnel unavailable — that is an SSH-app/battery issue, not HAM starting the tunnel. Note the vendor behavior honestly.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 10. Device reboot

**Steps:** Reboot the phone. Do not open HAM until you have noted whether the SSH app auto-restored the forward. Then open HAM.

**Expected:** HAM still has the saved origin and External SSH tunnel mode. If the forward is not up yet, **SSH tunnel unavailable**. After you restore the forward, HAM connects without a token paste.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 11. Wrong service on the local port

**Steps:** Stop the Hermes forward. Bind a different local service to the same port (for example a tiny HTTP server that is not Hermes). In HAM, Test tunnel or Retry.

**Expected:** **Wrong service on this port** — “The local port is occupied by a different service. Choose another local port.” Not the generic tunnel-unavailable copy. Restore the real Hermes forward afterward.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

### 12. Two origins on two local ports

**Steps:** Create two forwards (example `:9119` and `:9120`) to two Hermes instances, or the same instance on two ports if that is all you have. Save both as External SSH tunnel servers. Use one, then the other. Confirm each shows its own sessions.

**Expected:** Credentials, caches, and session lists stay isolated per origin. Switching servers does not reuse the other port’s token. No sign-in prompt for tunnel mode.

| Result | Observed |
|---|---|
| Pass / Fail / Skipped | |

---

## Adaptive layout on real hardware

Screenshots on a workstation are not this section. Use a compact phone, and if you have them a medium width (fold inner, large phone, split screen) and an expanded width (unfolded foldable, tablet, DeX).

| Width | What to open | Expected | Result | Observed |
|---|---|---|---|---|
| Compact | Tunnel setup | Connection type, origin, Experimental, warning, Test tunnel, Save. Checklist / Test tunnel / warning / Save may require scroll on a short screen. | | |
| Compact | Connected session list + an open chat | Recovery banner (when in a failure state) does not hide the list. Cached label only when offline. | | |
| Compact | Tunnel unavailable and Installation changed | Distinct titles: **SSH tunnel unavailable** vs **Hermes installation changed** (Accept new server / Cancel). | | |
| Medium | Same three surfaces | List and detail or settings use the wider layout; copy still readable. | | |
| Expanded | Same three surfaces | List/detail or large settings; no overlapping composer/IME; insets look correct. | | |

Optional: rotate, split-screen, and fold/unfold with a selected session and a composer draft. Selection and draft should survive.

---

## Screenshot goldens (owner visual review)

Do **not** update references until you have looked at the rendered candidates.

Report: `app/build/reports/screenshotTest/preview/debug/index.html`  
Rendered candidates: `app/build/outputs/screenshotTest-results/preview/debug/rendered/`

**Missing goldens (new Task 7 previews):**

- Compact / medium / expanded External SSH tunnel setup
- Compact / medium / expanded tunnel unavailable
- Compact / medium / expanded installation changed

**Known compact mismatches (existing goldens):**

- Compact server settings
- Compact active session workspace
- Compact dark markdown table

Compact tunnel setup at 400×900 is expected to crop below the fold. Confirm Test tunnel and the security warning exist when you scroll, then decide whether the golden should be the folded first screen or a taller preview.

After visual review, update goldens only if you approve the pixels.

---

## Diagnostics

### Capture when something fails

Safe to record:

- HAM commit / APK date, device model, Android version, SSH app name (not the host).
- Local bind address and **port number only** (example: `127.0.0.1:9119`).
- Hermes **version** from `/api/status` (not `install_id` unless you must; treat it as non-secret catalog metadata, never as a credential).
- HTTP status codes only: `200`, `401`, `403`. For WebSocket: close codes `4401`, `4403`, `4404`, `4408`, `1011`, or handshake HTTP `403` vs `101`.
- Which matrix row, what you did, and the **banner title** HAM showed.
- Whether the phone browser could open `/api/status` at that moment.
- Whether the SSH app still showed the forward as active.

### Never capture or share

Do not screenshot, paste, log, or attach:

- Dashboard session tokens, OAuth tokens, cookies, tickets, API keys
- URLs that contain `token=`, `ticket=`, or other credentials
- Prompts, transcripts, attachments, sudo/terminal input
- SSH hostnames, usernames, passwords, keys, `ProxyCommand`s, or full `ssh` command lines
- Connection strings that include secrets
- Full HTML of `/` (it embeds the session token)
- Full `/api/status` bodies if they include secrets you would not put in a bug report

If you need to prove bootstrap happened, say “root HTML contained one session-token assignment” — never the value.

There is no in-app logging of these fields. Do not add adb logcat dumps that might contain them. If logcat is required, scrub it first.

---

## Honest residuals (do not treat as a device pass)

- Feature ships **Experimental**. Token comes from dashboard HTML, not a published API.
- Android loopback is shared: any app on the device can reach the forwarded port.
- A rejected WebSocket **handshake** may be HTTP 403 rather than close code 4401. Report which you saw. Do not “fix” that by pasting a token.
- Screenshot goldens are not approved until you say so.
- This checklist is empty until you fill it. An agent run that never used this phone is not a device pass.

---

## Owner observed (2026-08-31)

Owner-reported on a physical device. **Not agent-observed.** Partial coverage only — matrix **not complete**.

- Test tunnel handshake succeeded (earlier session).
- Chat over tunnel works, including images.
- Background → reopen: cached → connected; reconnect works.
- Swipe from Recents → reopen: same.
- Settings Force stop → reopen: same.

**Not reported / still open:** items 2, 6–12; adaptive layout on hardware; screenshot golden approval.
