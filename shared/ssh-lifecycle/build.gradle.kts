plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Apple targets link only on macOS, and merely configuring them pulls the
// Kotlin/Native toolchain. Same gating as :shared:mercury-core — Android-only
// contributors and Linux CI skip them; override with
// -Pmercury.enableAppleTargets=true|false.
val appleTargetsEnabled =
  providers.gradleProperty("mercury.enableAppleTargets").map { it.toBoolean() }
    .orElse(providers.systemProperty("os.name").map { it.startsWith("Mac") })
    .getOrElse(false)

kotlin {
  jvmToolchain(17)

  androidLibrary {
    namespace = "com.unsupportedpastels.mercury.ssh"
    compileSdk = 37
    minSdk = 29
    withHostTestBuilder {}
  }

  if (appleTargetsEnabled) {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
      target.binaries.framework {
        baseName = "SshLifecycle"
        isStatic = true
      }
    }
  }

  sourceSets {
    // Deliberately dependency-free: the whole lifecycle layer is pure Kotlin
    // (no coroutines, no serialization, no crypto provider), so it compiles for
    // every target including iOS and stays testable without Android APIs.
    commonMain.dependencies {}

    // JSch is the transport for Android: the platform ships no `ssh` binary,
    // so the desktop's `ssh -M -N -f` / `ssh -O forward` CLI calls are replaced
    // by one Session + setPortForwardingL (see JschSshTransport).
    androidMain.dependencies {
      implementation(libs.jsch)
    }

    // Host (JVM) unit tests: plain JUnit 4 — assertions, argv/payload shape
    // checks, and integration cases that talk to a throwaway sshd when one is
    // available (they skip themselves otherwise).
    getByName("androidHostTest").dependencies {
      implementation(libs.junit)
      // The host tests reference JSch types directly (HostKeyRepository), so the
      // transport dependency must be visible to them as well.
      implementation(libs.jsch)
    }
  }
}