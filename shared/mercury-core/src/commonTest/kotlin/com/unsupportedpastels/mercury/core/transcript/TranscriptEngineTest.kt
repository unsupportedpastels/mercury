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
    fun duplicateStartReusesTheOpenAssistantRow() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageDelta(sid, "partial"),
            ChatEvent.MessageStart(sid, "replacement"),
        )
        assertEquals(1, state.rows.size)
        assertEquals("replacement", state.rows.single().text)
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

    @Test
    fun interimCommentarySurvivesToolBoundaryAndTerminalWithoutText() {
        val state = reduce(
            ChatEvent.MessageStart(sid, null),
            ChatEvent.MessageInterim(sid, "I will inspect the files first.", alreadyStreamed = false),
            ChatEvent.ToolStart(sid, "tool-1", "read_file", null),
            ChatEvent.ToolComplete(sid, "tool-1", "read_file", "done"),
            ChatEvent.MessageComplete(sid, text = ""),
        )

        assertEquals("I will inspect the files first.", state.rows.first().text)
        assertTrue(state.rows.first().completed)
        assertEquals(ToolRowState.Completed, state.tools.single().state)
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
    fun secondRequestReplacesPending() {
        val approval = ChatEvent.ApprovalRequest(sid, "r1", "cmd", null, listOf("yes", "no"))
        val clarify = ChatEvent.ClarifyRequest(sid, "r2", "which?", listOf("a"), multiSelect = false)
        var state = reduce(approval)
        assertIs<PendingTranscriptRequest.Approval>(state.pendingRequest)
        state = reduce(clarify, from = state)
        assertIs<PendingTranscriptRequest.Clarify>(state.pendingRequest)
    }

    @Test
    fun expireClearsOnlyTheMatchingKindAndRequestId() {
        val clarify = ChatEvent.ClarifyRequest(sid, "c1", "which?", listOf("a"), multiSelect = false)
        var state = reduce(clarify)
        // Wrong kind, even with the same id, leaves the prompt up.
        state = reduce(ChatEvent.ApprovalExpire(sid, "c1"), from = state)
        assertIs<PendingTranscriptRequest.Clarify>(state.pendingRequest)
        // Right kind, stale id (an earlier request's expiry arriving late).
        state = reduce(ChatEvent.ClarifyExpire(sid, "c0"), from = state)
        assertIs<PendingTranscriptRequest.Clarify>(state.pendingRequest)
        // Exact match clears.
        state = reduce(ChatEvent.ClarifyExpire(sid, "c1"), from = state)
        assertNull(state.pendingRequest)

        val approval = ChatEvent.ApprovalRequest(sid, "r1", "cmd", null, listOf("yes"))
        state = reduce(approval, from = state)
        state = reduce(ChatEvent.ClarifyExpire(sid, "r1"), from = state)
        assertIs<PendingTranscriptRequest.Approval>(state.pendingRequest)
        state = reduce(ChatEvent.ApprovalExpire(sid, "r0"), from = state)
        assertIs<PendingTranscriptRequest.Approval>(state.pendingRequest)
        state = reduce(ChatEvent.ApprovalExpire(sid, "r1"), from = state)
        assertNull(state.pendingRequest)
    }

    @Test
    fun approvalWithoutRequestIdIsNeverExpiredByWire() {
        val approval = ChatEvent.ApprovalRequest(sid, null, "cmd", null, listOf("yes"))
        val state = reduce(approval, ChatEvent.ApprovalExpire(sid, "anything"))
        assertIs<PendingTranscriptRequest.Approval>(state.pendingRequest)
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

    @Test
    fun liveToolActivityDoesNotDropPartiallyCoveredPersistedToolHistory() {
        val rows = buildList {
            add(TranscriptRow(1, "user", "older", completed = true))
            add(TranscriptRow(2, "tool", "old result", completed = true, toolName = "read_file"))
            add(TranscriptRow(3, "assistant", "old answer", completed = true))
            add(TranscriptRow(4, "user", "current", completed = true))
            repeat(10) { index ->
                add(
                    TranscriptRow(
                        id = 5L + index,
                        role = "tool",
                        text = "current result $index",
                        completed = true,
                        toolName = "tool-$index",
                    ),
                )
            }
            add(TranscriptRow(15, "assistant", "current answer", completed = true))
        }

        // Every history row must remain visible until exact invocation identity
        // is available to reconcile it against separately rendered live tools.
        val entries = coalesceTranscriptEntries(rows)

        val toolRuns = entries.filterIsInstance<TranscriptEntry.ToolRun>()
        assertEquals(listOf(1, 10), toolRuns.map { it.rows.size })
        assertEquals(
            listOf("older", "old answer", "current", "current answer"),
            entries.filterIsInstance<TranscriptEntry.Message>().map { it.row.text },
        )
    }

    @Test
    fun paginatedToolOnlyHistoryPageKeepsRowsWhenNoUserIsPresent() {
        val rows = (0 until 10).map { index ->
            TranscriptRow(
                id = index.toLong(),
                role = "tool",
                text = "page result $index",
                completed = true,
                toolName = "tool-$index",
            )
        }

        val entries = coalesceTranscriptEntries(rows)

        val toolRun = assertIs<TranscriptEntry.ToolRun>(entries.single())
        assertEquals(10, toolRun.rows.size)
        assertEquals("page result 0", toolRun.rows.first().text)
        assertEquals("page result 9", toolRun.rows.last().text)
    }

    @Test
    fun fiftyLiveRowsDoNotEraseOlderPersistedRowsBeyondTheLiveWindow() {
        val rows = buildList {
            add(TranscriptRow(1, "user", "current", completed = true))
            // Live RunEventState retains only its final 50 rows, while the
            // persisted transcript page can contain more history than that.
            repeat(60) { index ->
                add(
                    TranscriptRow(
                        id = 2L + index,
                        role = "tool",
                        text = "persisted result $index",
                        completed = true,
                        toolName = "tool-$index",
                    ),
                )
            }
            add(TranscriptRow(62, "assistant", "final answer", completed = true))
        }

        val entries = coalesceTranscriptEntries(rows)

        val toolRun = entries.filterIsInstance<TranscriptEntry.ToolRun>().single()
        assertEquals(60, toolRun.rows.size)
        assertEquals("persisted result 0", toolRun.rows.first().text)
        assertEquals("persisted result 59", toolRun.rows.last().text)
        assertEquals("final answer", entries.filterIsInstance<TranscriptEntry.Message>().last().row.text)
    }
}
