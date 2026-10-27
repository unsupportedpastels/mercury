#!/usr/bin/env bash
# Re-download the jars the offline compile/test run needs (they are gitignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LIB="$ROOT/tools/ssh-lifecycle-verify/lib"
M=https://repo1.maven.org/maven2
mkdir -p "$LIB"

fetch() { # url dest
  if [ -f "$LIB/$2" ]; then echo "  have  $2"; return; fi
  curl -sS -L --max-time 180 -o "$LIB/$2" "$1"
  printf "  got   %-46s %s\n" "$2" "$(du -h "$LIB/$2" | cut -f1)"
}

# Kotlin compiler + the classpath it itself needs (trove4j and a matching
# kotlin-reflect are the two that bite: the compiler dies with
# ClassNotFoundException: gnu.trove.TObjectHashingStrategy without them).
fetch "$M/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.0/kotlin-compiler-embeddable-2.1.0.jar" kotlin-compiler-embeddable-2.1.0.jar
fetch "$M/org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar" kotlin-stdlib-2.1.0.jar
fetch "$M/org/jetbrains/kotlin/kotlin-reflect/2.1.0/kotlin-reflect-2.1.0.jar" kotlin-reflect-2.1.0.jar
fetch "$M/org/jetbrains/kotlin/kotlin-script-runtime/2.1.0/kotlin-script-runtime-2.1.0.jar" kotlin-script-runtime-2.1.0.jar
fetch "$M/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" trove4j-1.0.20200330.jar
fetch "$M/org/jetbrains/annotations/23.0.0/annotations-23.0.0.jar" annotations-23.0.0.jar
fetch "$M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar" kotlinx-coroutines-core-jvm-1.6.4.jar
# Test runtime: the platform console (which carries the JUnit 4 vintage engine),
# plus JUnit 4 and its hamcrest companion.
fetch "$M/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar" junit-platform-console-standalone-1.11.4.jar
fetch "$M/junit/junit/4.13.2/junit-4.13.2.jar" junit-4.13.2.jar
fetch "$M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" hamcrest-core-1.3.jar
fetch "$M/com/github/mwiede/jsch/0.2.20/jsch-0.2.20.jar" jsch-0.2.20.jar

echo "done — now run: bash tools/ssh-lifecycle-verify/build.sh"