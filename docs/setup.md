# Development Setup

Android and the shared core build on Linux or macOS. The iOS app builds only on macOS with Xcode; see [macOS and Xcode](#macos-and-xcode) at the end.

## Host prerequisites (Linux, Android)

- Linux x86_64
- JDK 17
- Android SDK rooted at `$ANDROID_HOME`
- KVM acceleration for x86_64 emulators

The bootstrap host now uses Android SDK command-line tools 22.0. If `/dev/kvm` is group-owned by `kvm`, add the development account once and sign out/in:

```bash
sudo usermod -aG kvm "$USER"
```

## Official Android CLI

Install or update the official CLI:

```bash
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
android --no-metrics update
android --version
```

The project-local official skills live under `.agents/skills/`:

- `android-cli`
- `adaptive`
- `navigation-3`
- `edge-to-edge`
- `testing-setup`
- `android-intent-security`
- `agp-9-upgrade`

Refresh one by reinstalling the current published copy:

```bash
android --no-metrics skills add adaptive --project=.
```

## SDK and emulators

Required SDK packages:

```bash
android --no-metrics sdk install \
  platform-tools \
  emulator \
  platforms/android-37 \
  build-tools/37.0.0 \
  system-images/android-37.0/google_apis/x86_64 \
  system-images/android-36/google_apis_playstore/x86_64
```

Create general resizable profiles:

```bash
android --no-metrics emulator create medium_phone
android --no-metrics emulator create medium_tablet
```

The Android CLI currently exposes phone/tablet/desktop presets but not its SDK's 7.6-inch foldable hardware profile. Create the foldable AVD with the SDK manager's device catalog:

```bash
avdmanager create avd \
  -n Hermes_ZFold8_Adaptive_API36 \
  -k 'system-images;android-36;google_apis_playstore;x86_64' \
  -d '7.6in Foldable' \
  --force
```

This is a generic 7.6-inch fold-in profile with a separating hinge and an 884 x 2208 cover region. App code must never branch on that AVD name or a Samsung model name.

For a headless boot after joining the `kvm` group:

```bash
$ANDROID_HOME/emulator/emulator \
  -avd Hermes_ZFold8_Adaptive_API36 \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader
```

The API-37 Google APIs image installed during bootstrap currently crashes its system compositor in headless software-rendering mode. Current AndroidX dependencies require API 37 at compile time, but API 36 remains the stable target and verified runtime-test image.

## Verification

```bash
./gradlew clean testDebugUnitTest validateDebugScreenshotTest lintDebug assembleDebug
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W --user 0 \
  -n com.unsupportedpastels.hermesandroid/.MainActivity
```

Use `adb shell cmd device_state state 1|2|3` for closed, half-open, and open posture signals. If the image does not switch to the hardware profile's cover region, exercise the exact cover width in place with `adb shell wm size 884x2208`, then restore it with `adb shell wm size reset`.

## macOS and Xcode

The iOS app (`ios/`) requires a Mac. The same machine can also run every Android and shared-core gate above; the Android SDK path goes in `local.properties` as on Linux.

Install:

```bash
xcode-select --install            # or install Xcode from the App Store and open it once
brew install xcodegen             # generates ios/Mercury.xcodeproj from ios/project.yml
brew install openjdk@17           # the Xcode pre-build phase runs ./gradlew to link MercuryCore
```

Homebrew's `openjdk@17` is keg-only; follow its caveat so `java` is on `PATH` (or export `JAVA_HOME`) in the shell that launches Xcode or `xcodebuild`:

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

The `Mercury` target's pre-build script checks `command -v java` / `JAVA_HOME` and fails with a clear message if neither is available. On macOS the shared module configures its Apple targets automatically (`-Pmercury.enableAppleTargets=false` skips them for an Android-only build).

Generate, build, and test:

```bash
cd ios
xcodegen generate
xcodebuild -project Mercury.xcodeproj -scheme Mercury \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build test
```

`xcrun simctl list devices available` lists the simulators your Xcode ships; substitute another iPhone if `iPhone 17 Pro` is missing. Open `Mercury.xcodeproj` in Xcode and select your signing team to run on a physical device. The generated project is gitignored; rerun `xcodegen generate` after editing `project.yml`.
