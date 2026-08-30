package com.unsupportedpastels.mercury.core.notifications

import com.unsupportedpastels.mercury.core.transcript.InterruptSentinel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Merged from the Android HermesNotificationPolicyTest and iOS
 * NotificationPolicyTests text/visibility sections. All strings are asserted
 * verbatim — user-visible contract on both platforms. Divergences resolved
 * during migration (documented in docs/plans/kmp-shared-core.md): interrupt
 * sentinel suppression and cap-then-trim input previews (both from iOS),
 * secure-input fallback wording (from Android's "…is required to continue"
 * family).
 */
class NotificationPolicyTest {

    // --- finalResponsePreview -------------------------------------------------

    @Test
    fun previewStripsMarkdownAndCapsLines() {
        val text = """
            # Heading

            **Done** with `task`
            second __line__
            third line
            fourth line
        """.trimIndent()
        assertEquals(
            "Done with task\nsecond line",
            NotificationTextPolicy.finalResponsePreview(text, maxLines = 2),
        )
    }

    @Test
    fun previewCapsAt240CharsAndFallsBack() {
        val long = "a".repeat(500)
        assertEquals(240, NotificationTextPolicy.finalResponsePreview(long).length)
        assertEquals("Response completed", NotificationTextPolicy.finalResponsePreview(""))
        assertEquals("Response completed", NotificationTextPolicy.finalResponsePreview("### Only a heading"))
    }

    @Test
    fun previewSuppressesInterruptSentinel() {
        val sentinel = "Operation interrupted: waiting for model response (request abc)"
        assertTrue(InterruptSentinel.isInterruptSentinel("  $sentinel  "))
        assertFalse(InterruptSentinel.isInterruptSentinel("Operation interrupted: other"))
        assertEquals("Response completed", NotificationTextPolicy.finalResponsePreview(sentinel))
    }

    // --- inputPreview ---------------------------------------------------------

    @Test
    fun inputPreviewCapsThenTrims() {
        assertEquals("hi", NotificationTextPolicy.inputPreview("  hi  "))
        val padded = "b".repeat(238) + "  trailing"
        val preview = NotificationTextPolicy.inputPreview(padded)
        assertEquals(238, preview.length)
        assertTrue(preview.all { it == 'b' })
    }

    // --- wire status mapping and headings -------------------------------------

    @Test
    fun wireStatusMapsToCompletionStatus() {
        assertEquals(CompletionStatus.FAILED, NotificationTextPolicy.completionStatusFromWire("error"))
        assertEquals(CompletionStatus.FAILED, NotificationTextPolicy.completionStatusFromWire("Failed"))
        assertEquals(CompletionStatus.CANCELLED, NotificationTextPolicy.completionStatusFromWire("cancelled"))
        assertEquals(CompletionStatus.CANCELLED, NotificationTextPolicy.completionStatusFromWire("canceled"))
        assertEquals(CompletionStatus.CANCELLED, NotificationTextPolicy.completionStatusFromWire("interrupted"))
        assertEquals(CompletionStatus.FINISHED, NotificationTextPolicy.completionStatusFromWire(null))
        assertEquals(CompletionStatus.FINISHED, NotificationTextPolicy.completionStatusFromWire("done"))
    }

    @Test
    fun completionHeadingsAreExact() {
        assertEquals("Mercury finished", NotificationTextPolicy.completionHeading(CompletionStatus.FINISHED))
        assertEquals("Mercury task failed", NotificationTextPolicy.completionHeading(CompletionStatus.FAILED))
        assertEquals("Mercury task was cancelled", NotificationTextPolicy.completionHeading(CompletionStatus.CANCELLED))
    }

    @Test
    fun inputHeadingsAreExact() {
        assertEquals("Hermes needs approval", NotificationTextPolicy.inputHeading(NotificationInputKind.APPROVAL))
        assertEquals("Hermes needs your input", NotificationTextPolicy.inputHeading(NotificationInputKind.CLARIFICATION))
        assertEquals("Hermes needs secure input", NotificationTextPolicy.inputHeading(NotificationInputKind.SECURE_INPUT))
    }

    @Test
    fun fallbackPromptsAreExact() {
        assertEquals("Authorization is required to continue", NotificationTextPolicy.APPROVAL_FALLBACK)
        assertEquals("Clarification is required to continue", NotificationTextPolicy.CLARIFICATION_FALLBACK)
        assertEquals("Secure input is required to continue", NotificationTextPolicy.SECURE_INPUT_FALLBACK)
    }

    @Test
    fun activeTurnTitleCounts() {
        assertEquals("Hermes is working", NotificationTextPolicy.activeTurnTitle(0))
        assertEquals("Hermes is working", NotificationTextPolicy.activeTurnTitle(1))
        assertEquals("Hermes is working in 3 sessions", NotificationTextPolicy.activeTurnTitle(3))
    }

    // --- visibility -----------------------------------------------------------

    @Test
    fun suppressesOnlyWhenForegroundFocusedAndVisible() {
        val id = "sess-1"
        val looking = SessionNotificationVisibility(appForeground = true, windowFocused = true, visibleSessionId = id)
        assertFalse(NotificationVisibilityPolicy.shouldPost(id, looking))
        assertTrue(NotificationVisibilityPolicy.shouldPost("sess-2", looking))
        assertTrue(NotificationVisibilityPolicy.shouldPost(id, looking.copy(appForeground = false)))
        assertTrue(NotificationVisibilityPolicy.shouldPost(id, looking.copy(windowFocused = false)))
        assertTrue(NotificationVisibilityPolicy.shouldPost(id, looking.copy(visibleSessionId = null)))
    }

    @Test
    fun defaultWindowFocusPreservesTwoFieldSemantics() {
        // iOS constructs visibility without windowFocused; the default must not
        // add a third suppression condition there.
        val id = "sess-1"
        val visibility = SessionNotificationVisibility(appForeground = true, visibleSessionId = id)
        assertFalse(NotificationVisibilityPolicy.shouldPost(id, visibility))
    }
}
