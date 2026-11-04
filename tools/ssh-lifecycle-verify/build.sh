#!/usr/bin/env bash
# Offline compile + test for :shared:ssh-lifecycle — no Gradle, no Android SDK.
#
# Why this exists: the module lives in a KMP project whose Android target needs
# the Android SDK and whose Apple targets need macOS, so `./gradlew` cannot
# configure on an arbitrary Linux box. This script compiles the very same
# sources (commonMain + androidMain + androidHostTest) as plain JVM code and
# runs the tests with the JUnit platform — the same code, verified without the
# toolchain the app build requires.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LIB="$ROOT/tools/ssh-lifecycle-verify/lib"
OUT="$ROOT/tools/ssh-lifecycle-verify/out"
SRC="$ROOT/shared/ssh-lifecycle/src"

COMPILER="$(ls "$LIB"/kotlin-compiler-embeddable-*.jar | head -1)"
STDLIB="$(ls "$LIB"/kotlin-stdlib-*.jar | head -1)"
REFLECT="$(ls "$LIB"/kotlin-reflect-*.jar | head -1)"
SCRIPT_RT="$(ls "$LIB"/kotlin-script-runtime-*.jar | head -1)"
TROVE="$(ls "$LIB"/trove4j-*.jar | head -1)"
COROUTINES="$(ls "$LIB"/kotlinx-coroutines-core-jvm-*.jar | head -1)"
ANNOTATIONS="$(ls "$LIB"/annotations-*.jar | head -1)"
JUNIT_PLATFORM="$(ls "$LIB"/junit-platform-console-standalone-*.jar | head -1)"
JUNIT4="$(ls "$LIB"/junit-4*.jar | head -1)"
HAMCREST="$(ls "$LIB"/hamcrest-core-*.jar | head -1)"
JSCH="$(ls "$LIB"/jsch-*.jar | head -1)"

rm -rf "$OUT"; mkdir -p "$OUT"

echo "== compile (commonMain + androidMain + androidHostTest, as JVM) =="
java -cp "$COMPILER:$STDLIB:$COROUTINES:$ANNOTATIONS:$TROVE:$REFLECT:$SCRIPT_RT" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -jvm-target 17 -Xjdk-release=17 \
  -classpath "$STDLIB:$JSCH:$JUNIT4:$HAMCREST" \
  -d "$OUT" \
  $(find "$SRC/commonMain" "$SRC/androidMain" "$SRC/androidHostTest" -name '*.kt' | sort)

echo "== test =="
# Fixture wiring: without these the integration cases skip instead of running.
export HERMES_SSH_FIXTURE_HOST="${HERMES_SSH_FIXTURE_HOST:-127.0.0.1}"
export HERMES_SSH_FIXTURE_PORT="${HERMES_SSH_FIXTURE_PORT:-2222}"
export HERMES_SSH_FIXTURE_USER="${HERMES_SSH_FIXTURE_USER:-$(id -un)}"
export HERMES_SSH_FIXTURE_KEY="${HERMES_SSH_FIXTURE_KEY:-$HOME/.ssh/id_ed25519}"
export HERMES_FAKE_HERMES="${HERMES_FAKE_HERMES:-$ROOT/tools/ssh-lifecycle-verify/fixtures/fake-hermes}"

java -jar "$JUNIT_PLATFORM" execute \
  -cp "$OUT:$STDLIB:$JSCH:$JUNIT4:$HAMCREST" \
  --scan-classpath --details=summary --disable-banner \
  --include-engine=junit-vintage \
  --reports-dir="$ROOT/tools/ssh-lifecycle-verify/reports"