# Task 7 layout-fix security review

Independent read-only security review of commit `ad5d7de` (screenshot-review layout fixes for tunnel setup and installation changed).

**Scope:** installation-changed duplicate removal, recovery-action wrapping, expanded/short-viewport tunnel Save/actions ordering.  
**Lens:** logging, token/credential handling, exported components, cleartext policy, shared-loopback disclosure, installation-changed explicit accept flow, Test tunnel non-mutation.  
**Authoritative contract:** `AGENTS.md`, `docs/EXTERNAL_SSH_TUNNEL_QUESTIONS_ANSWERS.md`, `.agents/skills/android-intent-security/SKILL.md`.  
**Graphify:** queried before file reads (`External SSH tunnel UI layout security logging tokens exports cleartext`; no directed path from tunnel setup UI to logging).  
**Did not:** update screenshot goldens, push, or mutate implementation code.

## Verdict

**PASS**

**Counts:** Critical 0 / Important 0.

## Changed files (security-relevant)

| File | Security relevance |
|---|---|
| `ConnectionRecoveryBanner.kt` | Layout only: `Row` → `FlowRow` for recovery actions |
| `ExternalSshTunnelSetup.kt` | Removes duplicate setup guide on non-compact widths |
| `HermesApp.kt` | Installation-changed empty-state dedup; short-viewport tunnel layout reorder |
| `ExternalSshTunnelSetupTest.kt` | Layout assertions only; no credential/catalog mutation tests added |

No changes to `AndroidManifest.xml`, network security config, credential stores, reducers, or transport evaluation.

## Findings

### Critical

None.

### Important

None.

### Minor

- Short-viewport tunnel mode (`screenHeightDp < 800`) hides the server catalog list to save vertical space. Shared-loopback warning, Test tunnel, origin field, and Save remain composed; a Compose test pins warning/Test/Save above the checklist on `w900dp-h675dp`. Checklist-after-Save tradeoff is unchanged from prior compact-fold review.
- `FlowRow` recovery actions may wrap on very narrow panes. Callbacks and action set are unchanged; Accept new server / Cancel remain explicit buttons, not auto-accept.
- Screenshot candidate PNGs added under `.superpowers/sdd/screenshot-candidates/rendered/` contain UI chrome only; no tokens, origins, or credentials observed in review metadata.

## Named-risk inspection

| Risk | Result |
|---|---|
| New logging of credentials, tokens, tickets, or connection strings | **Not found.** No `Log` / `println` / Timber additions in diff. |
| Token persistence, paste field, or catalog save behavior change | **Not found.** Save/Test/accept handlers untouched; only compose order and visibility gates changed. |
| New exported component, Intent, or PendingIntent surface | **Not found.** Manifest and intent-security surfaces unchanged. |
| Global or release cleartext HTTP policy change | **Not found.** No manifest or network-security edits. |
| Shared-loopback warning moved below Save | **Not found.** `ExternalSshTunnelSetup` still composes warning and Test tunnel before the Save row; guide moves after Save. |
| Installation-changed auto-accept or duplicate/conflicting prompts | **Not found.** Empty-state duplicate block removed; `ConnectionRecoveryBanner` remains the sole installation-changed surface with Accept new server and Cancel. Test asserts exactly one “different Hermes installation” string. |
| Test tunnel mutates catalog or retains bootstrap token | **Not found.** `onTestTunnel` wiring unchanged; no graph path to credential install or catalog save. |
| Copy claims loopback is private/sandboxed or that this app restores SSH | **Not found.** Warning and guide copy unchanged in this diff. |

## Assessment

Pure UI layout correction. Security-sensitive flows (explicit installation-changed accept, shared-loopback disclosure before Save, non-mutating Test tunnel, no new exports or cleartext) are preserved. No secret-handling or transport regressions identified.
