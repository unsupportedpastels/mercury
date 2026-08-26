# Testing Strategy

## Fast local gate

```bash
./gradlew testDebugUnitTest
```

Use local tests for reducers, origin normalization/persistence, server setup UI, protocol parsing, observer/controller policy, request correlation, refresh classification, concurrent controller generations, reconnect reconciliation, notification routing, working-state policy, and state restoration models. Prefer explicit fakes over mocks.

## Build and static gate

```bash
./gradlew lintDebug assembleDebug
```

The complete pre-handoff gate is:

```bash
git diff --check && \
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest
```

## Adaptive UI matrix

Every screen-level adaptive screenshot suite must cover compact, medium, and expanded widths and representative heights, including 400x500, 610x1000, and 900x1000 dp. Add dark theme and 1.5 font-scale variants for core screens.

Behavior checks must cover:

- compact one-pane list -> detail -> back;
- configure and edit a canonical HTTPS server origin, including inline rejection of cleartext and credential-bearing input;
- unfolded list/detail selection;
- fold/unfold with selected session and composer draft preserved;
- resize while streaming and while blocking input is pending;
- two concurrent Mercury-started turns with independent completion and Stop behavior;
- Back from a running session, opening another session, and reopening the first without losing its partial output or amber working state;
- idle attached controllers never appearing as active work;
- cold- and warm-notification taps opening the exact durable session;
- reconnect replacing authoritative inflight text/tool state without duplication;
- origin/profile changes preventing stale refresh, metadata, socket, and controller results from publishing;
- edge-to-edge system bars and IME visibility;
- predictive back;
- keyboard focus and navigation;
- split screen, freeform, and DeX-sized windows.

## Device gate

Use a disposable foldable emulator for instrumentation and process-restoration tests. Before milestone completion, install and exercise the debug APK on the standard/non-Ultra Galaxy Z Fold 8 with a data-preserving install. Capture the live layout tree and settled screenshots for cover, unfolded portrait, and unfolded landscape. Do not run uninstalling/clearing instrumentation against the user's authenticated primary installation. A missing or locked physical device blocks only real-device verification, not local development.

Do not regenerate screenshot references without inspecting the visual diff.

## iOS notifications and Live Activities

Hermetic suites (`MercuryTests`, scheme `Mercury`) cover the pure notification/Live Activity brains: `RunActivityPolicyTests`, `RunActivityReducerTests`, `RunActivityCoordinatorTests`, `RunActivityReconcilerTests`, `NotificationPreferencesTests`, and `MercuryDeepLinkTests`. All fakes; no ActivityKit, network, or timers.

Device procedure (physical iPhone, signed build via `-allowProvisioningUpdates`):

1. Fresh install: launch must show **no** notification permission prompt. Settings → Notifications shows "Not requested"; Enable triggers the single system prompt.
2. Enable Live Activities in Mercury settings (and confirm iOS Settings → Mercury → Live Activities is on). Start a long tool-using turn, lock the phone: the Lock Screen activity must show only the session title + generic status, no prompt/command/path text. Dynamic Island compact/minimal/expanded render on supported hardware.
3. Background mid-turn past the ~20s grace window: the activity flips to "Reconnecting — last update…" (stale), never a fabricated completion. Reopen: it reconciles to the real outcome without duplicate banners.
4. Force-kill during a run, relaunch: a recent orphan resolves from the session list (complete when the message count advanced, otherwise stale/status-unknown); an orphan for a removed server ends as status-unknown.
5. Tap the activity/notification: it must open that exact durable session, switching server/profile first when needed and holding the route through sign-in.
6. Response excerpts: verify absent by default and present only after the explicit opt-in toggle.

Static release invariants: `NSSupportsLiveActivities` present; **no** `aps-environment` entitlement, `remote-notification` background mode, `registerForRemoteNotifications`, or `pushType: .token` anywhere in the iOS tree.
