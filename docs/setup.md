# Development Setup

## Host prerequisites

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

## Cleartext and local Hermes

Debug and release share the same fail-closed network security config. Direct connections require HTTPS. HTTP is allowed only for `127.0.0.1` or `[::1]` in External SSH tunnel mode. There is no debug exception for private, LAN, or Tailscale HTTP. Use `http://127.0.0.1:9119` (not `localhost`) when pointing HAM at a phone-local SSH forward.
