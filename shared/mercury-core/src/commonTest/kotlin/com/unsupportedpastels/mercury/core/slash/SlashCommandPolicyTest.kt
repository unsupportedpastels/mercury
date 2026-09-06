package com.unsupportedpastels.mercury.core.slash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlashCommandPolicyTest {

    @Test
    fun modelPickerMatchesTrimmedCommandOnly() {
        assertTrue(SlashCommandPolicy.isModelPickerCommand("/model"))
        assertTrue(SlashCommandPolicy.isModelPickerCommand("  /model  "))
        assertTrue(SlashCommandPolicy.isModelPickerCommand("/model "))
        assertFalse(SlashCommandPolicy.isModelPickerCommand("/model x"))
        assertFalse(SlashCommandPolicy.isModelPickerCommand("/models"))
    }

    @Test
    fun steerMatchesBareAndArgumentForms() {
        assertTrue(SlashCommandPolicy.isSteerCommand("/steer"))
        assertTrue(SlashCommandPolicy.isSteerCommand("  /steer focus"))
        assertTrue(SlashCommandPolicy.isSteerCommand(" /steer"))
        assertFalse(SlashCommandPolicy.isSteerCommand("/steering"))
    }

    @Test
    fun reasoningEffortParsesAndCanonicalizes() {
        assertEquals("high", SlashCommandPolicy.reasoningEffortCommand("/reasoning high"))
        assertEquals("high", SlashCommandPolicy.reasoningEffortCommand("  /reasoning  HIGH  "))
        assertEquals("high", SlashCommandPolicy.reasoningEffortCommand("/reasoning high"))
        assertNull(SlashCommandPolicy.reasoningEffortCommand("/reasoning"))
        assertNull(SlashCommandPolicy.reasoningEffortCommand("/reasoning bogus"))
        assertNull(SlashCommandPolicy.reasoningEffortCommand("/reasoning high extra"))
        assertEquals(8, SlashCommandPolicy.VALID_REASONING_EFFORTS.size)
    }

    @Test
    fun slashContextRequiresLeadingSingleSlashToken() {
        assertTrue(SlashCommandPolicy.isSlashCommandContext("/he"))
        assertTrue(SlashCommandPolicy.isSlashCommandContext("/help arg"))
        assertTrue(SlashCommandPolicy.isSlashCommandContext("/a b/c"))
        assertFalse(SlashCommandPolicy.isSlashCommandContext("/home/user/file"))
        assertFalse(SlashCommandPolicy.isSlashCommandContext("say /help"))
        assertFalse(SlashCommandPolicy.isSlashCommandContext(""))
    }

    @Test
    fun applyCompletionKeepsPrefixAndDropsRemainder() {
        assertEquals(
            "run /help",
            SlashCommandPolicy.applySlashCompletion("run /he", "/help", 4),
        )
        assertEquals("/help", SlashCommandPolicy.applySlashCompletion("/he", "/help", 0))
        // Prefix already ends in slash: the item's slash is dropped.
        assertEquals("/help", SlashCommandPolicy.applySlashCompletion("/he", "help", 1).let { "/$it".drop(1) })
        assertEquals("/help", SlashCommandPolicy.applySlashCompletion("/xx", "/help", 1))
    }

    @Test
    fun applyCompletionClampsInsideSurrogatePair() {
        // U+1F600 occupies two UTF-16 units; offset 1 lands inside the pair and
        // must clamp back to 0 instead of emitting a lone surrogate.
        val result = SlashCommandPolicy.applySlashCompletion("😀x", "/help", 1)
        assertEquals("/help", result)
    }

    @Test
    fun applyCompletionClampsOutOfRangeOffsets() {
        assertEquals("ab/help", SlashCommandPolicy.applySlashCompletion("ab", "/help", 99))
        assertEquals("/help", SlashCommandPolicy.applySlashCompletion("ab", "/help", -5))
    }

    @Test
    fun defaultDisplayAddsExactlyOneSlash() {
        assertEquals("/details", SlashCommandPolicy.defaultDisplay("details"))
        assertEquals("/details", SlashCommandPolicy.defaultDisplay("/details"))
    }
}
