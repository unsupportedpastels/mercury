import Foundation
import XCTest
@testable import Mercury
import MercuryRunActivityKit

final class RunActivityReducerTests: XCTestCase {
    private let sessionID = "durable-session-1"
    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private let serverID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!

    private func context(excerptsEnabled: Bool = false) -> RunActivityReducer.Context {
        RunActivityReducer.Context(
            serverID: serverID,
            profile: "default",
            durableSessionID: sessionID,
            sessionTitle: "A session",
            baselineMessageCount: 4,
            excerptsEnabled: excerptsEnabled,
            now: now
        )
    }

    private func reduce(
        _ event: ChatEvent,
        state: inout RunActivityReductionState,
        excerptsEnabled: Bool = false
    ) -> RunActivityCommand {
        RunActivityReducer.reduce(
            event: event,
            state: &state,
            context: context(excerptsEnabled: excerptsEnabled)
        )
    }

    private func complete(
        status: String? = nil,
        text: String? = "final response"
    ) -> ChatEvent {
        .messageComplete(
            sessionID: sessionID,
            text: text,
            status: status,
            error: "provider error must not escape",
            reasoning: "reasoning must not escape",
            warning: "warning must not escape",
            failureReason: "failure detail must not escape",
            recoverable: false,
            billing: nil
        )
    }

    @discardableResult
    private func start(_ state: inout RunActivityReductionState) -> RunActivityCommand {
        reduce(.messageStart(sessionID: sessionID, text: nil), state: &state)
    }

    func testMessageStartStartsExactlyOnce() {
        var state = RunActivityReductionState()

        let first = start(&state)
        guard case .start(let seed) = first else {
            return XCTFail("first messageStart should start the activity")
        }
        XCTAssertEqual(seed.serverID, serverID)
        XCTAssertEqual(seed.profile, "default")
        XCTAssertEqual(seed.durableSessionID, sessionID)
        XCTAssertEqual(seed.sessionTitle, "A session")
        XCTAssertEqual(seed.baselineMessageCount, 4)
        XCTAssertEqual(seed.startedAt, now)
        XCTAssertEqual(seed.initialState.status, .starting)
        XCTAssertEqual(state.startedAt, now)

        let second = start(&state)
        guard case .update(let update) = second else {
            return XCTFail("subsequent messageStart should update, not start again")
        }
        XCTAssertEqual(update.status, .thinking)
        XCTAssertEqual(state.startedAt, now)
    }

    func testMessageDeltaAppendsWithoutTrimmingAndExcerptIsOffByDefault() {
        var state = RunActivityReductionState()
        start(&state)

        _ = reduce(.messageDelta(sessionID: sessionID, text: "  first"), state: &state)
        let command = reduce(.messageDelta(sessionID: sessionID, text: " second  "), state: &state)

        guard case .update(let update) = command else {
            return XCTFail("messageDelta should update the activity")
        }
        XCTAssertEqual(state.rawResponseText, "  first second  ")
        XCTAssertEqual(update.status, .responding)
        XCTAssertEqual(update.responseExcerpt, "")
    }

    func testMessageDeltaIncludesSanitizedExcerptOnlyWhenEnabled() {
        var state = RunActivityReductionState()
        start(&state)

        let command = reduce(
            .messageDelta(sessionID: sessionID, text: "# Heading\n**Visible** response"),
            state: &state,
            excerptsEnabled: true
        )
        guard case .update(let update) = command else {
            return XCTFail("messageDelta should update the activity")
        }
        XCTAssertEqual(update.responseExcerpt, "Visible response")
        XCTAssertFalse(update.responseExcerpt.contains("Heading"))
    }

    func testReasoningIsGenericAndNeverIncludesReasoningText() {
        var state = RunActivityReductionState()
        start(&state)
        let sensitive = "private chain of thought and secret path"

        let command = reduce(
            .reasoningDelta(sessionID: sessionID, text: sensitive, replace: false),
            state: &state
        )
        guard case .update(let update) = command else {
            return XCTFail("reasoningDelta should update the activity")
        }
        XCTAssertEqual(update.status, .thinking)
        XCTAssertEqual(update.activityLine, "Thinking")
        XCTAssertFalse(update.activityLine.contains(sensitive))
        XCTAssertFalse(update.responseExcerpt.contains(sensitive))
    }

