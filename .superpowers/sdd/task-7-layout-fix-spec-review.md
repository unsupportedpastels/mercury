# Task 7 layout-fix follow-up — independent spec review

**Reviewer:** independent (did not implement). Treat implementer report as unverified.
**Range:** commit `ad5d7de` — screenshot-review layout bugs for tunnel setup and installation changed.
**Prior reviews:** `.superpowers/sdd/task-7-compact-fold-review.md` (compact 400×900 above-the-fold follow-up).
**Gate:** task-scoped spec conformance for this layout fix only. Screenshot goldens not updated (correct). Suite spot-checked, not full gate.
**Checkout:** review markdown only.

Sources: `docs/EXTERNAL_SSH_TUNNEL_QUESTIONS_ANSWERS.md`, `docs/design/external-ssh-tunnel-session-auth.md`, `docs/plan/task-7-ui-and-docs.md`, `docs/plan/task-8-release-verification.md`, `AGENTS.md`, `.agents/skills/adaptive/SKILL.md`, commit `ad5d7de` diff, working-tree sources.

Graphify used first (`ExternalSshTunnelSetup`, `ConnectionRecoveryBanner`, `InstallationChanged`, `SessionListScreen`).

No uncommitted changes to the reviewed Kotlin files; `ad5d7de` is the full implementation surface.

---

## Verdict: **PASS**

---

### Spec compliance (targeted fixes)

#### 1. Installation-changed empty-state duplicate — ✅

- **Spec:** Q&A §9 and Task 7 require explicit **Accept new server** / **Cancel** when `install_id` changes; recovery copy must be distinct and actionable, not duplicated across surfaces.
- **Evidence:** `SessionListScreen` no longer special-cases `TunnelConnectionFailure.InstallationChanged` in the list-detail placeholder. The detail pane falls back to generic reconnecting copy while `ConnectionRecoveryBanner` remains the single authoritative surface via `tunnelRecoveryCopy` (`AcceptNewServer` + `Cancel`).
- **Test:** `mediumInstallationChangedShowsSingleRecoveryBanner` (`w610dp-h900dp`) asserts the recovery banner, both actions, and exactly one node matching `"different Hermes installation"`.

#### 2. Cancel wrapping on narrow panes — ✅

- **Spec:** Task 7 requires correct layout at compact, medium, and expanded widths; adaptive decisions from window metrics, not device names.
- **Evidence:** `ConnectionRecoveryBanner` replaces a fixed `Row` with `FlowRow` (`fillMaxWidth`, horizontal and vertical spacing) so **Accept new server** and **Cancel** wrap to a new row instead of breaking label glyphs vertically on medium installation-changed width.
- **Copy unchanged:** actions still match `TunnelRecoveryCopy` for `InstallationChanged`; no new “restore the tunnel” implication.

#### 3. Expanded / short-viewport tunnel setup — Save and actions above checklist — ✅

- **Spec:** Task 7 requires Experimental label, shared-loopback warning, and **Test tunnel** before Save; checklist remains in the setup flow. Task 8 screenshot review flagged expanded 900×675 with actions below the fold.
- **Evidence:**
  - `shortViewport` (`screenHeightDp < 800`) extends compact tunnel layout so expanded-but-short windows prioritize the form.
  - Duplicate `ExternalSshTunnelSetupGuide()` removed from `ExternalSshTunnelSetup`; guide renders once after Save/Cancel in `ServerSettingsScreen`, preserving warning → Test → Save order in `ExternalSshTunnelSetup`.
  - Server catalog chrome is suppressed only when `compactTunnel && shortViewport` to reclaim vertical space on short viewports.
- **Test:** `expandedTunnelSetupShowsWarningTestAndSaveWithoutScrolling` (`w900dp-h675dp`) asserts warning, Test tunnel, Save, and Cancel are displayed and bounds prove warning/test above Save and Save above checklist.

---

### Strengths

- Fixes map directly to owner screenshot-review failures without weakening security copy (loopback warning still before Save; no private/sandbox language).
- Regression tests pin the three reported defects rather than relying on screenshot candidates alone.
- Goldens were not auto-updated; candidate renders in `.superpowers/sdd/screenshot-candidates/rendered/` remain for owner sign-off per Task 7/8 policy.

---

### Issues

#### Critical (must fix)

None.

#### Important (should fix)

None.

#### Minor (nice to have)

1. **Checklist position on medium/expanded tall viewports** — guide now always follows Save for External SSH tunnel mode, whereas medium/expanded tall previously showed it between Test and Save inside `ExternalSshTunnelSetup`. Still in the setup flow; warning and Test remain before Save. Owner visual review only.
2. **Expanded layout test name** — `expandedTunnelSetupShowsWarningTestAndSaveWithoutScrolling` uses `performScrollTo` for some nodes while asserting vertical order; name is slightly misleading. Ordering assertions are still meaningful.
3. **Installation-changed placeholder copy** — detail placeholder shows generic “Reconnecting” while the banner carries installation-specific text. Intentional de-duplication; not a second failure surface.

---

### Claim check

| Claim | Verdict |
|---|---|
| Stop duplicating installation-changed copy in empty state | Holds — placeholder branch removed; banner is sole classified surface |
| Let recovery actions wrap on narrow panes | Holds — `FlowRow` in `ConnectionRecoveryBanner` |
| Keep tunnel Save/actions above checklist on short expanded viewport | Holds — `shortViewport` reorder + test bounds |
| Warning / Experimental / Test tunnel before Save (Task 7) | Holds on all widths including `w900dp-h675dp` |
| Accept new server + Cancel for installation changed (Q&A §9) | Holds in `TunnelRecoveryCopy` and Compose test |
| Screenshot goldens not updated | Holds |

---

### Assessment

**Spec:** ✅ **PASS**

**Critical:** 0

**Important:** 0

This commit closes the three screenshot-review layout defects without violating Task 7 product rules or the authoritative Q&A installation-changed contract. Remaining items are owner golden approval and minor test-naming / tall-viewport checklist ordering notes, not spec blockers.
