package com.unsupportedpastels.mercury.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCapabilityPolicyTest {
    @Test fun qualifiedAliasesNeverGuessAcrossTwoQualifiedNames() {
        assertTrue(ModelCapabilityPolicy.identifiersMatch("vendor/model", "model"))
        assertFalse(ModelCapabilityPolicy.identifiersMatch("one/model", "two/model"))
        assertFalse(ModelCapabilityPolicy.identifiersMatch(null, "model"))
    }

    @Test fun exactExplicitUnsupportedFlagsBeatAliases() {
        val unsupported = ModelCapabilitiesSpec(fast = false, reasoning = false)
        assertEquals(unsupported, ModelCapabilityPolicy.fromCatalog("model", mapOf(
            "model" to unsupported,
            "vendor/model" to ModelCapabilitiesSpec(fast = true, reasoning = true),
        )))
    }

    @Test fun unknownAndConflictingAliasCapabilitiesStayUnknown() {
        assertNull(ModelCapabilityPolicy.fromCatalog("model", mapOf("model" to ModelCapabilitiesSpec())))
        assertNull(ModelCapabilityPolicy.fromCatalog("model", mapOf(
            "one/model" to ModelCapabilitiesSpec(fast = true),
            "two/model" to ModelCapabilitiesSpec(fast = false),
        )))
        assertEquals(ModelCapabilitiesSpec(reasoning = true), ModelCapabilityPolicy.fromCatalog("vendor/model", mapOf(
            "model" to ModelCapabilitiesSpec(reasoning = true),
        )))
    }
}
