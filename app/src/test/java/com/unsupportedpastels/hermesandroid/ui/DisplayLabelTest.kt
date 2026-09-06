package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.connection.CloudAgent
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** Server and cloud-agent rows show hostnames unless the user set a label (iOS parity). */
class DisplayLabelTest {
    @Test
    fun serverEntryShowsHostnameUnlessLabelled() {
        val plain = ServerCatalogEntry(ServerOrigin.parse("https://hermes.example.com:8443"))
        assertEquals("hermes.example.com", plain.displayLabel)
        val labelled = ServerCatalogEntry(ServerOrigin.parse("https://hermes.example.com"), label = "Home box")
        assertEquals("Home box", labelled.displayLabel)
    }

    @Test
    fun cloudAgentShowsDashboardHostAndFallsBackToNameWhileProvisioning() {
        val ready = CloudAgent("a", "Atlas", "active", "https://atlas.hermes.cloud/dashboard", "active")
        assertEquals("atlas.hermes.cloud", cloudAgentDisplayLabel(ready))
        val provisioning = CloudAgent("b", "Beacon", "provisioning", null, "unknown")
        assertEquals("Beacon", cloudAgentDisplayLabel(provisioning))
    }
}
