package com.unsupportedpastels.mercury.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushPreviewFieldPolicyTest {
    private val fields = listOf(
        Triple("title", 160, PushPreviewFieldPolicy::validTitle),
        Triple("body", 640, PushPreviewFieldPolicy::validBody),
        Triple("sid", 128, PushPreviewFieldPolicy::validSessionId),
        Triple("profile", 64, PushPreviewFieldPolicy::validProfile),
    )

    @Test fun hostNormalizedMultilineBodyIsAcceptedWithoutRelaxingOtherFields() {
        for (value in listOf("Alpha", "Alpha\nBeta", "Alpha\nBeta\nGamma")) {
            assertTrue(PushPreviewFieldPolicy.validBody(value))
        }
        for ((name, _, valid) in fields) {
            assertEquals(name == "body", valid("Alpha\nBeta"), name)
            assertFalse(valid("Alpha\r\nBeta"), name) // Host normalizes CRLF; wire CR remains invalid.
        }
    }

    @Test fun onlyLFIsExemptFromFrozenFoundationControlScalars() {
        val controls = listOf(
            "\u0000", "\t", "\r", "\u000b", "\u000c", "\u001f", "\u007f", "\u0085",
            "\u00ad", "\u061c", "\u0890", "\u200b", "\u200d", "\u202e", "\ufeff",
            "\ud804\udcbd", "\ud80d\udc3f", "\ud834\udd73", "\udb40\udc01", "\udb40\udd01",
        )
        for ((name, _, valid) in fields) {
            for (control in controls) assertFalse(valid("A${control}B"), name)
            // Existing Foundation exceptions must not be replaced by Unicode categories.
            for (value in listOf("\u2065", "\u2028", "\u2029", "\udb40\udd00", "\udb40\udd80")) {
                assertTrue(valid(value), name)
            }
        }
    }

    @Test fun nonEmptyUTF8ByteBoundsRemainUnchanged() {
        for ((name, limit, valid) in fields) {
            assertFalse(valid(""), name)
            assertTrue(valid("a".repeat(limit)), name)
            assertFalse(valid("a".repeat(limit + 1)), name)
            assertTrue(valid("é".repeat(limit / 2)), name)
            assertFalse(valid("é".repeat(limit / 2) + "a"), name)
            assertTrue(valid("\ud83d\ude00".repeat(limit / 4)), name)
            assertFalse(valid("\ud83d\ude00".repeat(limit / 4) + "a"), name)
            assertTrue(valid(" ../ spaced : name "), name)
        }
        assertTrue(PushPreviewFieldPolicy.validBody("é".repeat(319) + "\na"))
        assertFalse(PushPreviewFieldPolicy.validBody("é".repeat(319) + "\naa"))
    }
}
