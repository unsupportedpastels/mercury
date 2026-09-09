import Foundation
import XCTest
import MercuryCore
@testable import Mercury

final class SessionActivityParityTests: XCTestCase {
    private let snapshotJSON = #"{"name":"todo_list","result":{"revision":4,"todos":[{"id":"a","content":"Run checks","status":"completed"}]}}"#

    @MainActor
    private func state() -> ChatSessionState {
        let state = ChatSessionState(sessionID: "durable", title: "Test", isNewSession: false, incomingShare: nil)
        state.durableID = "durable"
        state.runtimeSessionID = "runtime"
        state.connectionState = .live
        state.transcript.ownSessionIDs = ["durable", "runtime"]
        return state
    }

    @MainActor
    func testNativeActivityStatesUseSharedPriorities() {
        let state = state()
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .hidden)
        XCTAssertEqual(state.activityLine(activeChildCount: 1).label, "1 background task")
        state.isSending = true
        state.transcript.apply(.messageStart(sessionID: "runtime", text: nil))
        XCTAssertEqual(state.activityLine(activeChildCount: 0).label, "Thinking")
        state.transcript.apply(.messageDelta(sessionID: "runtime", text: "An answer"))
        XCTAssertEqual(state.activityLine(activeChildCount: 0).label, "Writing")
        state.transcript.apply(.toolStart(sessionID: "runtime", toolID: "call", name: "terminal", context: "Checking output"))
        XCTAssertEqual(state.activityLine(activeChildCount: 0).label, "Running · Checking output")
        state.connectionState = .offline
        XCTAssertEqual(state.activityLine(activeChildCount: 0).label, "Connection lost")
        XCTAssertFalse(state.activityLine(activeChildCount: 0).animated)
        state.transcript.apply(.clarifyRequest(sessionID: "runtime", requestID: "q", question: "Continue?", choices: [], multiSelect: false))
        XCTAssertEqual(state.activityLine(activeChildCount: 0).label, "Needs you")
        XCTAssertFalse(state.activityLine(activeChildCount: 0).showTimer)
    }

    @MainActor
    func testHistoricalAndOtherRuntimeToolsCannotRefreshProgress() throws {
        let state = state()
        let snapshot = try XCTUnwrap(MercuryCore.DurableProgressBridge.shared.liveSnapshotJson(payloadJson: snapshotJSON))
        state.observeProgress(.toolComplete(sessionID: "runtime", toolID: "a", name: "todo_list", summary: "reported", progressSnapshot: snapshot), atMillis: 10)
        let before = state.progress
        state.observeProgress(.toolStart(sessionID: "other", toolID: "b", name: "terminal", context: nil), atMillis: 20)
        state.observeProgress(.toolComplete(sessionID: "runtime", toolID: "c", name: "terminal", summary: "old", historical: true), atMillis: 30)
        XCTAssertEqual(state.progress, before)
        XCTAssertEqual(state.progress.milestones.first?.content, "Run checks")
    }

    @MainActor
    func testRejectedSendRestoresHistoryButNewObservationWins() throws {
        let state = state()
        let snapshot = try XCTUnwrap(MercuryCore.DurableProgressBridge.shared.liveSnapshotJson(payloadJson: snapshotJSON))
        state.progress = snapshot
        let previous = state.progress
        state.progress = state.progress.beginTurn(atEpochMillis: 100)
        let resetVersion = state.progress.observationVersion
        state.progress = state.progress.restoreUnstartedTurn(previous: previous, resetVersion: resetVersion)
        XCTAssertEqual(state.progress, previous)
        state.progress = previous.beginTurn(atEpochMillis: 200)
        let reset2 = state.progress.observationVersion
        state.observeProgress(.toolStart(sessionID: "runtime", toolID: "new", name: "terminal", context: "new evidence"), atMillis: 210)
        let newer = state.progress
        state.progress = state.progress.restoreUnstartedTurn(previous: previous, resetVersion: reset2)
        XCTAssertEqual(state.progress, newer)
        state.progress = state.progress.recover(history: previous, expectedVersion: reset2)
        XCTAssertEqual(state.progress.evidence.first?.toolCallId, "new")
    }

    @MainActor
    func testRestoredHistoryNeverHidesAnAuthoritativelyRunningTurn() throws {
        // Reopen / foreground recovery: resume reported running=true and the read-only
        // history refresh labeled the progress restored. Liveness comes from the turn
        // and the connection only; `restored` merely labels saved milestone evidence.
        let state = state()
        let rows = #"[{"role":"assistant","tool_calls":[{"id":"plan","function":{"name":"todo_list"}}]},{"role":"tool","tool_call_id":"plan","timestamp":1700000000,"content":{"revision":4,"todos":[{"id":"a","content":"Run checks","status":"in_progress"}]}}]"#
        let history = try TranscriptProgressPage.decode(Data("{\"data\":\(rows)}".utf8)).progress
        state.progress = state.progress.recover(history: history, expectedVersion: state.progress.observationVersion)
        XCTAssertTrue(state.progress.restored)
        state.isSending = true
        state.transcript.apply(.toolStart(sessionID: "runtime", toolID: "call", name: "terminal", context: "Checking output"))
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .working)
        XCTAssertEqual(state.activityLine(activeChildCount: 0).label, "Running · Checking output")
        XCTAssertTrue(state.activityToolRowsLive, "live tool rows are run state, not recovered history")
        XCTAssertFalse(state.activityMilestonesLive, "restored milestones stay labeled saved")
        state.connectionState = .reconnecting(attempt: 1)
        XCTAssertFalse(state.activityToolRowsLive, "a reconnecting socket cannot vouch for a tool row")
        state.connectionState = .live
        state.isSending = false
        XCTAssertFalse(state.activityToolRowsLive)
    }

    func testRESTAndRelayPreserveProgressMetadataAndReportedTimestamp() throws {
        let rows = #"[{"role":"assistant","tool_calls":[{"id":"plan","function":{"name":"todo_list"}}]},{"role":"tool","tool_call_id":"plan","timestamp":1700000000,"content":{"revision":4,"todos":[{"id":"a","content":"Run checks","status":"completed"}]}},{"role":"assistant","content":"Done"}]"#
        let direct = try TranscriptProgressPage.decode(Data("{\"data\":\(rows)}".utf8))
        let raw = try JSONSerialization.jsonObject(with: Data(rows.utf8))
        let relay = try RelayTranscriptPage.decode(["messages": raw], offset: 0, limit: 100)
        XCTAssertEqual(direct.progress, relay.progress)
        XCTAssertEqual(direct.progress.milestones.count, 1)
        XCTAssertEqual(direct.progress.lastObservedAtEpochMillis?.int64Value, 1_700_000_000_000)
        XCTAssertTrue(direct.progress.restored)
        XCTAssertEqual(direct.messages.last?.content, "Done")
        let otherEnvelope = try TranscriptProgressPage.decode(Data("{\"messages\":\(rows)}".utf8))
        XCTAssertEqual(otherEnvelope.progress, direct.progress)
    }

    func testCompletedTurnsFoldSeparatelyAndActiveProseRemainsVisible() {
        var transcript = TranscriptState()
        transcript.loadTranscript([
            .init(role: "user", content: "First"),
            .init(role: "assistant", content: "Checking", reasoningText: "Inspect"),
            .init(role: "tool", content: "OK", toolName: "terminal"),
            .init(role: "assistant", content: "First answer", reasoningText: "Reasoned answer"),
            .init(role: "user", content: "Second"),
            .init(role: "assistant", content: "Second interim"),
            .init(role: "tool", content: "OK", toolName: "read_file"),
            .init(role: "assistant", content: "Second answer"),
        ])
        let completed = foldTranscriptTurns(transcript.rows, turnActive: false)
        XCTAssertEqual(completed.compactMap { if case .activity = $0 { return $0.id }; return nil }.count, 2)
        XCTAssertEqual(Set(completed.map(\.id)).count, completed.count)
        let active = foldTranscriptTurns(transcript.rows, turnActive: true)
        XCTAssertEqual(active.compactMap { if case .activity = $0 { return $0.id }; return nil }.count, 1)
        XCTAssertTrue(active.contains { if case .message(let row) = $0 { return row.text == "Second interim" }; return false })
        XCTAssertEqual(transcript.rows.count, 8, "Presentation must not mutate the transcript")
    }

    func testExportedQuietHoldAndTimerFormatting() {
        let thinking = MercuryCore.ActivityLineState(kind: .working, label: "Thinking", animated: true, showTimer: true)
        let writing = MercuryCore.ActivityLineState(kind: .working, label: "Writing", animated: true, showTimer: true)
        var held = MercuryCore.ActivityLineHoldState(shown: thinking, candidate: nil, candidateSinceMillis: 0)
        held = MercuryCore.ActivityLineHold.shared.step(previous: held, candidate: writing, nowMillis: 10)
        XCTAssertEqual(held.shown?.label, "Thinking")
        held = MercuryCore.ActivityLineHold.shared.step(previous: held, candidate: writing, nowMillis: 1210)
        XCTAssertEqual(held.shown?.label, "Writing")
        let hidden = MercuryCore.ActivityLineState(kind: .hidden, label: "", animated: false, showTimer: false)
        held = MercuryCore.ActivityLineHold.shared.step(previous: held, candidate: hidden, nowMillis: 1211)
        XCTAssertEqual(held.shown?.kind, .hidden)
        XCTAssertEqual(MercuryCore.ActivityLinePolicy.shared.formatElapsed(seconds: 65), "1:05")
    }
}
