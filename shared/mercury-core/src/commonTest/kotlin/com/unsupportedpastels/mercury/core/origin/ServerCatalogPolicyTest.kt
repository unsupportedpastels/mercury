package com.unsupportedpastels.mercury.core.origin

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerCatalogPolicyTest {
    @Test
    fun evictsLeastRecentlyUsedNonActiveFirst() {
        val lastUsed = listOf<Long?>(50, null, 10, 40, 30, 20, 60, 70, 80, 90)
        assertEquals(listOf(0, 3, 4, 5, 6, 7, 8, 9), ServerCatalogPolicy.retainedIndices(lastUsed, activeIndex = 0))
        // The active entry survives even when it is the oldest.
        assertEquals(listOf(0, 1, 3, 4, 6, 7, 8, 9), ServerCatalogPolicy.retainedIndices(lastUsed, activeIndex = 1))
    }

    @Test
    fun tiesEvictTheEarliestIndexAndSmallCatalogsAreUntouched() {
        assertEquals(listOf(1, 2), ServerCatalogPolicy.retainedIndices(listOf(5, 5, 5), activeIndex = null, maxEntries = 2))
        assertEquals(listOf(0, 1), ServerCatalogPolicy.retainedIndices(listOf(1, 2), activeIndex = 0))
    }
}
