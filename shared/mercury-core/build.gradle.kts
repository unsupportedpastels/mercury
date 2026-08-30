plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Apple targets link only on macOS, and merely configuring them pulls the
// Kotlin/Native toolchain. Android-only contributors and Linux CI skip them;
// override with -Pmercury.enableAppleTargets=true|false.
val appleTargetsEnabled =
  providers.gradleProperty("mercury.enableAppleTargets").map { it.toBoolean() }
    .getOrElse(System.getProperty("os.name").startsWith("Mac"))

kotlin {
  jvmToolchain(17)

  androidLibrary {
    namespace = "com.unsupportedpastels.mercury.core"
    compileSdk = 37
    minSdk = 29
    withHostTestBuilder {}
  }

  if (appleTargetsEnabled) {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
      target.binaries.framework {
        baseName = "MercuryCore"
        // Static keeps extension consumers (MercuryShare) simple: no embed
        // step, no duplicated dylib across the app group's processes.
        isStatic = true
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