    func testInterimAndStatusUpdateAreBoundedSingleLineActivityText() {
        var state = RunActivityReductionState()
        start(&state)

        let interim = reduce(
            .messageInterim(sessionID: sessionID, text: "ignored interim payload", alreadyStreamed: false),
            state: &state
        )
        guard case .update(let interimState) = interim else {
            return XCTFail("messageInterim should update the activity")
        }
        XCTAssertEqual(interimState.status, .responding)
        XCTAssertEqual(interimState.activityLine, "Responding")

        let longStatus = "Running tests\n" + String(repeating: "x", count: 120)
        let status = reduce(
            .statusUpdate(sessionID: sessionID, kind: "tool", text: longStatus),
            state: &state
        )
        guard case .update(let statusState) = status else {
            return XCTFail("statusUpdate should update the activity")
        }
        XCTAssertLessThanOrEqual(statusState.activityLine.count, 80)
        XCTAssertFalse(statusState.activityLine.contains("\n"))
        XCTAssertTrue(statusState.activityLine.hasPrefix("Running tests"))
    }

    func testToolsAddInOrderAndReturnToWorkingStatusWhenRemoved() {
        var state = RunActivityReductionState()
        start(&state)

        _ = reduce(
            .toolGenerating(sessionID: sessionID, name: "read_file"),
            state: &state
        )
        XCTAssertEqual(state.lastStatus, .usingTool)
        XCTAssertEqual(state.activeToolLabels, ["Reading files"])

        _ = reduce(
            .toolStart(sessionID: sessionID, toolID: "tool-1", name: "read_file", context: "secret args"),
            state: &state
        )
        XCTAssertEqual(state.activeToolLabels, ["Reading files"])

        _ = reduce(
            .toolComplete(sessionID: sessionID, toolID: "tool-1", name: "read_file", summary: "private summary"),
            state: &state
        )
        XCTAssertTrue(state.activeToolLabels.isEmpty)
        XCTAssertEqual(state.lastStatus, .thinking)

        let finalTool = reduce(
            .toolComplete(sessionID: sessionID, toolID: "generating-id", name: "terminal.exec", summary: nil),
            state: &state
        )
        guard case .update(let update) = finalTool else {
            return XCTFail("last tool completion should update the working state")
        }
        XCTAssertTrue(state.activeToolLabels.isEmpty)
        XCTAssertEqual(update.status, .thinking)
    }

    func testToolCompletionReturnsToRespondingWhenResponseTextExists() {
        var state = RunActivityReductionState()
        start(&state)
        _ = reduce(.messageDelta(sessionID: sessionID, text: "answer"), state: &state)
        _ = reduce(.toolStart(sessionID: sessionID, toolID: "tool-1", name: "lookup", context: nil), state: &state)

        let command = reduce(
            .toolComplete(sessionID: sessionID, toolID: "tool-1", name: "lookup", summary: nil),
            state: &state
        )
        guard case .update(let update) = command else {
            return XCTFail("tool completion should update the activity")
        }
        XCTAssertEqual(update.status, .responding)
    }

    func testWaitingStatesUseGenericCopyAndExpireBackToWorking() {
        var state = RunActivityReductionState()
        start(&state)

        _ = reduce(
            .approvalRequest(
                sessionID: sessionID,
                requestID: "approval-1",
                command: "rm -rf /private",
                description: "private description",
                choices: ["allow", "deny"]
            ),
            state: &state
        )
        XCTAssertEqual(state.lastStatus, .waitingForApproval)
        XCTAssertEqual(state.awaitingInput, .waitingForApproval)
        XCTAssertEqual(state.lastStatus.map(RunActivityPolicy.displayName), "Needs approval")

        let expiry = reduce(.approvalExpire(sessionID: sessionID, requestID: "approval-1"), state: &state)
        guard case .update(let afterExpiry) = expiry else {
            return XCTFail("approval expiry should resume the activity")
        }
        XCTAssertNil(state.awaitingInput)
        XCTAssertEqual(afterExpiry.status, .thinking)
        XCTAssertFalse(afterExpiry.activityLine.contains("private"))

        _ = reduce(
            .clarifyRequest(
                sessionID: sessionID,
                requestID: "clarify-1",
                question: "private question",
                choices: ["one"],
                multiSelect: false
            ),
            state: &state
        )
        XCTAssertEqual(state.awaitingInput, .waitingForClarification)

        _ = reduce(.clarifyExpire(sessionID: sessionID, requestID: "clarify-1"), state: &state)
        XCTAssertNil(state.awaitingInput)

        _ = reduce(
            .unsupportedBlockingRequest(
                sessionID: sessionID,
                kind: .secret,
                requestID: "secret-1",
                prompt: "enter private secret"
            ),
            state: &state
        )
        XCTAssertEqual(state.awaitingInput, .waitingForSecureInput)
        XCTAssertEqual(state.lastStatus, .waitingForSecureInput)
        XCTAssertFalse(RunActivityPolicy.displayName(for: state.lastStatus!).contains("private"))

        _ = reduce(
            .unsupportedBlockingExpire(sessionID: sessionID, kind: .secret, requestID: "secret-1"),
            state: &state
        )
        XCTAssertNil(state.awaitingInput)
        XCTAssertEqual(state.lastStatus, .thinking)
    }

