package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesEndpointProtocolTest {
    @Test
    fun junkAndForeignServiceAreNotHermesAndNotTunnelUnavailable() {
        listOf(
            "",
            "<html>nginx</html>",
            "ok",
            "{}",
            """{"status":"ok"}""",
            """{"version":"1.0"}""",
            """{"auth_required":"yes"}""",
        ).forEach { body ->
            val rejected = parseHermesStatusBody(body) as HermesStatusProbe.Rejected
            assertEquals(body, TunnelConnectionFailure.NotHermesEndpoint, rejected.failure)
            assertTrue(rejected.message.isNotBlank())
            assertFalse(rejected.message.contains("unavailable", ignoreCase = true))
            assertFalse(rejected.message.contains("identity", ignoreCase = true))
            assertFalse(rejected.message.contains("authentication", ignoreCase = true))
        }
    }

    @Test
    fun unknownOlderVersionIsProtocolIncompatibleWithNoFallback() {
        listOf(
            """{"version":"0.20.3","auth_required":false}""",
            """{"version":"0.19.9","auth_required":false,"auth_flows":[]}""",
            """{"version":"0.20.4-pre","auth_required":false}""",
            """{"auth_required":false}""",
        ).forEach { body ->
            val rejected = parseHermesStatusBody(body) as HermesStatusProbe.Rejected
            assertEquals(body, TunnelConnectionFailure.ProtocolIncompatible, rejected.failure)
            assertTrue(rejected.message.contains("0.20.4"))
            assertFalse(rejected.message.contains("identity", ignoreCase = true))
        }
    }

    @Test
    fun supportedStatusParsesVersionInstallIdAndReleaseDate() {
        val accepted = parseHermesStatusBody(
            """{"version":"0.20.4","release_date":"2026.8.18","auth_required":false,"auth_flows":[],"install_id":"install-a"}""",
        ) as HermesStatusProbe.Accepted
        assertEquals("0.20.4", accepted.status.version)
        assertEquals("2026.8.18", accepted.status.releaseDate)
        assertEquals("install-a", accepted.status.installId)
        assertFalse(accepted.status.authRequired)
        assertEquals(
            "0.20.6",
            (parseHermesStatusBody(
                """{"version":"0.20.6","auth_required":true,"auth_flows":["native_pkce"]}""",
            ) as HermesStatusProbe.Accepted).status.version,
        )
    }

    @Test
    fun absentInstallIdIsProtocolValidationOnly() {
        val accepted = parseHermesStatusBody(
            """{"version":"0.20.4","auth_required":false}""",
        ) as HermesStatusProbe.Accepted
        assertNull(accepted.status.installId)
        assertEquals(
            InstallationContinuity.Unchanged,
            evaluateInstallationContinuity(lastSeenInstallId = "previous", observedInstallId = null),
        )
        assertEquals(
            InstallationContinuity.Unchanged,
            evaluateInstallationContinuity(lastSeenInstallId = null, observedInstallId = null),
        )
    }

    @Test
    fun installIdChangeRequiresAcceptanceAndDoesNotClaimIdentityVerification() {
        val changed = evaluateInstallationContinuity(
            lastSeenInstallId = "install-a",
            observedInstallId = "install-b",
        ) as InstallationContinuity.Changed
        assertEquals("install-a", changed.previousInstallId)
        assertEquals("install-b", changed.observedInstallId)
        assertFalse(INSTALLATION_CHANGED_MESSAGE.contains("identity", ignoreCase = true))
        assertFalse(INSTALLATION_CHANGED_MESSAGE.contains("verif", ignoreCase = true))
        assertTrue(INSTALLATION_CHANGED_MESSAGE.contains("different Hermes installation"))
        assertEquals(
            InstallationContinuity.Unchanged,
            evaluateInstallationContinuity(lastSeenInstallId = "install-a", observedInstallId = "install-a"),
        )
        assertEquals(
            InstallationContinuity.Unchanged,
            evaluateInstallationContinuity(lastSeenInstallId = null, observedInstallId = "install-a"),
        )
    }
}
