import XCTest
import MercuryCore
@testable import Mercury

/// Synthetic adapter/reducer lifecycle coverage; not a mounted navigation or live-server test.
@MainActor
final class FirstResponseReopenInvestigationTests: XCTestCase {
    private let answer = "FIRST ANSWER BEGIN\n\nEvery synthetic paragraph survives.\n\nFIRST ANSWER END"
    private let prompt = "Explain the synthetic result."

    private func visibleAnswers(_ state: TranscriptState) -> [String] {
        foldTranscriptTurns(state.rows, turnActive: false).compactMap {
            if case .message(let row) = $0, row.role == "assistant" { return row.text }
            return nil
        }
    }

    private func completeFirstResponse(tools: Bool) -> TranscriptState {
        var state = TranscriptState(isNewSession: true)
        state.appendUserMessage(prompt)
        if tools {
            state.apply(.messageInterim(sessionID: "synthetic-runtime", text: "Checking synthetic data.", alreadyStreamed: false))
            state.apply(.toolStart(sessionID: "synthetic-runtime", toolID: "t1", name: "fixture", context: "synthetic"))
        }
        state.apply(.messageStart(sessionID: "synthetic-runtime", text: nil))
        state.apply(.messageDelta(sessionID: "synthetic-runtime", text: answer))
        state.apply(.messageComplete(sessionID: "synthetic-runtime", text: answer, status: nil, error: nil, reasoning: nil,
                                     warning: nil, failureReason: nil, recoverable: false, billing: nil))
        XCTAssertFalse(state.hasStreamingAssistant)
        XCTAssertEqual(visibleAnswers(state), [answer])
        return state
    }

    private func wireRows(tools: Bool) -> [[String: Any]] {
        var rows: [[String: Any]] = [["role": "user", "content": prompt]]
        if tools {
            rows.append(["role": "assistant", "content": "Checking synthetic data.", "tool_calls": [["id": "t1"]]])
            rows.append(["role": "tool", "content": "synthetic result", "tool_name": "fixture", "tool_call_id": "t1"])
        }
        rows.append(["role": "assistant", "content": answer, "finish_reason": "stop"])
        return rows
    }

    func testPostResumeDurablePublicationPreservesAllSegmentsAndRowIdentity() {
        let reopened = ChatView(sessionID: "synthetic-durable", title: "Synthetic")
        let rows = reopened.transcriptRows(from: [
            ["role": "user", "content": prompt],
            ["role": "assistant", "content": "Synthetic first answer alpha."],
            ["role": "assistant", "content": "Synthetic first answer omega."]
        ])
        reopened.state.transcript.loadTranscript(rows)
        let before = reopened.state.transcript.rows
        let authority = TranscriptReadPolicy.shared.afterResume(hasDisplayRows: !rows.isEmpty)
        XCTAssertFalse(authority.publishesTranscript)
        let stale = reopened.transcriptRows(from: [["role": "user", "content": prompt]])
        for fetched in [stale, rows, rows + rows] {
            reopened.applyDurableDisplayRows(fetched, sourceAuthority: authority)
            XCTAssertEqual(reopened.state.transcript.rows, before)
            XCTAssertEqual(reopened.state.transcript.rows.map(\.coreID), before.map(\.coreID))
        }
    }

    func testAuthoritativeResumeInvalidatesDurablePaginationAndMetadataCannotRestoreIt() {
        let reopened = ChatView(sessionID: "synthetic-durable", title: "Synthetic")
        reopened.state.adoptHistoryPagination(loadedCount: 100, hasMore: true)
        let resumeRows = (1...125).map {
            TranscriptState.RestoredMessage(
                role: $0.isMultiple(of: 2) ? "assistant" : "user",
                content: "Resume row \($0)"
            )
        }

        reopened.publishResumeDisplayRows(resumeRows)
        XCTAssertNil(reopened.state.historyPaginationRequest())
        XCTAssertEqual(reopened.state.loadedTranscriptCount, 0)
        XCTAssertFalse(reopened.state.hasMoreHistory)

        reopened.applyHistoryPagination(loadedCount: 100, hasMore: true, sourceAuthority: .resume)
        XCTAssertNil(reopened.state.historyPaginationRequest(),
                     "A metadata-only durable read must not restore a mismatched cursor")
        XCTAssertFalse(reopened.state.hasMoreHistory)
        XCTAssertEqual(reopened.state.transcript.rows.count, 125)
    }

