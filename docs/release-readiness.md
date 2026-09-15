# Release checklist

Mercury releases the same version on Android and iOS. The current version is
`0.2.5`: `versionName` in `app/build.gradle.kts` and `MARKETING_VERSION` in
`ios/project.yml` must always agree. Nothing here changes Hermes itself;
optional Relay features may require coordinated Mercury host-plugin and router releases.

## Before cutting either platform

1. `main` is green: `Android CI` (`.github/workflows/android-ci.yml`) and, for
   any change under `ios/`, `shared/`, or the Gradle files, `iOS CI`
   (`.github/workflows/ios-ci.yml`).
2. The vendored Relay protocol vectors match the pinned public plugin release
   (`tools/check-relay-vectors.sh`; CI runs it on every PR).
3. `PRIVACY.md` and `SECURITY.md` still describe what the build does — new
   permissions, storage, or network surfaces need a matching paragraph.
4. Bump the version in **both** files in one PR:
   - `app/build.gradle.kts` → `versionName = "X.Y.Z"` (`versionCode` is
     CI-driven from the workflow run number; do not hand-edit it).
   - `ios/project.yml` → `MARKETING_VERSION: "X.Y.Z"` under `settings.base`
     (bump `CURRENT_PROJECT_VERSION` too if the same marketing version is
     re-archived).

## Android

Gates (run locally, also enforced by CI):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest
./gradlew :shared:mercury-core:check
```

Release build:

- Merging the version bump to `main` runs `.github/workflows/release.yml`,
  which re-runs the unit, lint, assemble, and shared-core gates, builds a
  signed APK + AAB with the `UPLOAD_KEYSTORE_*` / `UPLOAD_KEY_*` repository
  secrets, verifies the APK signature with `apksigner`, and publishes the APK
  as GitHub release `v<versionName>` (the AAB is attached as a workflow
  artifact).
- The Play upload is manual: run the `Release` workflow with
  `publish_to_play` checked once the `PLAY_SERVICE_ACCOUNT_JSON` secret
  exists. It targets the internal track for package
  `com.unsupportedpastels.hermesandroid` (kept for Play continuity; the iOS
  bundle is `com.unsupportedpastels.mercury`).
- Release builds are not minified (`isMinifyEnabled = false`); enabling R8
  requires reviewing `app/proguard-rules.pro` first.
- Device check before publishing: install the signed APK on a phone and, if
  available, a foldable; connect, send a prompt, background the app during a
  turn, and confirm the completion notification opens the right session.

## iOS

Gates (run on a Mac with Xcode; the same command runs in `iOS CI`):

```bash
./gradlew :shared:mercury-core:check
cd ios && xcodegen generate && xcodebuild -project Mercury.xcodeproj -scheme Mercury \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build test
```

Release build:

- There is no automated App Store or TestFlight upload pipeline; iOS is archived from source.
  To produce a distributable build, regenerate the project with `xcodegen
  generate`, open `ios/Mercury.xcodeproj` in Xcode with your signing team, and
  archive the `Mercury` scheme (Product → Archive, or
  `xcodebuild -scheme Mercury -destination 'generic/platform=iOS' archive`).
  The archive embeds the `MercuryShare` and `MercuryNotificationService`
  extensions, which inherit the same version from `settings.base`. Use App
  Store Connect distribution provisioning for all three executable bundles;
  development profiles used for direct device installs are not sufficient.
- Static invariants that must hold in a TestFlight archive: the app's signed
  `aps-environment` is `production`; the notification extension uses the same
  production environment; App Group and separate preview Keychain access are
  present on the correct bundles; version/build numbers match. Visible APNs
  alerts do not require the `remote-notification` background mode.
- Verify production generic and encrypted-preview capabilities on the paired
  Mercury host/router before claiming TestFlight notifications work. Never
  register a production token with sandbox APNs. Preserve direct-mode local
  fallback and the user's notification/preview preferences.
- Include reviewed required-reason privacy manifests for the executable
  bundles that use covered APIs. Validate the archive's privacy report and
  complete App Store Connect export-compliance answers from the actual crypto
  inventory; do not infer exemption solely from the use of standard algorithms.
- Device check before distributing: fresh install shows no notification prompt
  at launch; a long turn completed while backgrounded within the grace window
  posts one local notification; the share extension stages an image into the
  composer without sending.
- Upload to App Store Connect and verify the processed build before assigning
  internal TestFlight testers. Test the actual TestFlight-installed build,
  including production push, notification taps, session reopen, pagination,
  attachments, and Share extension behavior. External Beta App Review and App
  Store review submissions require separate publisher approval.

## After the release

- Confirm the GitHub release page lists the APK and that
  `apksigner verify --verbose` passes on the downloaded file.
- Update the placeholder version in `.github/ISSUE_TEMPLATE/bug_report.yml`.
