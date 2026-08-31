# Task 8 — Screenshot candidates (compact-layout regen)

**Status:** candidates collected for owner visual review. Feature not complete. Device matrix not claimed.

**Branch:** `docs/external-ssh-tunnel-session-auth`  
**HEAD:** `53c2deb` (includes `99dcbb2` compact tunnel setup above the fold)  
**Not pushed.** Goldens were not updated and were not committed.

## Git

Working tree had no dirty screenshot goldens before or after the run. `app/src/screenshotTestDebug/reference/` was left untouched. Nothing was copied into `src/` screenshot reference dirs.

## Command (expected fail)

```
./gradlew --no-daemon validateDebugScreenshotTest
```

Result: **27 tests, 12 failed** (9 errors / 3 failures / 0 skipped). Success rate 55%. Duration 2.530s. BUILD FAILED.

NativeOAuthTest was not run and was not chased.

## Report

`app/build/reports/screenshotTest/preview/debug/index.html`

Build rendered dir (does not survive `clean`):

`app/build/outputs/screenshotTest-results/preview/debug/rendered/com/unsupportedpastels/hermesandroid/ui/HermesAppScreenshotTestKt/`

Build diffs dir:

`app/build/outputs/screenshotTest-results/preview/debug/diffs/com/unsupportedpastels/hermesandroid/ui/HermesAppScreenshotTestKt/`

Durable copy for review (survives `clean`):

`.superpowers/sdd/screenshot-candidates/`

See `.superpowers/sdd/screenshot-candidates/README.md` for per-image approval notes.

## Missing vs mismatched

| Kind | Count | Tests |
|---|---|---|
| Missing goldens (`ScreenshotImageNotFoundException`) | **9** | Compact / medium / expanded External SSH tunnel setup; compact / medium / expanded tunnel unavailable; compact / medium / expanded installation changed |
| Mismatched goldens (`ImageComparisonAssertionError`) | **3** | Compact server settings; compact active session workspace; compact dark markdown table |

## Copied candidates

Rendered (12):

- `screenshot-candidates/rendered/HermesExternalSshTunnelSetupScreenshot_Compact external SSH tunnel setup_f8fe67cd_0.png`
- `screenshot-candidates/rendered/HermesExternalSshTunnelSetupScreenshot_Medium external SSH tunnel setup_0f6b6e1c_0.png`
- `screenshot-candidates/rendered/HermesExternalSshTunnelSetupScreenshot_Expanded external SSH tunnel setup_51d57546_0.png`
- `screenshot-candidates/rendered/HermesTunnelUnavailableScreenshot_Compact tunnel unavailable_a900687a_0.png`
- `screenshot-candidates/rendered/HermesTunnelUnavailableScreenshot_Medium tunnel unavailable_e18067e0_0.png`
- `screenshot-candidates/rendered/HermesTunnelUnavailableScreenshot_Expanded tunnel unavailable_89bc6d11_0.png`
- `screenshot-candidates/rendered/HermesInstallationChangedScreenshot_Compact installation changed_47874a80_0.png`
- `screenshot-candidates/rendered/HermesInstallationChangedScreenshot_Medium installation changed_ad1f3b7e_0.png`
- `screenshot-candidates/rendered/HermesInstallationChangedScreenshot_Expanded installation changed_dc6e37c7_0.png`
- `screenshot-candidates/rendered/HermesServerDialogScreenshot_Compact server settings_9863892e_0.png`
- `screenshot-candidates/rendered/HermesActiveSessionScreenshot_Compact active session workspace_5d7be809_0.png`
- `screenshot-candidates/rendered/HermesMarkdownTableScreenshot_Compact dark markdown table_a7412d16_0.png`

Diffs vs existing goldens (3):

- `screenshot-candidates/diffs/HermesServerDialogScreenshot_Compact server settings_9863892e_0.png`
- `screenshot-candidates/diffs/HermesActiveSessionScreenshot_Compact active session workspace_5d7be809_0.png`
- `screenshot-candidates/diffs/HermesMarkdownTableScreenshot_Compact dark markdown table_a7412d16_0.png`

## Compact setup candidate

Compact tunnel setup (400×900) **now shows** the shared-loopback warning, Test tunnel, and Save on the first screen. Checklist starts after Save and is partially cut off at the bottom. Owner still must approve before any golden is written.

## Owner visual review cheat-sheet

**Reviewed:** 2026-08-31 (agent visual pass; no golden writes).  
**Verdict key:** looks correct = yes for that image; suspicious = inspect before yes; do not approve = no.

### Missing goldens (9) — approve as new references?

| Window | Screen | Verdict |
|---|---|---|
| Compact | External SSH tunnel setup | **looks correct** — warning, Test tunnel, and Save visible above the fold; checklist may be partially cut off (expected). |
| Medium | External SSH tunnel setup | **looks correct** — full tunnel form, experimental warning, and actions read cleanly. |
| Expanded | External SSH tunnel setup | **suspicious** — top of form only; Test tunnel / Save / checklist not visible in frame. |
| Compact | Tunnel unavailable | **looks correct** — error banner, Retry, and reconnecting empty state. |
| Medium | Tunnel unavailable | **looks correct** — list/detail split with same recovery copy. |
| Expanded | Tunnel unavailable | **looks correct** — navigation rail plus error and placeholder pane. |
| Compact | Installation changed | **looks correct** — warning, Accept new server, and Cancel on one screen. |
| Medium | Installation changed | **do not approve** — Cancel label wraps vertically; duplicate warning blocks on the left. |
| Expanded | Installation changed | **suspicious** — dialog plus a second duplicate message block and odd Cancel artifact. |

### Compact mismatches (3) — replace existing goldens?

| Window | Screen | Verdict |
|---|---|---|
| Compact | Server settings | **suspicious** — rendered UI is coherent, but diff shows layout shift and a moved/new operational-overview card vs the stored golden. |
| Compact | Active session workspace | **looks correct** — rendered session workspace is complete; diff image shows no highlighted delta (likely renderer flake). |
| Compact | Dark markdown table | **looks correct** — table, swipe hint, and composer render cleanly; diff shows no highlighted delta. |

### Owner one-liner

**Approve some, not all.** Safe yes on compact/medium tunnel setup, all tunnel-unavailable sizes, compact installation changed, and the two compact flake mismatches (active session, markdown table). Hold expanded tunnel setup, both installation-changed adaptive sizes (especially medium), and compact server settings until layout issues or intentional UI deltas are confirmed.

## Not done

- Goldens not updated.
- Screenshot gate not green.
- Device matrix not run.
- Feature not marked complete.
