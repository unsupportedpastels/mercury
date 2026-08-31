package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.unsupportedpastels.hermesandroid.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergedManifestCleartextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun mergedManifestDoesNotPermitGlobalCleartext() {
        val flags = context.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        val merged = File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        assertTrue(merged.isFile)
        val text = merged.readText()
        assertFalse(text.contains("usesCleartextTraffic=\"true\""))
        assertTrue(text.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }

    @Test
    fun sourceAndMergedManifestsRejectGlobalCleartext() {
        val sourceManifest = File("src/main/AndroidManifest.xml").readText()
        val sourceConfig = File("src/main/res/xml/network_security_config.xml").readText()
        assertFalse(sourceManifest.contains("usesCleartextTraffic=\"true\""))
        assertTrue(sourceManifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(sourceConfig.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(sourceConfig.contains("127.0.0.1"))
        assertFalse(sourceConfig.contains("10.0.1.2"))
        assertFalse(sourceConfig.contains("100.64."))
        assertFalse(sourceConfig.contains("tailscale"))

        val merged = File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        if (merged.isFile) {
            val text = merged.readText()
            assertFalse(text.contains("usesCleartextTraffic=\"true\""))
            assertTrue(text.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        }
    }

    @Test
    fun noReleaseSourceOverlayRestoresGlobalCleartext() {
        assertFalse(File("src/release/AndroidManifest.xml").isFile)
        assertFalse(File("src/release/res/xml/network_security_config.xml").isFile)
    }

    @Test
    fun allPresentMergedManifestsRejectGlobalCleartext() {
        val mergedRoots = listOf(
            File("build/intermediates/merged_manifests"),
            File("build/intermediates/merged_manifest"),
        )
        val merged = mergedRoots
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.name == "AndroidManifest.xml" }
                    .toList()
            }
        assertTrue(
            "expected at least one merged AndroidManifest.xml under build/intermediates",
            merged.isNotEmpty(),
        )
        merged.forEach { file ->
            val text = file.readText()
            assertFalse(
                "${file.path} must not restore global cleartext",
                text.contains("usesCleartextTraffic=\"true\""),
            )
            assertTrue(
                "${file.path} must keep the shared network security config",
                text.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
            )
        }
    }

    @Test
    fun mergedManifestStillExportsOnlyTheLauncherActivityFromThisApp() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES,
        )
        val exported = packageInfo.activities.orEmpty()
            .filter { it.exported && it.name.startsWith("com.unsupportedpastels.hermesandroid") }
            .map { it.name }
        assertEquals(listOf(MainActivity::class.java.name), exported)
    }
}
