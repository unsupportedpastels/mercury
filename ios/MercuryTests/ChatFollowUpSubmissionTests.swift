import Foundation
import XCTest
@testable import Mercury

/// Regression coverage for the iOS prompt-admission race:
/// message.complete/error can be delivered before prompt.submit's correlated
/// response. The terminal event must release the composer gate, while a late
/// failed acknowledgement must not restore the admitted draft or affect the
/// next attempt.
final class ChatFollowUpSubmissionTests: XCTestCase {
    private static func eventEnvelope(type: String, payload: String, sessionID: String = "rt-follow-up") -> String {
        #"{"jsonrpc":"2.0","method":"event","params":{"session_id":"\#(sessionID)","type":"\#(type)","payload":\#(payload)}}"#
    }

    private static func errorFrame(id: Int64, code: Int64) -> String {
        #"{"jsonrpc":"2.0","id":\#(id),"error":{"code":\#(code),"message":"request failed"}}"#
    }

    private func firstEvent(
        from stream: AsyncStream<ChatEvent>,
        timeoutNanoseconds: UInt64 = 2_000_000_000
    ) async throws -> ChatEvent {
        try await withThrowingTaskGroup(of: ChatEvent?.self) { group in
            group.addTask {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: timeoutNanoseconds)
                throw ChatError.transport("timed out waiting for terminal event")
            }
            defer { group.cancelAll() }
            guard let result = try await group.next(), let event = result else {
                throw ChatError.transport("terminal event stream ended")
            }
            return event
        }
    }

    func testTerminalEventBeforeSubmitAckReleasesGateAndPermitsFollowUp() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { [socket] sent in
            guard sent.contains(#""method":"prompt.submit""#) else { return nil }
            // The fake delivers the authoritative terminal event first and the
            // correlated RPC failure second, exactly the ordering under test.
            socket.enqueue(.frame(Self.eventEnvelope(
                type: "message.complete",
                payload: #"{"text":"first response","status":"completed"}"#
            )))
            return Self.errorFrame(id: 1, code: -32000)
        }

        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()
        var lifecycle = PromptSubmissionLifecycle()
        let firstAttempt = try XCTUnwrap(
            lifecycle.begin(draft: "first draft", hostReferenceIDs: ["host-old"])
        )

        let submitFailed = Task { () -> Bool in
            do {
                _ = try await connection.submitPrompt(runtimeSessionID: "rt-follow-up", text: "first draft")
                return false
            } catch {
                return true
            }
        }

        let event = try await firstEvent(from: stream)
        guard case .messageComplete = event else {
            return XCTFail("expected message.complete before submit acknowledgement, got \(event)")
        }
        let terminalEffect = lifecycle.observeTerminal()
        XCTAssertEqual(
            terminalEffect,
            .releasedPending(hostReferenceIDs: ["host-old"])
        )
        if case .releasedPending(let referenceIDs) = terminalEffect {
            var stagedReferenceIDs = ["host-old"]
            stagedReferenceIDs.removeAll { referenceIDs.contains($0) }
            XCTAssertTrue(stagedReferenceIDs.isEmpty)
        }
        XCTAssertEqual(lifecycle.phase, .terminalBeforeAcceptance)

        let submitDidFail = await submitFailed.value
        XCTAssertTrue(submitDidFail)
        // The late failure is authoritative-terminal cleanup, not a draft
        // restoration. A second attempt can therefore be admitted immediately.
        XCTAssertEqual(
            lifecycle.resolve(attempt: firstAttempt, accepted: false),
            .authoritativeTerminal
        )
        XCTAssertEqual(lifecycle.phase, .idle)
        XCTAssertNotNil(lifecycle.begin(draft: "second draft"))

        await connection.close()
    }

    func testLateOldFailureCannotClearReplacementAttemptOrRestoreItsDraft() throws {
        var lifecycle = PromptSubmissionLifecycle()
        let first = try XCTUnwrap(
            lifecycle.begin(draft: "already admitted", hostReferenceIDs: ["host-old"])
        )
        XCTAssertEqual(
            lifecycle.observeTerminal(),
            .releasedPending(hostReferenceIDs: ["host-old"])
        )

        // The user submitted a new draft after the terminal event released the
        // gate, before the old prompt.submit failure arrived.
        let replacement = try XCTUnwrap(
            lifecycle.begin(draft: "new draft", hostReferenceIDs: ["host-new"])
        )
        XCTAssertEqual(
            lifecycle.resolve(attempt: first, accepted: false),
            .stale
        )
        XCTAssertEqual(lifecycle.phase, .awaitingAcceptance)

        XCTAssertEqual(
            lifecycle.resolve(attempt: replacement, accepted: true),
            .accepted(hostReferenceIDs: ["host-new"])
        )
        XCTAssertEqual(lifecycle.phase, .idle)
    }
}
