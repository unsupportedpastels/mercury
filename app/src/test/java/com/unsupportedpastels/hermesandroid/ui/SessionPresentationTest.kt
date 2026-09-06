package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.applyTranscriptEvent
import com.unsupportedpastels.hermesandroid.gateway.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionPresentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun transcriptOnlyUpdatesReuseInboxProjectionAndMetadataChangeInvalidatesIt() {
        val snapshot = mutableStateOf(HermesGatewaySnapshot(
            durableSessions = listOf(SessionSummary(id = DurableSessionId("one"), title = "One", preview = "Preview")),
        ))
        var observed: SessionInboxMetadata? = null
        composeRule.setContent {
            val current = snapshot.value
            val inbox = rememberSessionInboxMetadata(current.durableSessions, emptyMap(), emptyList(), emptyList(), emptyList())
            Text(current.chatSessions.size.toString())
            SideEffect { observed = inbox }
        }
        lateinit var original: SessionInboxMetadata
        composeRule.runOnIdle {
            original = observed!!
            snapshot.value = snapshot.value.copy(chatSessions = mapOf(DurableSessionId("one") to ChatSessionSnapshot(isSending = true)))
        }
        composeRule.runOnIdle {
            assertSame(original, observed)
            snapshot.value = snapshot.value.copy(durableSessions = snapshot.value.durableSessions.map { it.copy(title = "Renamed") })
        }
        composeRule.runOnIdle {
            assertNotSame(original, observed)
            assertEquals("Renamed", observed!!.sessions.single().title)
        }
    }

    @Test fun retainedTranscriptKeysSurviveEarlierRowRemovalAndStreamingDeltas() {
        val seeded = ChatSessionSnapshot(messages = listOf(
            ChatMessage(ChatMessageRole.User, "Old"), ChatMessage(ChatMessageRole.User, "Keep"),
        )).applyTranscriptEvent(HermesChatEvent.MessageStart(RuntimeSessionId("runtime"), ""))
        val oldEntry = coalesceTranscriptEntries(seeded.messages)[1]
        val oldKey = transcriptEntryKey(oldEntry, seeded)
        val replaced = seeded.copy(messages = seeded.messages.drop(1))
            .applyTranscriptEvent(HermesChatEvent.MessageDelta(RuntimeSessionId("runtime"), "reply"))
        val newEntry = coalesceTranscriptEntries(replaced.messages)[0]
        assertEquals(oldKey, transcriptEntryKey(newEntry, replaced))
        val next = replaced.applyTranscriptEvent(HermesChatEvent.MessageDelta(RuntimeSessionId("runtime"), " more"))
        assertEquals(transcriptEntryKey(coalesceTranscriptEntries(replaced.messages).last(), replaced),
            transcriptEntryKey(coalesceTranscriptEntries(next.messages).last(), next))
    }
}
