package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceSpeechPermissionRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRecognizer : DeviceSpeechRecognizer {
        override val state: StateFlow<DeviceSpeechRecognizerState> =
            MutableStateFlow(DeviceSpeechRecognizerState.Idle)
        override val isActive: Boolean = false
        var starts = 0

        override fun start(
            currentDraft: String,
            onDraftChanged: (String) -> Unit,
            onError: (String) -> Unit,
        ): Boolean {
            starts++
            return true
        }

        override fun finish() = Unit
    }

    @Test
    fun pendingPermissionGrantSurvivesSavedInstanceStateRestoreForSameRequestScope() {
        val restoration = StateRestorationTester(composeRule)
        val recognizer = FakeRecognizer()
        var coordinator: DeviceSpeechPermissionCoordinator? = null

        restoration.setContent {
            coordinator = rememberDeviceSpeechPermissionCoordinator(
                controller = recognizer,
                requestIdentity = "session-a",
            )
        }
        composeRule.runOnIdle {
            checkNotNull(coordinator).apply {
                update(true, true, "draft", {}, {})
                onClick(false) {}
            }
        }

        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            checkNotNull(coordinator).apply {
                update(true, true, "draft", {}, {})
                onPermissionResult(granted = true)
            }
        }

        assertEquals(1, recognizer.starts)
    }

    @Test
    fun pendingPermissionDenialSurvivesSavedInstanceStateRestoreForSameRequestScope() {
        val restoration = StateRestorationTester(composeRule)
        val recognizer = FakeRecognizer()
        var coordinator: DeviceSpeechPermissionCoordinator? = null
        var error: String? = null

        restoration.setContent {
            coordinator = rememberDeviceSpeechPermissionCoordinator(
                controller = recognizer,
                requestIdentity = "session-a",
            )
        }
        composeRule.runOnIdle {
            checkNotNull(coordinator).apply {
                update(true, true, "draft", {}, { error = it })
                onClick(false) {}
            }
        }

        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            checkNotNull(coordinator).apply {
                update(true, true, "draft", {}, { error = it })
                onPermissionResult(granted = false)
            }
        }

        assertEquals("Microphone permission is required for voice input", error)
        assertEquals(0, recognizer.starts)
    }

    @Test
    fun changingRequestScopeDropsPendingPermissionGrant() {
        val recognizer = FakeRecognizer()
        var requestIdentity by mutableStateOf("session-a")
        var coordinator: DeviceSpeechPermissionCoordinator? = null

        composeRule.setContent {
            coordinator = rememberDeviceSpeechPermissionCoordinator(
                controller = recognizer,
                requestIdentity = requestIdentity,
            )
        }
        composeRule.runOnIdle {
            checkNotNull(coordinator).apply {
                update(true, true, "draft", {}, {})
                onClick(false) {}
            }
            requestIdentity = "session-b"
        }
        composeRule.runOnIdle {
            checkNotNull(coordinator).apply {
                update(true, true, "draft", {}, {})
                onPermissionResult(granted = true)
            }
        }

        assertEquals(0, recognizer.starts)
    }

    @Test
    fun restoredPermissionIntentIsRejectedAfterProcessIdentityChanges() {
        val recognizer = FakeRecognizer()
        val pendingState = DeviceSpeechPermissionRequestState()
        DeviceSpeechPermissionCoordinator(recognizer, pendingState).apply {
            update(true, true, "draft", {}, {})
            onClick(false) {}
        }
        val saver = deviceSpeechPermissionRequestStateSaver("session-a", "process-a")
        val saved = checkNotNull(
            with(saver) { SaverScope { true }.save(pendingState) },
        )
        val restoredState = checkNotNull(
            deviceSpeechPermissionRequestStateSaver("session-a", "process-b").restore(saved),
        )

        DeviceSpeechPermissionCoordinator(recognizer, restoredState).apply {
            update(true, true, "draft", {}, {})
            onPermissionResult(granted = true)
        }

        assertEquals(0, recognizer.starts)
    }

    @Test
    fun restoredPermissionIntentIsRejectedForDifferentRequestScope() {
        val recognizer = FakeRecognizer()
        val pendingState = DeviceSpeechPermissionRequestState()
        DeviceSpeechPermissionCoordinator(recognizer, pendingState).apply {
            update(true, true, "draft", {}, {})
            onClick(false) {}
        }
        val saver = deviceSpeechPermissionRequestStateSaver("session-a", "process-a")
        val saved = checkNotNull(
            with(saver) { SaverScope { true }.save(pendingState) },
        )
        val restoredState = checkNotNull(
            deviceSpeechPermissionRequestStateSaver("session-b", "process-a").restore(saved),
        )

        DeviceSpeechPermissionCoordinator(recognizer, restoredState).apply {
            update(true, true, "draft", {}, {})
            onPermissionResult(granted = true)
        }

        assertEquals(0, recognizer.starts)
    }
}
