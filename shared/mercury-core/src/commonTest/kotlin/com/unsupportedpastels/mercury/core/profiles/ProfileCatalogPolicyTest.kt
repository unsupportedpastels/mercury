package com.unsupportedpastels.mercury.core.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileCatalogPolicyTest {
    private val policy = ProfileCatalogPolicy

    @Test
    fun restNamesPreserveTrimmedLegacySemanticsWithBoundsAndOrder() {
        val names = policy.sanitizeRestNames(
            listOf(" default ", "work", "work", "", "   ", "../invalid", "A profile")
        )

        assertEquals(listOf("default", "work", "../invalid", "A profile"), names)
    }

    @Test
    fun restNamesCapAfterFilteringAndDeduplication() {
        val names = policy.sanitizeRestNames(
            (0 until policy.maxRestProfiles + 2).map { "profile-$it" } + "profile-0"
        )

        assertEquals(policy.maxRestProfiles, names.size)
        assertEquals("profile-0", names.first())
        assertEquals("profile-${policy.maxRestProfiles - 1}", names.last())
    }

    @Test
    fun rpcNamesUseExactLowercaseProfileGrammar() {
        val names = policy.sanitizeRpcNames(
            listOf("default", "work", "work", "Work", "../invalid", "has space", "ok_name", "ok-name")
        )

        assertEquals(listOf("default", "work", "ok_name", "ok-name"), names)
        assertTrue(policy.isValidRpcName("a"))
        assertTrue(policy.isValidRpcName("a0_b-c"))
        assertFalse(policy.isValidRpcName("_leading"))
        assertFalse(policy.isValidRpcName("-leading"))
        assertFalse(policy.isValidRpcName("A"))
        assertFalse(policy.isValidRpcName("a.b"))
        assertFalse(policy.isValidRpcName("a b"))
    }

    @Test
    fun rpcNamesCapWithoutChangingAdmissionOrder() {
        val names = policy.sanitizeRpcNames(
            (0 until policy.maxRpcProfiles + 2).map { "p$it" } + "p0"
        )

        assertEquals(policy.maxRpcProfiles, names.size)
        assertEquals("p0", names.first())
        assertEquals("p${policy.maxRpcProfiles - 1}", names.last())
    }
}
