plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.unsupportedpastels.hermesandroid"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.unsupportedpastels.hermesandroid"
        minSdk = 29
        targetSdk = 36
        // versionCode is CI-driven so every Play upload is unique (Play rejects
        // duplicates). Release CI passes VERSION_CODE=${{ github.run_number }};
        // local builds fall back to 1.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Env-driven upload signing: the release keystore is only materialized in CI
    // from GitHub Secrets (decoded to a temp path). No keystore path -> the block
    // stays empty and the release build is produced unsigned, so contributors
    // without the key aren't broken and no signing material lives in the repo.
    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("UPLOAD_KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only attach the release signing config when CI supplied the upload
            // keystore; otherwise leave the build unsigned (never the debug key).
            if (System.getenv("UPLOAD_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Current AndroidX requires API 37/AGP 9.3.1; Gradle 9.7 currently breaks AGP's lint hint path.
        // API 36 remains the latest stable runtime target while API 37 is compile-only.
        // Keep all code/resource/security analysis and suppress only these two version-policy hints.
        disable += "AndroidGradlePluginVersion"
        disable += "OldTargetApi"
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.websockets)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.tink.android)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3.adaptive)
  implementation(libs.androidx.compose.material3.adaptive.navigation3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  screenshotTestImplementation(composeBom)
  screenshotTestImplementation(libs.screenshot.validation.api)
  screenshotTestImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: JUnit, coroutines, Robolectric, and Compose behavior
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Media playback (managed video artifacts)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
}