    func testAutoAnsweredSurfaceRequestsDoNotEnterWaiting() {
        var state = RunActivityReductionState()
        start(&state)
        let before = state

        let command = reduce(
            .unsupportedBlockingRequest(
                sessionID: sessionID,
                kind: .terminalRead,
                requestID: "surface-1",
                prompt: "private prompt"
            ),
            state: &state
        )
        XCTAssertEqual(command, .none)
        XCTAssertNil(state.awaitingInput)
        XCTAssertEqual(state.lastStatus, before.lastStatus)
    }

    func testMessageCompleteMapsFinishedFailedCancelledAndInterruptedWireStatuses() {
        let cases: [(String?, MercuryRunActivityStatus)] = [
            (nil, .complete),
            ("", .complete),
            ("finished", .complete),
            ("error", .failed),
            ("failed", .failed),
            ("cancelled", .cancelled),
            ("canceled", .cancelled),
            ("interrupted", .cancelled)
        ]

        for (wireStatus, expectedStatus) in cases {
            var state = RunActivityReductionState()
            start(&state)
            let command = reduce(complete(status: wireStatus), state: &state, excerptsEnabled: true)
            guard case .end(let finalState) = command else {
                return XCTFail("messageComplete should end for wire status \(String(describing: wireStatus))")
            }
            XCTAssertEqual(finalState.status, expectedStatus)
            XCTAssertTrue(finalState.isFinal)
            XCTAssertTrue(state.finalized)
            if expectedStatus == .complete {
                XCTAssertEqual(finalState.responseExcerpt, "final response")
            } else {
                XCTAssertEqual(finalState.responseExcerpt, "")
            }
        }
    }

    func testErrorEndsWithGenericCopyAndNeverRawError() {
        var state = RunActivityReductionState()
        start(&state)
        let rawError = "provider leaked secret /private/path"

        let command = reduce(.error(sessionID: sessionID, message: rawError), state: &state)
        guard case .end(let finalState) = command else {
            return XCTFail("error should end the activity")
        }
        XCTAssertEqual(finalState.status, .failed)
        XCTAssertEqual(finalState.activityLine, "The run failed.")
        XCTAssertFalse(finalState.activityLine.contains(rawError))
        XCTAssertTrue(state.finalized)

        let duplicate = reduce(complete(), state: &state)
        XCTAssertEqual(duplicate, .none)
    }

    func testDuplicateFinalAndWrongSessionEventsAreNoOps() {
        var finalized = RunActivityReductionState()
        start(&finalized)
        _ = reduce(complete(), state: &finalized)
        let snapshot = finalized
        XCTAssertEqual(reduce(complete(), state: &finalized), .none)
        XCTAssertEqual(finalized, snapshot)

        var fresh = RunActivityReductionState()
        let foreign = ChatEvent.messageDelta(sessionID: "other-session", text: "must be ignored")
        XCTAssertEqual(reduce(foreign, state: &fresh), .none)
        XCTAssertEqual(fresh, RunActivityReductionState())
    }

    func testTitleAndSessionInfoEventsAreNotActivityCommands() {
        var state = RunActivityReductionState()
        XCTAssertEqual(
            reduce(.sessionTitle(sessionID: sessionID, title: "new title"), state: &state),
            .none
        )
        XCTAssertEqual(
            reduce(
                .sessionInfo(
                    sessionID: sessionID,
                    storedSessionID: sessionID,
                    model: "model",
                    provider: "provider",
                    reasoningEffort: nil,
                    fastMode: nil,
                    title: "new title",
                    running: true
                ),
                state: &state
            ),
            .none
        )
    }
}