    func testHistoryPublicationRestoresPaginationAndOwnershipChangeRejectsInflightPage() throws {
        let reopened = ChatView(sessionID: "synthetic-durable", title: "Synthetic")
        reopened.applyHistoryPagination(loadedCount: 100, hasMore: true, sourceAuthority: .history)
        let request = try XCTUnwrap(reopened.state.historyPaginationRequest())
        XCTAssertEqual(request.loadedCount, 100)

        reopened.publishResumeDisplayRows([
            TranscriptState.RestoredMessage(role: "assistant", content: "Authoritative resume")
        ])
        XCTAssertFalse(reopened.state.acceptsHistoryPaginationResponse(generation: request.generation))

        reopened.applyDurableDisplayRows([
            TranscriptState.RestoredMessage(role: "assistant", content: "History fallback")
        ], sourceAuthority: .history)
        reopened.applyHistoryPagination(loadedCount: 50, hasMore: true, sourceAuthority: .history)
        XCTAssertEqual(reopened.state.historyPaginationRequest()?.loadedCount, 50)
        XCTAssertTrue(reopened.state.hasMoreHistory)
    }

    func testEmptyResumeFallsBackAndInitialExplicitHistoryCanReplaceOrRewind() {
        let reopened = ChatView(sessionID: "synthetic-durable", title: "Synthetic")
        let full = reopened.transcriptRows(from: wireRows(tools: true))
        reopened.applyDurableDisplayRows(full)
        XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer])
        reopened.applyDurableDisplayRows([], sourceAuthority: .history)
        XCTAssertTrue(reopened.state.transcript.rows.isEmpty)
        reopened.applyDurableDisplayRows(full,
            sourceAuthority: TranscriptReadPolicy.shared.afterResume(hasDisplayRows: false))
        XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer])
        reopened.applyDurableDisplayRows(Array(full.prefix(1)))
        XCTAssertEqual(reopened.state.transcript.rows.map(\.text), [prompt])
    }

    func testCompletedFirstResponseLeaveReopenThroughRESTDecodeAndResumeMapping() throws {
        for tools in [false, true] {
            _ = completeFirstResponse(tools: tools)
            // A different detail owner on reopen must not rely on retained live rows.
            let reopened = ChatView(sessionID: "synthetic-durable", title: "Synthetic")
            let wire = wireRows(tools: tools)
            let page = try TranscriptProgressPage.decode(JSONSerialization.data(withJSONObject: ["messages": wire]))
            reopened.state.transcript.loadTranscript(TranscriptPageOrdering.forDisplay(page.messages).map {
                TranscriptState.RestoredMessage(role: $0.role, content: $0.content, toolName: $0.toolName,
                                                reasoningText: $0.reasoningText, displayKind: $0.displayKind)
            })
            XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer], "REST restore must retain the entire first answer")
            // Production resume mapping, then the idle post-resume durable refresh.
            reopened.state.transcript.loadTranscript(reopened.transcriptRows(from: wire))
            XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer], "Resume must not erase any paragraph")
            reopened.state.transcript.loadTranscript(reopened.transcriptRows(from: wire))
            reopened.state.transcript.finishStreamingAssistant()
            XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer])
        }
    }

    func testCompletedFirstToolResponseLeaveReopenThroughRelayPageAndPagination() throws {
        _ = completeFirstResponse(tools: true)
        let reopened = ChatView(sessionID: "synthetic-durable", title: "Synthetic")
        let wire = wireRows(tools: true)
        let newest = try RelayTranscriptPage.decode(["messages": [wire.last!], "raw_returned": 1,
            "next_offset": 1, "has_more": true], offset: 0, limit: 1)
        reopened.state.transcript.loadTranscript(newest.messages.map {
            TranscriptState.RestoredMessage(role: $0.role, content: $0.content, toolName: $0.toolName)
        })
        XCTAssertEqual(newest.nextOffset, 1)
        XCTAssertTrue(newest.hasMore)
        XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer])
        let older = try RelayTranscriptPage.decode(["messages": Array(wire.dropLast()), "raw_returned": 3,
            "next_offset": 4, "has_more": false], offset: newest.nextOffset, limit: 100)
        reopened.state.transcript.prependHistory(older.messages.map {
            TranscriptState.RestoredMessage(role: $0.role, content: $0.content, toolName: $0.toolName)
        })
        XCTAssertFalse(older.hasMore)
        XCTAssertEqual(visibleAnswers(reopened.state.transcript), [answer])
        XCTAssertEqual(reopened.state.transcript.rows.map(\.text), [prompt, "Checking synthetic data.", "synthetic result", answer])
    }
}
