#!/usr/bin/env bash
# Build the debug APK inside a ready-made Android build image.
#
# Why: the host toolchain here needs a JDK, the Android SDK and a matching
# compileSdk; the image supplies all three, and the host's Gradle wrapper dist
# + dependency cache + SDK are bind-mounted so nothing is re-downloaded.
#
# Usage: bash tools/android-build/build-apk.sh [image]
#   default image: thyrlian/android-sdk:latest
#   fallback:      mingc/android-build-box:latest
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="${1:-thyrlian/android-sdk:latest}"
GRADLE_HOME="${GRADLE_HOME:-$HOME/.gradle}"
SDK_HOST="${SDK_HOST:-$HOME/workspaces/android-sdk}"
OUT="$ROOT/app/build/outputs/apk/debug"

# Packages the app's build files ask for:
#   compileSdk 37 (shipped as android-37.1: the SDK now versions minors)
#   build-tools 37.0.0
SDK_PACKAGES='"platform-tools" "platforms;android-37.1" "build-tools;37.0.0"'

echo "== image: $IMAGE =="
mkdir -p "$GRADLE_HOME" "$SDK_HOST"
if [ ! -d "$SDK_HOST/cmdline-tools/latest/bin" ]; then
  SDK_VOLUME=""
  echo "   (no host SDK at $SDK_HOST — the image's own SDK will be used)"
else
  SDK_VOLUME="-v $SDK_HOST:/opt/android-sdk"
fi

docker run --rm \
  ${SDK_VOLUME:+$SDK_VOLUME} \
  -v "$ROOT:/work" \
  -v "$GRADLE_HOME:/root/.gradle" \
  -w /work \
  -e ANDROID_HOME=/opt/android-sdk \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e GRADLE_USER_HOME=/root/.gradle \
  "$IMAGE" \
  bash -lc "
    set -e
    if [ -d /opt/android-sdk/cmdline-tools/latest/bin ]; then
      export PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:\$PATH
    fi
    yes | sdkmanager --licenses >/dev/null 2>&1 || true
    sdkmanager $SDK_PACKAGES >/dev/null
    java -version 2>&1 | head -1
    ./gradlew :app:assembleDebug --no-daemon --console=plain
  "

echo "== artifacts =="
ls -lh "$OUT"/*.apk 2>/dev/null || { echo "no APK produced"; exit 1; }