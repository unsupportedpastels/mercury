import XCTest
@testable import Mercury

/// Hermetic tests for `TranscriptState`, the pure transcript reducer
/// extracted from ChatView. These pin the EXACT pre-extraction semantics:
/// no session filtering except title adoption, no text bounding (that is
/// ChatConnection's job upstream), and any-expire-clears-pending behavior.
final class TranscriptReducerTests: XCTestCase {

    // MARK: - Helpers

    private func makeState(isNewSession: Bool = false, ownIDs: [String] = []) -> TranscriptState {
        var state = TranscriptState(isNewSession: isNewSession)
        state.ownSessionIDs = Set(ownIDs)
        return state
    }

    private func complete(
        _ sessionID: String = "s1",
        text: String? = "final",
        status: String? = nil,
        error: String? = nil
    ) -> ChatEvent {
        .messageComplete(
            sessionID: sessionID,
            text: text,
            status: status,
            error: error,
            reasoning: nil,
            warning: nil,
            failureReason: nil,
            recoverable: false,
            billing: nil
        )
    }

    // MARK: - messageStart

    /// messageStart appends a fresh incomplete assistant row; nil start text
    /// becomes empty string.
    func testMessageStartAppendsIncompleteAssistantRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: nil))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].role, "assistant")
        XCTAssertEqual(state.rows[0].text, "")
        XCTAssertFalse(state.rows[0].completed)

        state.apply(.messageStart(sessionID: "s1", text: "hello"))
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertEqual(state.rows[1].text, "hello")
        XCTAssertFalse(state.rows[1].completed)
    }

    // MARK: - messageDelta

    /// Deltas append to the LAST incomplete assistant row.
    func testMessageDeltaAppendsToLastIncompleteAssistantRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "He"))
        state.apply(.messageDelta(sessionID: "s1", text: "llo"))
        state.apply(.messageDelta(sessionID: "s1", text: " world"))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "Hello world")
        XCTAssertFalse(state.rows[0].completed)
    }

    /// A delta arriving with no open assistant row still gets a home: it
    /// starts its own incomplete row (pre-extraction parity).
    func testMessageDeltaWithoutStartFrameCreatesRow() {
        var state = makeState()
        state.apply(.messageDelta(sessionID: "s1", text: "orphan"))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].role, "assistant")
        XCTAssertEqual(state.rows[0].text, "orphan")
        XCTAssertFalse(state.rows[0].completed)
    }

    /// Completed rows and user rows are never reopened by deltas; the last
    /// INCOMPLETE assistant row wins.
    func testMessageDeltaTargetsLastIncompleteAssistantRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "first"))
        state.apply(complete(text: "first-done"))
        state.appendUserMessage("hi")
        state.apply(.messageStart(sessionID: "s1", text: ""))
        state.apply(.messageDelta(sessionID: "s1", text: "second"))
        XCTAssertEqual(state.rows.count, 3)
        XCTAssertEqual(state.rows[2].text, "second")
        XCTAssertEqual(state.rows[0].text, "first-done")
        XCTAssertTrue(state.rows[0].completed)
    }

    // MARK: - messageComplete

    /// Authoritative final text replaces the streamed buffer when present;
    /// nil final text keeps the buffer.
    func testMessageCompleteReplacesBufferOnlyWhenTextPresent() {
        var withText = makeState()
        withText.apply(.messageStart(sessionID: "s1", text: "strea"))
        withText.apply(complete(text: "authoritative"))
        XCTAssertTrue(withText.rows[0].completed)
        XCTAssertEqual(withText.rows[0].text, "authoritative")

        var withoutText = makeState()
        withoutText.apply(.messageStart(sessionID: "s1", text: "strea"))
        withoutText.apply(complete(text: nil))
        XCTAssertTrue(withoutText.rows[0].completed)
        XCTAssertEqual(withoutText.rows[0].text, "strea")
        XCTAssertEqual(withoutText.rows.count, 1)
    }

    /// The server's "Operation interrupted: waiting for model response
    /// (Ns elapsed)." final text is cancellation metadata (hermes-agent
    /// #7921): it must never replace streamed text.
    func testInterruptSentinelKeepsStreamedBuffer() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "partial an"))
        state.apply(complete(
            text: "Operation interrupted: waiting for model response (3.0s elapsed).",
            status: "interrupted"
        ))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertTrue(state.rows[0].completed)
        XCTAssertEqual(state.rows[0].text, "partial an")
    }

    /// A sentinel completing a row that streamed nothing visible removes the
    /// row entirely — no blank assistant bubble.
    func testInterruptSentinelRemovesEmptyRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: nil))
        state.apply(complete(
            text: "Operation interrupted: waiting for model response (12.4s elapsed).",
            status: "interrupted"
        ))
        XCTAssertTrue(state.rows.isEmpty)
    }

    /// A sentinel with no open assistant row appends nothing.
    func testInterruptSentinelWithoutOpenRowAppendsNothing() {
        var state = makeState()
        state.apply(complete(
            text: "Operation interrupted: waiting for model response (0.4s elapsed).",
            status: "interrupted"
        ))
        XCTAssertTrue(state.rows.isEmpty)
    }

    /// A sentinel row that streamed reasoning keeps the row (the disclosure
    /// still has value), just without sentinel prose.
    func testInterruptSentinelKeepsRowWithReasoning() {
        var state = makeState()
        state.apply(.reasoningDelta(sessionID: "s1", text: "thinking…", replace: false))
        state.apply(complete(
            text: "Operation interrupted: waiting for model response (5.0s elapsed).",
            status: "interrupted"
        ))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "")
        XCTAssertEqual(state.rows[0].reasoningText, "thinking…")
        XCTAssertTrue(state.rows[0].completed)
    }

    /// Ordinary prose that merely mentions an interruption is NOT the
    /// sentinel; only the exact stable prefix is suppressed.
    func testInterruptSentinelPrefixIsExact() {
        var state = makeState()
        state.apply(complete(text: "Operation interrupted: the build was cancelled."))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "Operation interrupted: the build was cancelled.")
    }

    /// Persisted sentinels (servers predating the upstream transcript fix)
    /// are dropped on history restore.
    func testLoadTranscriptDropsPersistedInterruptSentinel() {
        var state = makeState()
        state.loadTranscript([
            (role: "user", content: "hi"),
            (role: "assistant", content: "Operation interrupted: waiting for model response (3.0s elapsed)."),
            (role: "assistant", content: "real answer"),
        ])
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertEqual(state.rows[0].text, "hi")
        XCTAssertEqual(state.rows[1].text, "real answer")
    }

    /// Older-history pagination uses a separate prepend path and must apply
    /// the same persisted-interruption filtering as initial transcript load.
    func testPrependHistoryDropsPersistedInterruptSentinel() {
        var state = makeState()
        state.loadTranscript([
            (role: "user", content: "older user"),
            (role: "assistant", content: "current answer"),
        ])
        state.prependHistory([
            TranscriptState.RestoredMessage(
                role: "assistant",
                content: "Operation interrupted: waiting for model response (3.0s elapsed)."
            ),
            TranscriptState.RestoredMessage(role: "user", content: "older real answer"),
        ])
        XCTAssertEqual(state.rows.map(\.text), ["older real answer", "older user", "current answer"])
    }

    /// Completing with no open assistant row appends a completed row only
    /// when text is present; nil text + no open row is a no-op.
    func testMessageCompleteWithoutOpenRow() {
        var appended = makeState()
        appended.apply(complete(text: "cold finish"))
        XCTAssertEqual(appended.rows.count, 1)
        XCTAssertEqual(appended.rows[0].role, "assistant")
        XCTAssertEqual(appended.rows[0].text, "cold finish")
        XCTAssertTrue(appended.rows[0].completed)

        var noop = makeState()
        noop.apply(complete(text: nil))
        XCTAssertTrue(noop.rows.isEmpty)
    }

    /// Completion marks only the last open assistant row; earlier rows stay
    /// untouched.
    func testMessageCompleteFinalizesOnlyLastOpenAssistantRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "one"))
        state.apply(.messageStart(sessionID: "s1", text: "two"))
        state.apply(complete(text: "two-final"))
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertFalse(state.rows[0].completed)
        XCTAssertEqual(state.rows[0].text, "one")
        XCTAssertTrue(state.rows[1].completed)
        XCTAssertEqual(state.rows[1].text, "two-final")
    }

    // MARK: - error

    /// Error events record their message for presentation; they do NOT
    /// create transcript rows (pre-extraction parity).
    func testErrorCapturesMessageWithoutCreatingRows() {
        var state = makeState()
        state.apply(.error(sessionID: "s1", message: "boom"))
        XCTAssertTrue(state.rows.isEmpty)
        XCTAssertEqual(state.lastError, "boom")

        state.apply(.error(sessionID: "s1", message: "boom again"))
        XCTAssertEqual(state.lastError, "boom again")
    }

    // MARK: - Approval / clarify lifecycle

    /// approvalRequest captures the event as a pending approval, request id
    /// included.
    func testApprovalRequestSetsPendingApproval() {
        var state = makeState()
        let event = ChatEvent.approvalRequest(
            sessionID: "s1",
            requestID: "req-7",
            command: "rm -rf /",
            description: "dangerous",
            choices: ["allow", "deny"]
        )
        state.apply(event)
        XCTAssertEqual(state.pendingRequest, .approval(event))
    }

    /// approvalRequest with a nil request id also captures cleanly.
    func testApprovalRequestWithNilRequestIDSetsPendingApproval() {
        var state = makeState()
        let event = ChatEvent.approvalRequest(
            sessionID: "s1",
            requestID: nil,
            command: nil,
            description: nil,
            choices: ["yes"]
        )
        state.apply(event)
        XCTAssertEqual(state.pendingRequest, .approval(event))
    }

    /// clarifyRequest captures as pending clarify.
    func testClarifyRequestSetsPendingClarify() {
        var state = makeState()
        let event = ChatEvent.clarifyRequest(
            sessionID: "s1",
            requestID: "q-1",
            question: "Which?",
            choices: ["a", "b"],
            multiSelect: false
        )
        state.apply(event)
        XCTAssertEqual(state.pendingRequest, .clarify(event))
    }

    /// Pre-extraction parity: a second request REPLACES whichever request is
    /// pending, regardless of kind.
    func testSecondRequestReplacesPendingRequest() {
        var state = makeState()
        let approval = ChatEvent.approvalRequest(
            sessionID: "s1", requestID: "r1", command: nil, description: nil, choices: ["ok"]
        )
        state.apply(approval)
        let clarify = ChatEvent.clarifyRequest(
            sessionID: "s1", requestID: "c1", question: "?", choices: [], multiSelect: false
        )
        state.apply(clarify)
        XCTAssertEqual(state.pendingRequest, .clarify(clarify))
    }

    /// Any expire clears whichever request is pending — expires are not
    /// matched by request ID or kind (pre-extraction parity).
    func testAnyExpireClearsAnyPendingRequest() {
        var state = makeState()
        state.apply(ChatEvent.clarifyRequest(
            sessionID: "s1", requestID: "c1", question: "?", choices: [], multiSelect: false
        ))
        state.apply(ChatEvent.approvalExpire(sessionID: "s1", requestID: "unrelated-id"))
        XCTAssertNil(state.pendingRequest)

        state.apply(ChatEvent.approvalRequest(
            sessionID: "s1", requestID: "r2", command: nil, description: nil, choices: ["go"]
        ))
        state.apply(ChatEvent.clarifyExpire(sessionID: "s1", requestID: "also-unrelated"))
        XCTAssertNil(state.pendingRequest)
    }

    /// Expire with nothing pending is a no-op.
    func testExpireWithNothingPendingIsNoOp() {
        var state = makeState()
        state.apply(ChatEvent.approvalExpire(sessionID: "s1", requestID: "x"))
        state.apply(ChatEvent.clarifyExpire(sessionID: "s1", requestID: "y"))
        XCTAssertNil(state.pendingRequest)
        XCTAssertTrue(state.rows.isEmpty)
    }

    // MARK: - Session title adoption

    /// New-chat flow adopts a live rename for an owned session id.
    func testSessionTitleAdoptedForOwnedSessionWhenNew() {
        var state = makeState(isNewSession: true, ownIDs: ["runtime-1"])
        state.apply(.sessionTitle(sessionID: "runtime-1", title: "Renamed live"))
        XCTAssertEqual(state.adoptedTitle, "Renamed live")
    }

    /// Foreign-session titles are ignored even in the new-chat flow.
    func testSessionTitleIgnoredForForeignSession() {
        var state = makeState(isNewSession: true, ownIDs: ["runtime-1"])
        state.apply(.sessionTitle(sessionID: "other-session", title: "Not mine"))
        XCTAssertNil(state.adoptedTitle)
    }

    /// Existing-session flow never adopts live renames (title comes from
    /// navigation until the list refreshes).
    func testSessionTitleIgnoredWhenNotNewSession() {
        var state = makeState(isNewSession: false, ownIDs: ["durable-9"])
        state.apply(.sessionTitle(sessionID: "durable-9", title: "Ignored"))
        XCTAssertNil(state.adoptedTitle)
    }

    /// An empty navigation session id is never seeded into ownSessionIDs, so
    /// it can never match — mirroring the original
    /// `!sessionID.isEmpty &&` guard.
    func testEmptyNavigationSessionIDNeverMatches() {
        var state = makeState(isNewSession: true)
        XCTAssertTrue(state.ownSessionIDs.isEmpty)
        state.apply(.sessionTitle(sessionID: "", title: "Blank match attempt"))
        XCTAssertNil(state.adoptedTitle)
    }

    /// Runtime AND durable ids both count as owned once learned.
    func testRuntimeAndDurableIDsBothMatch() {
        var state = makeState(isNewSession: true, ownIDs: ["rt-1", "dur-2"])
        state.apply(.sessionTitle(sessionID: "dur-2", title: "via durable"))
        XCTAssertEqual(state.adoptedTitle, "via durable")
        state.apply(.sessionTitle(sessionID: "rt-1", title: "via runtime"))
        XCTAssertEqual(state.adoptedTitle, "via runtime")
    }

    // MARK: - statusUpdate

    /// Status updates record their text and count so views can detect
    /// arrivals even when the text repeats.
    func testStatusUpdateRecordsTextAndCount() {
        var state = makeState()
        state.apply(.statusUpdate(sessionID: "s1", kind: "tool", text: "running tests"))
        XCTAssertEqual(state.latestStatusText, "running tests")
        XCTAssertEqual(state.statusUpdateCount, 1)

        state.apply(.statusUpdate(sessionID: "s1", kind: "tool", text: "running tests"))
        XCTAssertEqual(state.statusUpdateCount, 2)
        XCTAssertTrue(state.rows.isEmpty)
    }

    // MARK: - Unhandled events are no-ops

    /// Event kinds this slice does not model fall through default: break —
    /// no rows, no pending request, no captured title/error. (Reasoning,
    /// interim, and tool events have dedicated semantics below.)
    func testUnhandledEventsAreNoOps() {
        var state = makeState(isNewSession: true, ownIDs: ["s1"])
        state.apply(.sessionInfo(
            sessionID: "s1", storedSessionID: nil, model: "m", provider: "p",
            reasoningEffort: nil, fastMode: nil, title: "info-title", running: false
        ))

        XCTAssertTrue(state.rows.isEmpty)
        XCTAssertTrue(state.tools.isEmpty)
        XCTAssertNil(state.pendingRequest)
        XCTAssertNil(state.adoptedTitle)
        XCTAssertNil(state.lastError)
        XCTAssertNil(state.generatingStatusText)
        XCTAssertEqual(state.statusUpdateCount, 0)
    }

    // MARK: - Non-event mutations

    /// REST history loads as completed rows in caller-supplied display order,
    /// replacing any live rows.
    func testLoadTranscriptReplacesWithCompletedRows() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "live"))
        state.loadTranscript([
            (role: "user", content: "question"),
            (role: "assistant", content: "answer"),
        ])
        XCTAssertEqual(state.rows, [
            TranscriptState.Row(role: "user", text: "question", completed: true),
            TranscriptState.Row(role: "assistant", text: "answer", completed: true),
        ])
    }

    func testForegroundReconciliationPreservesTurnMissingFromRESTHistory() {
        var state = makeState()
        state.loadTranscript([
            TranscriptState.RestoredMessage(role: "user", content: "Earlier question"),
            TranscriptState.RestoredMessage(role: "assistant", content: "Earlier answer"),
        ])
        state.appendUserMessage("Still running")
        state.apply(.messageStart(sessionID: "s1", text: "Partial reply"))

        let stillActive = state.reconcileForegroundTranscript(
            [
                TranscriptState.RestoredMessage(role: "user", content: "Earlier question"),
                TranscriptState.RestoredMessage(role: "assistant", content: "Earlier answer"),
            ],
            turnWasActive: true
        )

        XCTAssertTrue(stillActive)
        XCTAssertEqual(state.rows.map(\.text), [
            "Earlier question", "Earlier answer", "Still running", "Partial reply",
        ])
        XCTAssertFalse(state.rows.last?.completed ?? true)
    }

    func testForegroundReconciliationAdoptsCompletedRESTReplyInsteadOfStalePartial() {
        var state = makeState()
        state.appendUserMessage("Still running")
        state.apply(.messageStart(sessionID: "s1", text: "Stale partial"))

        let stillActive = state.reconcileForegroundTranscript(
            [
                TranscriptState.RestoredMessage(role: "user", content: "Still running"),
                TranscriptState.RestoredMessage(role: "assistant", content: "Finished while away"),
            ],
            turnWasActive: true
        )

        XCTAssertFalse(stillActive)
        XCTAssertEqual(state.rows.map(\.text), ["Still running", "Finished while away"])
        XCTAssertTrue(state.rows.allSatisfy(\.completed))
    }

    func testPrependHistoryAddsOlderWindowAboveNewestPage() {
        var state = makeState()
        state.loadTranscript([
            TranscriptState.RestoredMessage(role: "assistant", content: "Final answer"),
        ])

        state.prependHistory([
            TranscriptState.RestoredMessage(role: "user", content: "Older question"),
            TranscriptState.RestoredMessage(
                role: "tool",
                content: "older result",
                toolName: "read_file"
            ),
            TranscriptState.RestoredMessage(role: "assistant", content: "", reasoningText: ""),
        ])

        XCTAssertEqual(state.rows.count, 3)
        XCTAssertEqual(state.rows.first?.text, "Older question")
        XCTAssertEqual(state.rows.last?.text, "Final answer")
    }

    func testReasoningOnlyRowsCollapseIntoOneTurnActivityWithTheirTools() {
        let rows: [TranscriptState.Row] = [
            .init(role: "assistant", text: "", completed: true, reasoningText: "Planning the fix"),
            .init(role: "tool", text: "result-1", completed: true, toolName: "terminal"),
            .init(role: "assistant", text: "", completed: true, reasoningText: "Verifying"),
            .init(role: "tool", text: "result-2", completed: true, toolName: "read_file"),
            .init(role: "assistant", text: "Done.", completed: true),
        ]

        let entries = coalesceTranscriptEntries(rows)

        XCTAssertEqual(entries.count, 2)
        guard case .workBurst(let reasoning, let tools) = entries[0] else {
            return XCTFail("expected one consolidated turn activity, got \(entries)")
        }
        XCTAssertEqual(reasoning.map(\.reasoningText), ["Planning the fix", "Verifying"])
        XCTAssertEqual(tools.map(\.toolName), ["terminal", "read_file"])
        guard case .message(let final) = entries[1] else {
            return XCTFail("expected trailing prose message, got \(entries)")
        }
        XCTAssertEqual(final.text, "Done.")
    }

    func testConsecutiveReasoningWithoutToolsStillCollapsesIntoOneTurnActivity() {
        let rows: [TranscriptState.Row] = [
            .init(role: "assistant", text: "", completed: true, reasoningText: "Inspecting"),
            .init(role: "assistant", text: "", completed: false, reasoningText: "Still working"),
        ]

        let entries = coalesceTranscriptEntries(rows)

        XCTAssertEqual(entries.count, 1)
        guard case .workBurst(let reasoning, let tools) = entries[0] else {
            return XCTFail("expected consolidated reasoning activity, got \(entries)")
        }
        XCTAssertEqual(reasoning.count, 2)
        XCTAssertTrue(tools.isEmpty)
    }

    func testRestoredToolRowsStayTypedAndAdjacentRunsCoalesce() {
        var state = makeState()
        state.loadTranscript([
            TranscriptState.RestoredMessage(role: "user", content: "question"),
            TranscriptState.RestoredMessage(
                role: "tool",
                content: "{\"success\":true}",
                toolName: "read_file"
            ),
            TranscriptState.RestoredMessage(
                role: "tool",
                content: "{\"output\":\"ok\"}",
                toolName: "terminal"
            ),
            TranscriptState.RestoredMessage(
                role: "assistant",
                content: "Done.",
                reasoningText: "Checked both results."
            ),
        ])

        XCTAssertEqual(state.rows[1].role, "tool")
        XCTAssertEqual(state.rows[1].toolName, "read_file")
        XCTAssertEqual(state.rows[3].reasoningText, "Checked both results.")

        let entries = coalesceTranscriptEntries(state.rows)
        XCTAssertEqual(entries.count, 3)
        guard case .toolRun(let tools) = entries[1] else {
            return XCTFail("Adjacent restored tool rows must render as one collapsed run")
        }
        XCTAssertEqual(tools.map(\.toolName), ["read_file", "terminal"])
    }

    /// Resume parity: ensureInflightAssistantRow inserts one streaming row
    /// and never duplicates on re-resume.
    func testEnsureInflightAssistantRowGuardsAgainstDuplicates() {
        var state = makeState()
        state.ensureInflightAssistantRow(text: "partial answer", completed: false)
        state.ensureInflightAssistantRow(text: "duplicate attempt", completed: false)
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "partial answer")
        XCTAssertFalse(state.rows[0].completed)

        // Pin the guard's exact shape: a COMPLETED assistant row does not
        // count as open, so ensure after completion appends.
        var done = makeState()
        done.ensureInflightAssistantRow(text: "finished", completed: true)
        done.ensureInflightAssistantRow(text: "again", completed: false)
        XCTAssertEqual(done.rows.count, 2)

        // A completed REST row after the latest user prompt is reopened for
        // an active resume instead of receiving deltas in a second bubble.
        var restored = makeState()
        restored.loadTranscript([
            TranscriptState.RestoredMessage(role: "user", content: "question"),
            TranscriptState.RestoredMessage(role: "assistant", content: "partial"),
        ])
        restored.ensureInflightAssistantRow(text: "partial", completed: false)
        restored.apply(.messageDelta(sessionID: "s1", text: " continuation"))
        XCTAssertEqual(restored.rows.count, 2)
        XCTAssertEqual(restored.rows[1].text, "partial continuation")
        XCTAssertFalse(restored.rows[1].completed)
    }

    func testFinishStreamingAssistantFinalizesRestoredActiveRows() {
        var state = makeState()
        state.loadTranscript([
            TranscriptState.RestoredMessage(role: "user", content: "question"),
            TranscriptState.RestoredMessage(role: "assistant", content: "partial"),
        ])
        state.ensureInflightAssistantRow(text: "partial", completed: false)
        state.finishStreamingAssistant()
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertTrue(state.rows[1].completed)
    }

    /// User echo rows are completed user-role rows.
    func testAppendUserMessage() {
        var state = makeState()
        state.appendUserMessage("hello there")
        XCTAssertEqual(state.rows, [
            TranscriptState.Row(role: "user", text: "hello there", completed: true)
        ])
    }

    // MARK: - Value semantics

    // MARK: - Reasoning deltas (M5.1)

    /// Non-replacing reasoning deltas append to the last incomplete
    /// assistant row's reasoning buffer.
    func testReasoningDeltaAppendsToStreamingRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: ""))
        state.apply(.reasoningDelta(sessionID: "s1", text: "think", replace: false))
        state.apply(.reasoningDelta(sessionID: "s1", text: "ing", replace: false))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "")
        XCTAssertEqual(state.rows[0].reasoningText, "thinking")
        XCTAssertFalse(state.rows[0].completed)
    }

    /// A replacing delta overwrites the accumulated buffer instead of
    /// appending.
    func testReasoningDeltaReplaceOverwritesBuffer() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: ""))
        state.apply(.reasoningDelta(sessionID: "s1", text: "stale draft", replace: false))
        state.apply(.reasoningDelta(sessionID: "s1", text: "authoritative snapshot", replace: true))
        state.apply(.reasoningDelta(sessionID: "s1", text: "+append", replace: false))
        XCTAssertEqual(state.rows[0].reasoningText, "authoritative snapshot+append")
    }

    /// Blank reasoning deltas are ignored entirely — no row created, no
    /// mutation of the existing buffer.
    func testReasoningDeltaBlankIsIgnored() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: ""))
        state.apply(.reasoningDelta(sessionID: "s1", text: "  \n\t ", replace: false))
        state.apply(.reasoningDelta(sessionID: "s1", text: "", replace: true))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].reasoningText, "")

        var empty = makeState()
        empty.apply(.reasoningDelta(sessionID: "s1", text: "", replace: false))
        XCTAssertTrue(empty.rows.isEmpty)
    }

    /// Reasoning with no streaming assistant row opens a fresh incomplete
    /// assistant row whose TEXT is empty and reasoning holds the delta;
    /// subsequent message deltas land in that row's text buffer.
    func testReasoningDeltaWithoutStreamingRowCreatesReasoningOnlyRow() {
        var state = makeState()
        state.apply(.reasoningDelta(sessionID: "s1", text: "pondering", replace: false))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].role, "assistant")
        XCTAssertEqual(state.rows[0].text, "")
        XCTAssertEqual(state.rows[0].reasoningText, "pondering")
        XCTAssertFalse(state.rows[0].completed)

        state.apply(.messageDelta(sessionID: "s1", text: "answer"))
        XCTAssertEqual(state.rows[0].text, "answer")
        XCTAssertEqual(state.rows[0].reasoningText, "pondering")
    }

    /// Completed rows are never reopened by reasoning; the last INCOMPLETE
    /// assistant row wins. Reasoning survives completion for collapsed
    /// display.
    func testReasoningRetainedAfterCompletionAndTargetsLastIncompleteRow() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "one"))
        state.apply(complete(text: "one-final"))
        state.apply(.messageStart(sessionID: "s1", text: "two"))
        state.apply(.reasoningDelta(sessionID: "s1", text: "second-turn thoughts", replace: false))
        // The completed first segment is untouched; the open one received it.
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertEqual(state.rows[0].reasoningText, "")
        XCTAssertEqual(state.rows[1].reasoningText, "second-turn thoughts")

        // Reasoning survives completion so the UI can render it collapsed.
        state.apply(complete(text: "two-final"))
        XCTAssertEqual(state.rows[1].text, "two-final")
        XCTAssertEqual(state.rows[1].reasoningText, "second-turn thoughts")
        XCTAssertTrue(state.rows[1].completed)
    }

    // MARK: - Interim commentary (M5.2)

    /// Interim text seals the current streaming segment: the open assistant
    /// row takes the interim text verbatim and completes. Later deltas open
    /// a fresh incomplete row.
    func testInterimSealsOpenAssistantSegment() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "partial"))
        state.apply(.messageInterim(sessionID: "s1", text: "let me check…", alreadyStreamed: true))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "let me check…")
        XCTAssertTrue(state.rows[0].completed)

        // A later delta opens a FRESH row rather than reopening the sealed one.
        state.apply(.messageDelta(sessionID: "s1", text: "after tools"))
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertEqual(state.rows[1].text, "after tools")
        XCTAssertFalse(state.rows[1].completed)
    }

    /// Interim with no open segment appends a completed assistant row.
    func testInterimWithoutOpenRowAppendsCompleted() {
        var state = makeState()
        state.appendUserMessage("hi")
        state.apply(.messageInterim(sessionID: "s1", text: "cold interim", alreadyStreamed: false))
        XCTAssertEqual(state.rows.count, 2)
        XCTAssertEqual(state.rows[1].role, "assistant")
        XCTAssertEqual(state.rows[1].text, "cold interim")
        XCTAssertTrue(state.rows[1].completed)
    }

    /// `alreadyStreamed` carries no extra reducer behavior (Android parity):
    /// both values seal/append identically.
    func testInterimAlreadyStreamedHasNoExtraBehavior() {
        var streamed = makeState()
        streamed.apply(.messageInterim(sessionID: "s1", text: "x", alreadyStreamed: true))
        var notStreamed = makeState()
        notStreamed.apply(.messageInterim(sessionID: "s1", text: "x", alreadyStreamed: false))
        XCTAssertEqual(streamed.rows, notStreamed.rows)
    }

    /// Blank interim text is ignored — nothing sealed, nothing appended.
    func testInterimBlankIsIgnored() {
        var state = makeState()
        state.apply(.messageStart(sessionID: "s1", text: "open"))
        state.apply(.messageInterim(sessionID: "s1", text: "   ", alreadyStreamed: true))
        state.apply(.messageInterim(sessionID: "s1", text: "", alreadyStreamed: false))
        XCTAssertEqual(state.rows.count, 1)
        XCTAssertEqual(state.rows[0].text, "open")
        XCTAssertFalse(state.rows[0].completed)
    }

    // MARK: - Tool activity rows (M5.3)

    /// Full start→complete lifecycle: start creates a running row with its
    /// context; complete replaces in place with the summary while PRESERVING
    /// the start frame's context, and clears any generating status.
    func testToolStartCompleteLifecycle() {
        var state = makeState()
        state.apply(.toolGenerating(sessionID: "s1", name: "shell"))
        XCTAssertEqual(state.generatingStatusText, "Generating shell arguments…")

        state.apply(.toolStart(
            sessionID: "s1", toolID: "t1", name: "shell",
            context: "run the tests"
        ))
        XCTAssertEqual(state.tools.count, 1)
        XCTAssertEqual(state.tools[0].toolID, "t1")
        XCTAssertEqual(state.tools[0].name, "shell")
        XCTAssertEqual(state.tools[0].context, "run the tests")
        XCTAssertNil(state.tools[0].summary)
        XCTAssertEqual(state.tools[0].state, .running)
        XCTAssertNil(state.generatingStatusText)

        state.apply(.toolComplete(sessionID: "s1", toolID: "t1", name: "shell", summary: "42 passed"))
        XCTAssertEqual(state.tools.count, 1)
        XCTAssertEqual(state.tools[0].context, "run the tests", "context from start must survive completion")
        XCTAssertEqual(state.tools[0].summary, "42 passed")
        XCTAssertEqual(state.tools[0].state, .completed)
    }

    /// A complete arriving without a prior start still records a completed
    /// row (no context available).
    func testToolCompleteWithoutStartAppendsCompletedRow() {
        var state = makeState()
        state.apply(.toolComplete(sessionID: "s1", toolID: "orphan", name: "fetch", summary: nil))
        XCTAssertEqual(state.tools.count, 1)
        XCTAssertEqual(state.tools[0].toolID, "orphan")
        XCTAssertEqual(state.tools[0].name, "fetch")
        XCTAssertNil(state.tools[0].context)
        XCTAssertNil(state.tools[0].summary)
        XCTAssertEqual(state.tools[0].state, .completed)
    }

    /// A start for an already-completed tool id is ignored entirely — no
    /// replacement, no state change, and the generating status is untouched.
    func testToolStartAfterCompleteIsNoOp() {
        var state = makeState()
        state.apply(.toolStart(sessionID: "s1", toolID: "t1", name: "shell", context: "ctx"))
        state.apply(.toolComplete(sessionID: "s1", toolID: "t1", name: "shell", summary: "done"))

        state.apply(.toolStart(
            sessionID: "s1", toolID: "t1", name: "renamed", context: "new ctx"
        ))
        XCTAssertEqual(state.tools.count, 1)
        XCTAssertEqual(state.tools[0].name, "shell")
        XCTAssertEqual(state.tools[0].context, "ctx")
        XCTAssertEqual(state.tools[0].summary, "done")
        XCTAssertEqual(state.tools[0].state, .completed)
    }

    /// A second start for an id that is STILL RUNNING replaces in place
    /// (updated name/context) rather than duplicating.
    func testToolRestartWhileRunningReplacesInPlace() {
        var state = makeState()
        state.apply(.toolStart(sessionID: "s1", toolID: "t1", name: "shell", context: "first"))
        state.apply(.toolStart(sessionID: "s1", toolID: "t1", name: "shell-v2", context: nil))
        XCTAssertEqual(state.tools.count, 1)
        XCTAssertEqual(state.tools[0].name, "shell-v2")
        XCTAssertNil(state.tools[0].context)
        XCTAssertEqual(state.tools[0].state, .running)
    }

    /// Appending beyond 50 rows keeps only the LAST 50.
    func testToolRowsBoundedAtFifty() {
        var state = makeState()
        for index in 0..<55 {
            state.apply(.toolStart(sessionID: "s1", toolID: "t\(index)", name: "tool\(index)", context: nil))
        }
        XCTAssertEqual(state.tools.count, TranscriptState.maxToolRows)
        XCTAssertEqual(state.tools.first?.toolID, "t5", "oldest rows are dropped first")
        XCTAssertEqual(state.tools.last?.toolID, "t54")

        // Completion of an old-but-retained id still finds its row.
        state.apply(.toolComplete(sessionID: "s1", toolID: "t10", name: "tool10", summary: "ok"))
        XCTAssertEqual(state.tools.first(where: { $0.toolID == "t10" })?.state, .completed)
    }

    /// Tool ids and names bound at 256 chars; context and summary at 4096.
    func testToolFieldBounds() {
        let long256 = String(repeating: "a", count: 300)
        let long4096 = String(repeating: "b", count: 5000)
        var state = makeState()
        state.apply(.toolGenerating(sessionID: "s1", name: long256))
        state.apply(.toolStart(
            sessionID: "s1", toolID: long256, name: long256, context: long4096
        ))
        XCTAssertEqual(state.tools[0].toolID.count, 256)
        XCTAssertEqual(state.tools[0].name.count, 256)
        XCTAssertEqual(state.tools[0].context?.count, 4096)

        state.apply(.toolComplete(
            sessionID: "s1", toolID: long256, name: long256, summary: long4096
        ))
        XCTAssertEqual(state.tools[0].summary?.count, 4096)
    }

    /// Generating status format, bounding, and clearing by both
    /// toolStart and toolComplete.
    func testGeneratingStatusSetAndCleared() {
        var state = makeState()
        state.apply(.toolGenerating(sessionID: "s1", name: "web_search"))
        XCTAssertEqual(state.generatingStatusText, "Generating web_search arguments…")

        // Cleared by toolStart…
        state.apply(.toolStart(sessionID: "s1", toolID: "t1", name: "web_search", context: nil))
        XCTAssertNil(state.generatingStatusText)

        state.apply(.toolGenerating(sessionID: "s1", name: "fetch"))
        // …and by toolComplete.
        state.apply(.toolComplete(sessionID: "s1", toolID: "t2", name: "fetch", summary: nil))
        XCTAssertNil(state.generatingStatusText)

        // The name itself bounds at 256 chars first (Android parity), so a
        // huge name yields "Generating " + 256 chars + " arguments…" = 278 —
        // well under the 4096 whole-string cap.
        var huge = makeState()
        huge.apply(.toolGenerating(sessionID: "s1", name: String(repeating: "z", count: 5000)))
        XCTAssertEqual(
            huge.generatingStatusText,
            "Generating " + String(repeating: "z", count: 256) + " arguments…"
        )
    }

    /// messageComplete finalizes ALL running tool rows and clears the
    /// generating status; already-completed rows keep their summaries.
    func testMessageCompleteFinishesRunningTools() {
        var state = makeState()
        state.apply(.toolStart(sessionID: "s1", toolID: "t1", name: "shell", context: nil))
        state.apply(.toolComplete(sessionID: "s1", toolID: "t1", name: "shell", summary: "kept"))
        state.apply(.toolStart(sessionID: "s1", toolID: "t2", name: "fetch", context: "ctx"))
        state.apply(.toolStart(sessionID: "s1", toolID: "t3", name: "edit", context: nil))
        state.apply(.toolGenerating(sessionID: "s1", name: "next-tool"))

        state.apply(.messageStart(sessionID: "s1", text: "wrapping up"))
        state.apply(complete(text: "final answer"))

        XCTAssertEqual(state.tools.first(where: { $0.toolID == "t1" })?.summary, "kept")
        XCTAssertEqual(state.tools.first(where: { $0.toolID == "t2" })?.state, .completed)
        XCTAssertEqual(state.tools.first(where: { $0.toolID == "t3" })?.state, .completed)
        XCTAssertNil(state.generatingStatusText)
    }

    /// Error events also finish running tools and clear the generating
    /// status while recording lastError.
    func testErrorFinishesRunningTools() {
        var state = makeState()
        state.apply(.toolStart(sessionID: "s1", toolID: "t1", name: "shell", context: nil))
        state.apply(.toolGenerating(sessionID: "s1", name: "another"))

        state.apply(.error(sessionID: "s1", message: "turn failed"))
        XCTAssertEqual(state.lastError, "turn failed")
        XCTAssertEqual(state.tools.count, 1)
        XCTAssertEqual(state.tools[0].toolID, "t1")
        XCTAssertEqual(state.tools[0].state, .completed)
        XCTAssertNil(state.generatingStatusText)
    }

    // MARK: - Value semantics

    /// The reducer is a value type: applying events to a copy must not leak
    /// into the original.
    func testValueSemanticsKeepCopiesIndependent() {
        var original = makeState()
        original.apply(.messageStart(sessionID: "s1", text: "kept"))
        var copy = original
        copy.apply(complete(text: "changed"))
        XCTAssertEqual(original.rows[0].text, "kept")
        XCTAssertFalse(original.rows[0].completed)
        XCTAssertTrue(copy.rows[0].completed)
    }
}
