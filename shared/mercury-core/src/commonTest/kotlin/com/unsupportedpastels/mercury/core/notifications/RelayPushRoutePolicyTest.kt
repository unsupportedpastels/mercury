package com.unsupportedpastels.mercury.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayPushRoutePolicyTest {
    private fun route(sid: Any? = "sid", profile: Any? = "default", resolved: Any? = true) =
        mapOf("resolved" to resolved, "durable_session_id" to sid, "profile" to profile, "future" to listOf(1))

    @Test fun capabilitiesRequireActualTrueIndependently() {
        for (key in listOf("push_notifications_v1", "push_notifications_v2")) {
            for (value in listOf(true, false, null, 1, 0, 1.0, "true", emptyList<Any>(), mapOf("enabled" to true))) {
                val status = mapOf("capabilities" to mapOf(key to value), "future" to true)
                assertEquals(key == "push_notifications_v1" && value is Boolean && value, RelayPushRoutePolicy.supports(status))
                assertEquals(key == "push_notifications_v2" && value is Boolean && value, RelayPushRoutePolicy.supportsSessionResolution(status))
            }
        }
        for (status in listOf(null, emptyMap<String, Any>(), mapOf("capabilities" to true), mapOf("capabilities" to emptyList<Any>()))) {
            assertFalse(RelayPushRoutePolicy.supports(status))
            assertFalse(RelayPushRoutePolicy.supportsSessionResolution(status))
        }
    }

    @Test fun unresolvedAndMalformedHaveTheSameNoRouteFallback() {
        for (value in listOf(false, null, 1, 0, 1.0, "true", emptyList<Any>())) {
            assertNull(RelayPushRoutePolicy.resolvedSessionRoute(route(resolved = value)))
        }
        assertNull(RelayPushRoutePolicy.resolvedSessionRoute(null))
        assertNull(RelayPushRoutePolicy.resolvedSessionRoute(emptyMap<String, Any>()))
        assertNull(RelayPushRoutePolicy.resolvedSessionRoute(mapOf("resolved" to false)))
        assertNull(RelayPushRoutePolicy.resolvedSessionRoute(mapOf("resolved" to true)))
        for (value in listOf(null, true, 1, emptyList<Any>())) {
            assertNull(RelayPushRoutePolicy.resolvedSessionRoute(route(sid = value)))
            assertNull(RelayPushRoutePolicy.resolvedSessionRoute(route(profile = value)))
        }
    }

    @Test fun routeLimitsAreUTF8BytesAndPreservePreviewContract() {
        for ((field, limit) in listOf("durable_session_id" to 128, "profile" to 64)) {
            for ((value, accepted) in listOf("" to false, "a".repeat(limit) to true, "a".repeat(limit + 1) to false,
                "é".repeat(limit / 2) to true, ("é".repeat(limit / 2) + "a") to false,
                "\uD83D\uDE00".repeat(limit / 4) to true, ("\uD83D\uDE00".repeat(limit / 4) + "a") to false)) {
                assertEquals(accepted, RelayPushRoutePolicy.resolvedSessionRoute(route() + (field to value)) != null, field)
            }
        }
    }

    @Test fun noTrimmingCanonicalizationOrUnknownFieldRejection() {
        for (value in listOf(" ../ spaced : name ", "\u2028\u2029", "e\u0301", "\uD83D\uDE00")) {
            assertEquals(RelayPushSessionRoute(value, value), RelayPushRoutePolicy.resolvedSessionRoute(route(value, value)))
        }
        assertEquals(RelayPushSessionRoute("sid", "default"), RelayPushRoutePolicy.resolvedSessionRoute(route()))
    }

    @Test fun controlAndFormatScalarsIncludeSupplementaryFoundationCompatibility() {
        for (value in listOf("\u0000", "\n", "\u007f", "\u0085", "\u00ad", "\u061c", "\u0890", "\u200d", "\u202e",
            "\ufeff", "\ud804\udcbd", "\ud80d\udc3f", "\udb40\udc01", "\udb40\udd01")) {
            assertNull(RelayPushRoutePolicy.resolvedSessionRoute(route(sid = value)))
            assertNull(RelayPushRoutePolicy.resolvedSessionRoute(route(profile = value)))
        }
        for (value in listOf("\u2065", "\u2028", "\u2029", "\udb40\udd00", "\udb40\udd80")) {
            assertTrue(RelayPushRoutePolicy.resolvedSessionRoute(route(value, value)) != null)
        }
    }
}
