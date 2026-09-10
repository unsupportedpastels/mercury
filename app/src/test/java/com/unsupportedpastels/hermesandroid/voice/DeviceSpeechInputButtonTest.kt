package com.unsupportedpastels.hermesandroid.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSpeechInputButtonTest {
    private class FakeRecognizer(
        var startResult: Boolean = true,
    ) : DeviceSpeechRecognizer {
        private val mutableState = MutableStateFlow(DeviceSpeechRecognizerState.Idle)
        override val state: StateFlow<DeviceSpeechRecognizerState> = mutableState
        override val isActive: Boolean get() = mutableState.value != DeviceSpeechRecognizerState.Idle
        var starts = 0
        var finishes = 0
        var startedDraft: String? = null
        var draftCallback: ((String) -> Unit)? = null
        var errorCallback: ((String) -> Unit)? = null

        override fun start(
            currentDraft: String,
            onDraftChanged: (String) -> Unit,
            onError: (String) -> Unit,
        ): Boolean {
            starts++
            startedDraft = currentDraft
            draftCallback = onDraftChanged
            errorCallback = onError
            if (startResult) mutableState.value = DeviceSpeechRecognizerState.Listening
            return startResult
        }

        override fun finish() {
            finishes++
            mutableState.value = DeviceSpeechRecognizerState.Idle
        }
    }

    private fun coordinator(
        recognizer: FakeRecognizer,
        available: Boolean = true,
        enabled: Boolean = true,
        draft: String = "draft",
        onDraftChanged: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ) = DeviceSpeechPermissionCoordinator(recognizer).also {
        it.update(available, enabled, draft, onDraftChanged, onError)
    }

    @Test
    fun missingRecordAudioPermissionRequestsPermissionBeforeStarting() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer)
        var requests = 0

        coordinator.onClick(
            hasRecordAudioPermission = false,
            requestRecordAudioPermission = { requests++ },
        )

        assertEquals(1, requests)
        assertEquals(0, recognizer.starts)
        assertFalse(recognizer.isActive)
    }

    @Test
    fun permissionGrantWithoutMatchingRequestDoesNotStart() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer)

        coordinator.onPermissionResult(granted = true)

        assertEquals(0, recognizer.starts)
    }

    @Test
    fun permissionGrantStartsWithCurrentDraftAndCallbacks() {
        val recognizer = FakeRecognizer()
        var changedDraft: String? = null
        var error: String? = null
        val coordinator = coordinator(
            recognizer = recognizer,
            draft = "existing draft",
            onDraftChanged = { changedDraft = it },
            onError = { error = it },
        )
        coordinator.onClick(false) {}

        coordinator.onPermissionResult(granted = true)
        recognizer.draftCallback?.invoke("recognized draft")
        recognizer.errorCallback?.invoke("recognizer error")

        assertEquals(1, recognizer.starts)
        assertEquals("existing draft", recognizer.startedDraft)
        assertEquals("recognized draft", changedDraft)
        assertEquals("recognizer error", error)
    }

    @Test
    fun alreadyGrantedStartsWithoutRequestingAgain() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer)
        var requests = 0

        coordinator.onClick(true) { requests++ }

        assertEquals(1, recognizer.starts)
        assertEquals(0, requests)
    }

    @Test
    fun denialReportsMeaningfulErrorAndDoesNotStart() {
        val recognizer = FakeRecognizer()
        var error: String? = null
        val coordinator = coordinator(recognizer, onError = { error = it })
        coordinator.onClick(false) {}

        coordinator.onPermissionResult(granted = false)

        assertEquals(0, recognizer.starts)
        assertEquals("Microphone permission is required for voice input", error)
    }

    @Test
    fun repeatedTapsWhilePermissionIsPendingLaunchOnlyOneRequest() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer)
        var requests = 0

        coordinator.onClick(false) { requests++ }
        coordinator.onClick(false) { requests++ }

        assertEquals(1, requests)
        assertEquals(0, recognizer.starts)
    }

    @Test
    fun unavailableRecognizerReportsExistingUnavailableError() {
        val recognizer = FakeRecognizer(startResult = false)
        var error: String? = null
        val coordinator = coordinator(recognizer, onError = { error = it })

        coordinator.onClick(true) {}

        assertEquals(1, recognizer.starts)
        assertEquals("Voice input is unavailable", error)
    }

    @Test
    fun advertisedUnavailableDoesNotRequestOrStart() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer, available = false)
        var requests = 0

        coordinator.onClick(false) { requests++ }

        assertEquals(0, requests)
        assertEquals(0, recognizer.starts)
    }

    @Test
    fun activeTapStopsWithoutCheckingOrRequestingPermission() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer)
        coordinator.onClick(true) {}
        var requests = 0

        coordinator.onClick(false) { requests++ }

        assertEquals(1, recognizer.finishes)
        assertEquals(0, requests)
        assertFalse(recognizer.isActive)
    }

    @Test
    fun grantAfterDisabledDoesNotStart() {
        val recognizer = FakeRecognizer()
        val coordinator = coordinator(recognizer)
        coordinator.onClick(false) {}
        coordinator.update(true, false, "new draft", {}, {})

        coordinator.onPermissionResult(granted = true)

        assertEquals(0, recognizer.starts)
    }

    @Test
    fun grantAfterRequestScopeIsDisposedDoesNotStart() {
        val recognizer = FakeRecognizer()
        var error: String? = null
        val coordinator = coordinator(recognizer, onError = { error = it })
        coordinator.onClick(false) {}
        coordinator.dispose()

        coordinator.onPermissionResult(granted = true)

        assertEquals(0, recognizer.starts)
        assertTrue(error == null)
    }
}
