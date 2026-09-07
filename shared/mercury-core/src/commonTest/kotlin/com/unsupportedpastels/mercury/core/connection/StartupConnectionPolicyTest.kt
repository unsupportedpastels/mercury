package com.unsupportedpastels.mercury.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupConnectionPolicyTest {
    private fun target(
        kind: StartupConnectionKind,
        id: String,
        usable: Boolean = true,
    ) = StartupConnectionTarget(kind = kind, id = id, usable = usable)

    private fun choice(kind: StartupConnectionKind, id: String) =
        StartupConnectionChoice(kind = kind, id = id)

    @Test
    fun noConfiguredTargetsShowsOnboarding() {
        val decision = StartupConnectionPolicy.decide(emptyList(), null)

        assertEquals(StartupConnectionAction.ONBOARDING, decision.action)
        assertNull(decision.selected)
        assertFalse(decision.savedChoiceUnavailable)
    }

    @Test
    fun oneUsableTargetWithoutSavedChoiceAutoConnects() {
        val target = target(StartupConnectionKind.DIRECT, "direct-1")

        val decision = StartupConnectionPolicy.decide(listOf(target), null)

        assertEquals(StartupConnectionAction.AUTO_CONNECT, decision.action)
        assertEquals(StartupConnectionChoice(StartupConnectionKind.DIRECT, "direct-1"), decision.selected)
        assertFalse(decision.savedChoiceUnavailable)
    }

    @Test
    fun multipleUsableTargetsShowPicker() {
        val targets = listOf(
            target(StartupConnectionKind.DIRECT, "direct-1"),
            target(StartupConnectionKind.RELAY, "relay-1"),
        )

        val decision = StartupConnectionPolicy.decide(targets, null)

        assertEquals(StartupConnectionAction.SHOW_PICKER, decision.action)
        assertNull(decision.selected)
        assertFalse(decision.savedChoiceUnavailable)
    }

    @Test
    fun validSavedChoiceConnectsOnlyThatChoice() {
        val saved = choice(StartupConnectionKind.RELAY, "relay-1")
        val targets = listOf(
            target(StartupConnectionKind.DIRECT, "direct-1"),
            target(StartupConnectionKind.RELAY, "relay-1"),
        )

        val decision = StartupConnectionPolicy.decide(targets, saved)

        assertEquals(StartupConnectionAction.AUTO_CONNECT, decision.action)
        assertEquals(saved, decision.selected)
        assertFalse(decision.savedChoiceUnavailable)
    }

    @Test
    fun unavailableSavedChoiceNeverFallsBackToAnotherTarget() {
        val saved = choice(StartupConnectionKind.DIRECT, "removed-direct")
        val targets = listOf(target(StartupConnectionKind.RELAY, "other-relay"))

        val decision = StartupConnectionPolicy.decide(targets, saved)

        assertEquals(StartupConnectionAction.SHOW_PICKER, decision.action)
        assertNull(decision.selected)
        assertTrue(decision.savedChoiceUnavailable)
    }

    @Test
    fun pendingRelayIsVisibleToPickerButNeverUsableForAutostart() {
        val pending = target(StartupConnectionKind.RELAY, "pending-relay", usable = false)

        val decision = StartupConnectionPolicy.decide(listOf(pending), null)

        assertEquals(StartupConnectionAction.SHOW_PICKER, decision.action)
        assertNull(decision.selected)
        assertFalse(decision.savedChoiceUnavailable)
    }

    @Test
    fun savedPendingRelayDoesNotAutoConnect() {
        val saved = choice(StartupConnectionKind.RELAY, "pending-relay")
        val pending = target(StartupConnectionKind.RELAY, "pending-relay", usable = false)

        val decision = StartupConnectionPolicy.decide(listOf(pending), saved)

        assertEquals(StartupConnectionAction.SHOW_PICKER, decision.action)
        assertNull(decision.selected)
        assertTrue(decision.savedChoiceUnavailable)
    }

    @Test
    fun multipleConfiguredTargetsIncludingPendingRelayShowPicker() {
        val direct = target(StartupConnectionKind.DIRECT, "direct-1")
        val pending = target(StartupConnectionKind.RELAY, "pending-relay", usable = false)

        val decision = StartupConnectionPolicy.decide(listOf(direct, pending), null)

        assertEquals(StartupConnectionAction.SHOW_PICKER, decision.action)
        assertNull(decision.selected)
        assertFalse(decision.savedChoiceUnavailable)
    }
}
