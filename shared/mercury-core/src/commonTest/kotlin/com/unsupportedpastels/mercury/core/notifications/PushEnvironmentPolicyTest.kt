package com.unsupportedpastels.mercury.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushEnvironmentPolicyTest {
    private val exact = mapOf<String, Any?>(
        "capabilities" to mapOf(
            "push_environments" to mapOf(
                "version" to 1,
                "environments" to listOf("sandbox", "production"),
                "generic_register_version" to 2,
            ),
        ),
    )

    @Test fun parserAcceptsOnlyExactWireValues() {
        assertEquals(PushEnvironment.SANDBOX, PushEnvironmentPolicy.parse("sandbox"))
        assertEquals(PushEnvironment.PRODUCTION, PushEnvironmentPolicy.parse("production"))
        for (value in listOf<String?>(null, "", "Sandbox", " production", "production ", "development")) {
            assertNull(PushEnvironmentPolicy.parse(value), value)
        }
    }

    @Test fun productionGenericRequiresExactStructuredCapability() {
        assertTrue(PushEnvironmentPolicy.supportsVersion2Generic(exact, PushEnvironment.PRODUCTION))
        assertTrue(PushEnvironmentPolicy.supportsVersion2Generic(exact, PushEnvironment.SANDBOX))
        val capability = (exact["capabilities"] as Map<*, *>)["push_environments"] as Map<*, *>
        for ((field, value) in listOf(
            "version" to true, "version" to 1L, "version" to 1.0, "version" to "1", "version" to 2,
            "generic_register_version" to true, "generic_register_version" to 2L,
            "generic_register_version" to 2.0, "generic_register_version" to "2", "generic_register_version" to 1,
            "environments" to listOf("sandbox"), "environments" to listOf("production", "future"),
            "environments" to listOf("production", "production"), "environments" to "sandbox,production",
        )) {
            val changed = capability.toMutableMap().also { it[field] = value }
            val status = mapOf("capabilities" to mapOf("push_environments" to changed))
            assertFalse(PushEnvironmentPolicy.supportsVersion2Generic(status, PushEnvironment.PRODUCTION), "$field=$value")
        }
        assertFalse(PushEnvironmentPolicy.supportsVersion2Generic(null, PushEnvironment.PRODUCTION))
    }

    @Test fun swiftJsonBoundaryPreservesExactIntegerAndEnvironmentTypes() {
        val exactJson = """{"capabilities":{"push_notifications_v1":true,"push_environments":{"version":1,"environments":["sandbox","production"],"generic_register_version":2}}}"""
        assertEquals(2, PushEnvironmentPolicy.genericRegisterVersionJson("production", true, exactJson))
        assertEquals(2, PushEnvironmentPolicy.genericRegisterVersionJson("sandbox", true, exactJson))
        for (bad in listOf(
            exactJson.replace("\"version\":1", "\"version\":true"),
            exactJson.replace("\"version\":1", "\"version\":1.0"),
            exactJson.replace("\"generic_register_version\":2", "\"generic_register_version\":\"2\""),
            exactJson.replace("\"production\"", "\"future\""),
            "not-json",
        )) assertEquals(0, PushEnvironmentPolicy.genericRegisterVersionJson("production", true, bad), bad)
        assertEquals(1, PushEnvironmentPolicy.genericRegisterVersionJson("sandbox", true, "{}"))
        assertEquals(1, PushEnvironmentPolicy.genericRegisterVersionJson("sandbox", true, "not-json"))
    }

    @Test fun registrationPolicyPreservesLegacySandboxAndFailsClosedForProduction() {
        assertEquals(1, PushEnvironmentPolicy.genericRegisterVersion(PushEnvironment.SANDBOX, legacySupported = true, status = null))
        assertEquals(2, PushEnvironmentPolicy.genericRegisterVersion(PushEnvironment.SANDBOX, legacySupported = true, status = exact))
        assertEquals(2, PushEnvironmentPolicy.genericRegisterVersion(PushEnvironment.PRODUCTION, legacySupported = false, status = exact))
        assertEquals(0, PushEnvironmentPolicy.genericRegisterVersion(PushEnvironment.PRODUCTION, legacySupported = true, status = null))
        assertEquals(0, PushEnvironmentPolicy.genericRegisterVersion(PushEnvironment.SANDBOX, legacySupported = false, status = null))
    }
}
