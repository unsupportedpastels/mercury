package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral spec for the shared transcript engine, ported from the iOS
 * TranscriptReducerTests (the hermetic suite). The full 57-test iOS suite
 * runs against the Swift facade as the cross-platform parity harness.
 */
class TranscriptEngineTest {

    private val sid = "runtime-1"
    private fun initial() = TranscriptEngine.initial()

    private fun reduce(vararg events: ChatEvent, from: TranscriptSnapshot = initial()): TranscriptSnapshot =
        events.fold(from, TranscriptEngine::apply)

    // --- streaming ------------------------------------------------------------

    @Test
    fun startDeltaCompleteProducesOneCompletedRow() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageDelta(sid, "Hel"),
            ChatEvent.MessageDelta(sid, "lo"),
            ChatEvent.MessageComplete(sid, text = "Hello"),
        )
        assertEquals(1, state.rows.size)
        assertEquals("Hello", state.rows[0].text)
        assertTrue(state.rows[0].completed)
        assertFalse(state.hasStreamingAssistant)
    }

    @Test
    fun deltaWithoutStartOpensARow() {
        val state = reduce(ChatEvent.MessageDelta(sid, "orphan"))
        assertEquals(1, state.rows.size)
        assertFalse(state.rows[0].completed)
    }

    @Test
    fun completeWithNullTextKeepsStreamedBuffer() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageDelta(sid, "kept"),
            ChatEvent.MessageComplete(sid, text = null),
        )
        assertEquals("kept", state.rows.single().text)
        assertTrue(state.rows.single().completed)
    }

    @Test
    fun deltasPreserveLeadingWhitespace() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageDelta(sid, "a"),
            ChatEvent.MessageDelta(sid, " b"),
        )
        assertEquals("a b", state.rows.single().text)
    }

    @Test
    fun duplicateStartOpensSecondRow() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageStart(sid, null),
        )
        assertEquals(2, state.rows.size)
    }

    // --- interrupt sentinel ---------------------------------------------------

    @Test
    fun sentinelCompletionKeepsStreamedBufferAndDropsEmptyRow() {
        val sentinel = "Operation interrupted: waiting for model response (3s elapsed)."
        val kept = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageDelta(sid, "partial"),
            ChatEvent.MessageComplete(sid, text = sentinel),
        )
        assertEquals("partial", kept.rows.single().text)

        val dropped = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageComplete(sid, text = sentinel),
        )
        assertTrue(dropped.rows.isEmpty())
    }

    @Test
    fun sentinelCompletionWithoutOpenRowAppendsNothing() {
        val sentinel = "Operation interrupted: waiting for model response (1s elapsed)."
        assertTrue(reduce(ChatEvent.MessageComplete(sid, text = sentinel)).rows.isEmpty())
    }

    // --- reasoning ------------------------------------------------------------

    @Test
    fun reasoningAppendsReplacesAndSurvivesCompletion() {
        var state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.ReasoningDelta(sid, "think", replace = false),
            ChatEvent.ReasoningDelta(sid, " more", replace = false),
        )
        assertEquals("think more", state.rows.single().reasoningText)
        state = reduce(ChatEvent.ReasoningDelta(sid, "snapshot", replace = true), from = state)
        assertEquals("snapshot", state.rows.single().reasoningText)
        state = reduce(ChatEvent.MessageComplete(sid, text = "done"), from = state)
        assertEquals("snapshot", state.rows.single().reasoningText)
    }

    @Test
    fun orphanReasoningOpensReasoningOnlyRowThatLaterDeltasFill() {
        val state = reduce(
            ChatEvent.ReasoningDelta(sid, "pre", replace = false),
            ChatEvent.MessageDelta(sid, "text"),
        )
        assertEquals(1, state.rows.size)
        assertEquals("pre", state.rows[0].reasoningText)
        assertEquals("text", state.rows[0].text)
    }

    @Test
    fun blankReasoningIsIgnored() {
        assertTrue(reduce(ChatEvent.ReasoningDelta(sid, "   ", replace = false)).rows.isEmpty())
    }

    // --- interim --------------------------------------------------------------

    @Test
    fun interimSealsSegmentAndNextDeltaOpensFreshRow() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageDelta(sid, "will be replaced"),
            ChatEvent.MessageInterim(sid, "interim note", alreadyStreamed = false),
            ChatEvent.MessageDelta(sid, "fresh"),
        )
        assertEquals(2, state.rows.size)
        assertEquals("interim note", state.rows[0].text)
        assertTrue(state.rows[0].completed)
        assertEquals("fresh", state.rows[1].text)
        assertFalse(state.rows[1].completed)
    }

    // --- tools ----------------------------------------------------------------

    @Test
    fun toolLifecyclePreservesContextThroughCompletion() {
        val state = reduce(
            ChatEvent.ToolStart(sid, "t1", "Bash", "ls"),
            ChatEvent.ToolComplete(sid, "t1", "Bash", "2 files"),
        )
        val tool = state.tools.single()
        assertEquals("ls", tool.context)
        assertEquals("2 files", tool.summary)
        assertEquals(ToolRowState.Completed, tool.state)
    }

    @Test
    fun lateStartForCompletedToolIsIgnored() {
        val state = reduce(
            ChatEvent.ToolStart(sid, "t1", "Bash", "ls"),
            ChatEvent.ToolComplete(sid, "t1", "Bash", "done"),
            ChatEvent.ToolStart(sid, "t1", "Bash", "again"),
        )
        assertEquals(ToolRowState.Completed, state.tools.single().state)
        assertEquals("ls", state.tools.single().context)
    }

    @Test
    fun restartWhileRunningReplacesInPlaceKeepingSummaryUntouched() {
        val state = reduce(
            ChatEvent.ToolStart(sid, "t1", "Bash", "first"),
            ChatEvent.ToolStart(sid, "t1", "Bash", "second"),
        )
        assertEquals(1, state.tools.size)
        assertEquals("second", state.tools.single().context)
    }

    @Test
    fun completeWithoutStartAppendsCompletedRow() {
        val state = reduce(ChatEvent.ToolComplete(sid, "tX", "Read", null))
        assertEquals(ToolRowState.Completed, state.tools.single().state)
        assertNull(state.tools.single().context)
    }

    @Test
    fun toolRowsKeepTrailingWindowOf50() {
        var state = initial()
        for (index in 1..55) {
            state = TranscriptEngine.apply(state, ChatEvent.ToolStart(sid, "t$index", "Tool", null))
        }
        assertEquals(50, state.tools.size)
        assertEquals("t6", state.tools.first().toolId)
    }

    @Test
    fun generatingStatusSetsAndClears() {
        var state = reduce(ChatEvent.ToolGenerating(sid, "Bash"))
        assertEquals("Generating Bash arguments…", state.generatingStatusText)
        state = reduce(ChatEvent.ToolStart(sid, "t1", "Bash", null), from = state)
        assertNull(state.generatingStatusText)
    }

    @Test
    fun completionAndErrorFinalizeRunningTools() {
        val viaComplete = reduce(
            ChatEvent.ToolStart(sid, "t1", "Bash", null),
            ChatEvent.MessageComplete(sid, text = "done"),
        )
        assertEquals(ToolRowState.Completed, viaComplete.tools.single().state)

        val viaError = reduce(
            ChatEvent.ToolStart(sid, "t2", "Bash", null),
            ChatEvent.Error(sid, "boom"),
        )
        assertEquals(ToolRowState.Completed, viaError.tools.single().state)
        assertEquals("boom", viaError.lastError)
    }

    @Test
    fun errorDoesNotCreateRows() {
        val state = reduce(ChatEvent.Error(sid, "boom"))
        assertTrue(state.rows.isEmpty())
    }

    // --- pending requests -----------------------------------------------------

    @Test
    fun secondRequestReplacesPendingAndAnyExpireClears() {
        val approval = ChatEvent.ApprovalRequest(sid, "r1", "cmd", null, listOf("yes", "no"))
        val clarify = ChatEvent.ClarifyRequest(sid, "r2", "which?", listOf("a"), multiSelect = false)
        var state = reduce(approval)
        assertIs<PendingTranscriptRequest.Approval>(state.pendingRequest)
        state = reduce(clarify, from = state)
        assertIs<PendingTranscriptRequest.Clarify>(state.pendingRequest)
        // Blanket expire: not matched by id or kind.
        state = reduce(ChatEvent.ApprovalExpire(sid, "unrelated"), from = state)
        assertNull(state.pendingRequest)
    }

    // --- title / status -------------------------------------------------------

    @Test
    fun titleAdoptionRequiresNewChatFlowAndOwnSession() {
        val foreign = reduce(ChatEvent.SessionTitle("other", "New title"))
        assertNull(foreign.adoptedTitle)

        var state = TranscriptEngine.initial(isNewSession = true)
            .copy(ownSessionIds = setOf(sid))
        state = TranscriptEngine.apply(state, ChatEvent.SessionTitle("other", "Foreign"))
        assertNull(state.adoptedTitle)
        state = TranscriptEngine.apply(state, ChatEvent.SessionTitle(sid, "Mine"))
        assertEquals("Mine", state.adoptedTitle)

        val existingFlow = TranscriptEngine.initial(isNewSession = false)
            .copy(ownSessionIds = setOf(sid))
        assertNull(TranscriptEngine.apply(existingFlow, ChatEvent.SessionTitle(sid, "Nope")).adoptedTitle)
    }

    @Test
    fun statusUpdatesRecordTextAndCount() {
        val state = reduce(
            ChatEvent.StatusUpdate(sid, "info", "working"),
            ChatEvent.StatusUpdate(sid, "info", "working"),
        )
        assertEquals("working", state.latestStatusText)
        assertEquals(2, state.statusUpdateCount)
    }

    @Test
    fun unmodeledEventsAreNoOps() {
        val state = reduce(
            ChatEvent.SessionInfo(sid),
            ChatEvent.UnsupportedBlockingRequest(sid, UnsupportedBlockingKind.Secret, "r", null),
            ChatEvent.UnsupportedBlockingExpire(sid, UnsupportedBlockingKind.Secret, "r"),
        )
        assertEquals(initial().copy(nextRowId = state.nextRowId), state)
    }

    // --- history restore ------------------------------------------------------

    @Test
    fun loadTranscriptFiltersRolesSentinelsAndEmptyRows() {
        val state = TranscriptEngine.loadTranscript(
            initial(),
            listOf(
                RestoredMessage("user", "hi"),
                RestoredMessage("Assistant", "hello"),
                RestoredMessage("assistant", "Operation interrupted: waiting for model response (2s elapsed)."),
                RestoredMessage("weird", "nope"),
                RestoredMessage("assistant", "   "),
                RestoredMessage("tool", "", toolName = "Bash"),
            ),
        )
        assertEquals(listOf("user", "assistant", "tool"), state.rows.map { it.role })
        assertTrue(state.rows.all { it.completed })
    }

    @Test
    fun prependHistoryFiltersSentinelsAndKeepsOrder() {
        var state = TranscriptEngine.loadTranscript(initial(), listOf(RestoredMessage("user", "recent")))
        state = TranscriptEngine.prependHistory(
            state,
            listOf(
                RestoredMessage("assistant", "Operation interrupted: waiting for model response (9s elapsed)."),
                RestoredMessage("user", "older"),
            ),
        )
        assertEquals(listOf("older", "recent"), state.rows.map { it.text })
    }

    // --- foreground reconcile -------------------------------------------------

    @Test
    fun reconcileAdoptsPersistedReplyInsteadOfStalePartial() {
        var state = TranscriptEngine.loadTranscript(initial(), listOf(RestoredMessage("user", "ask")))
        state = TranscriptEngine.apply(state, ChatEvent.MessageDelta(sid, "stale partial"))
        val result = TranscriptEngine.reconcileForegroundTranscript(
            state,
            listOf(RestoredMessage("user", "ask"), RestoredMessage("assistant", "final reply")),
            turnWasActive = true,
        )
        assertFalse(result.keptLocalSuffix)
        assertEquals(listOf("ask", "final reply"), result.state.rows.map { it.text })
    }

    @Test
    fun reconcileKeepsLocalSuffixWhenReplyNotPersisted() {
        var state = TranscriptEngine.loadTranscript(initial(), emptyList())
        state = TranscriptEngine.appendUserMessage(state, "ask")
        state = TranscriptEngine.apply(state, ChatEvent.MessageDelta(sid, "streaming"))
        val result = TranscriptEngine.reconcileForegroundTranscript(
            state,
            listOf(RestoredMessage("user", "ask")),
            turnWasActive = true,
        )
        assertTrue(result.keptLocalSuffix)
        assertEquals(listOf("ask", "streaming"), result.state.rows.map { it.text })
    }

    // --- inflight / finish / echo ---------------------------------------------

    @Test
    fun ensureInflightExtendsOnlyWithPrefixGrowth() {
        var state = reduce(ChatEvent.MessageDelta(sid, "abc"))
        state = TranscriptEngine.ensureInflightAssistantRow(state, "abcdef", completed = false)
        assertEquals("abcdef", state.rows.single().text)
        state = TranscriptEngine.ensureInflightAssistantRow(state, "different", completed = false)
        assertEquals("abcdef", state.rows.single().text)
    }

    @Test
    fun ensureInflightReopensAssistantRowAfterLatestUser() {
        var state = TranscriptEngine.loadTranscript(
            initial(),
            listOf(RestoredMessage("user", "ask"), RestoredMessage("assistant", "partial")),
        )
        state = TranscriptEngine.ensureInflightAssistantRow(state, "", completed = false)
        assertEquals(2, state.rows.size)
        assertFalse(state.rows[1].completed)
        assertEquals("partial", state.rows[1].text)
    }

    @Test
    fun finishStreamingAssistantCompletesOpenRowsAndTools() {
        var state = reduce(
            ChatEvent.MessageDelta(sid, "open"),
            ChatEvent.ToolStart(sid, "t1", "Bash", null),
        )
        state = TranscriptEngine.finishStreamingAssistant(state)
        assertTrue(state.rows.all { it.completed })
        assertEquals(ToolRowState.Completed, state.tools.single().state)
    }

    @Test
    fun appendUserMessageEchoesCompletedRow() {
        val state = TranscriptEngine.appendUserMessage(initial(), "hi")
        assertEquals("user", state.rows.single().role)
        assertTrue(state.rows.single().completed)
    }

    // --- coalescing -----------------------------------------------------------

    @Test
    fun coalesceGroupsToolRunsAndWorkBursts() {
        var id = 0L
        fun row(role: String, text: String = "", reasoning: String = "") =
            TranscriptRow(id = id++, role = role, text = text, completed = true, reasoningText = reasoning)

        val entries = coalesceTranscriptEntries(
            listOf(
                row("user", "ask"),
                row("assistant", reasoning = "thinking"),
                row("tool", "{}"),
                row("tool", "{}"),
                row("assistant", "answer"),
                row("tool", "{}"),
            ),
        )
        assertEquals(4, entries.size)
        assertIs<TranscriptEntry.Message>(entries[0])
        val burst = assertIs<TranscriptEntry.WorkBurst>(entries[1])
        assertEquals(1, burst.reasoning.size)
        assertEquals(2, burst.tools.size)
        assertIs<TranscriptEntry.Message>(entries[2])
        assertIs<TranscriptEntry.ToolRun>(entries[3])
    }
}
