package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSettingsControllerTest {
    private fun scope(profile: String = "default", epoch: Long = 1) = ConnectionOperationScope(
        ServerOrigin.parse("https://review.example"), null, profile, epoch, epoch,
    )

    @Test fun oldProfileProbeCannotPublishIntoNewScope() = runTest {
        var current = scope()
        val response = CompletableDeferred<Pair<VoiceCapabilities, VoiceServerConfig>>()
        val owner = VoiceSettingsController(ConnectionOperationGuard { current }, { response.await() }, { _, _ -> true }, { emptyList() })
        val job = launch { owner.refresh() }
        runCurrent()
        current = scope("work", 2)
        owner.onScopeChanged()
        response.complete(VoiceCapabilities(true, true) to VoiceServerConfig.DEFAULT.copy(autoTts = true))
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(VoiceCapabilities.NONE, owner.capabilities.value)
        assertEquals(VoiceServerConfig.DEFAULT, owner.config.value)
    }

    @Test fun overlappingWritesSerializeAndFailureCannotUndoAnotherField() = runTest {
        val current = scope()
        val firstResponse = CompletableDeferred<Boolean>()
        var calls = 0
        val owner = VoiceSettingsController(ConnectionOperationGuard { current }, { VoiceCapabilities(true, true) to VoiceServerConfig.DEFAULT }, { _, _ ->
            calls++
            if (calls == 1) firstResponse.await() else true
        }, { emptyList() })
        val first = async { owner.setAutoTts(true) }
        runCurrent()
        val second = async { owner.setElevenLabsVoice("new-voice") }
        runCurrent()
        assertEquals(1, calls)
        firstResponse.complete(false)
        assertFalse(first.await())
        assertTrue(second.await())
        assertFalse(owner.config.value.autoTts)
        assertEquals("new-voice", owner.config.value.elevenLabsVoiceId)
    }

    @Test fun cancellationIsRethrownAndOldWriteCannotRollbackNewProfile() = runTest {
        var current = scope()
        val entered = CompletableDeferred<Unit>()
        val owner = VoiceSettingsController(ConnectionOperationGuard { current }, { VoiceCapabilities(true, false) to VoiceServerConfig.DEFAULT }, { _, _ ->
            entered.complete(Unit); awaitCancellation()
        }, { emptyList() })
        val writer = launch { owner.setAutoTts(true) }
        entered.await()
        current = scope("work", 2)
        owner.onScopeChanged()
        writer.cancelAndJoin()
        assertTrue(writer.isCancelled)
        assertEquals(VoiceServerConfig.DEFAULT, owner.config.value)
    }

    @Test fun relayScopeNeverUsesDirectVoiceTransport() = runTest {
        val relay = scope().copy(relayTargetId = "paired-host")
        val owner = VoiceSettingsController(ConnectionOperationGuard { relay }, { error("direct probe") }, { _, _ -> error("direct write") }, { error("direct voices") })
        owner.refresh()
        assertFalse(owner.setAutoTts(true))
        assertTrue(owner.loadVoices().isEmpty())
        assertEquals(VoiceCapabilities.NONE, owner.capabilities.value)
    }
}
