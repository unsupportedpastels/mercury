import Foundation
import XCTest
@testable import Mercury

/// Regression coverage for optional model capability hydration and direct-mode
/// recovery admission. These tests use the real ChatConnection request state
/// machine rather than a TaskGroup timeout wrapper.
@MainActor
final class ChatConnectionRecoveryTests: XCTestCase {
    func testNeverReplyingModelOptionsTimesOutWithoutClosingOrRetainingRPC() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"prompt.submit""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":2,"result":{"status":"queued"}}"#
        }
        let connection = try ChatConnection(
            socket: socket,
            modelOptionsTimeoutNanoseconds: 20_000_000
        )
        _ = connection.start()

        let view = ChatView(sessionID: "durable-1", title: "Chat")
        let state = view.state
        state.connection = connection
        state.runtimeSessionID = "runtime-1"

        let hydrated = try await view.hydrateModelOptions(
            using: connection,
            runtimeSessionID: "runtime-1"
        )

        XCTAssertNil(hydrated, "optional metadata failure must not block chat admission")
        XCTAssertTrue(state.modelFeatureSupported)
        XCTAssertEqual(connection.pendingRequestCount, 0)
        XCTAssertFalse(connection.isClosed)
        XCTAssertFalse(socket.wasClosed, "model.options timeout must not close the chat channel")

        // The same admitted channel remains usable after the timed-out request;
        // this also proves a later response is not wedged behind a stale pending
        // continuation.
        let submission = try await connection.submitPrompt(
            runtimeSessionID: "runtime-1",
            text: "still usable"
        )
        XCTAssertEqual(submission.status, "queued")
        XCTAssertEqual(connection.pendingRequestCount, 0)
        XCTAssertFalse(socket.wasClosed)

        await connection.close()
    }

    func testCancelledModelOptionsRequestRemovesPendingRPCWithoutClosingChannel() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(
            socket: socket,
            modelOptionsTimeoutNanoseconds: 5_000_000_000
        )
        _ = connection.start()

        let request = Task {
            try await connection.loadModelOptions(runtimeSessionID: "runtime-1")
        }
        var registered = false
        for _ in 0..<100 {
            if connection.pendingRequestCount == 1 {
                registered = true
                break
            }
            await Task.yield()
        }
        XCTAssertTrue(registered, "the never-replying request must register before cancellation")

        request.cancel()
        do {
            _ = try await request.value
            XCTFail("cancelling the RPC must throw")
        } catch {
            XCTAssertTrue(error is CancellationError, "cancellation must propagate as CancellationError, got \(error)")
        }

        XCTAssertEqual(connection.pendingRequestCount, 0)
        XCTAssertFalse(connection.isClosed)
        XCTAssertFalse(socket.wasClosed)
        await connection.close()
    }

    func testDirectAutomaticResumeRequiresExplicitRetry() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)

        do {
            _ = try await connection.resume(
                durableSessionID: "durable-1",
                profile: "default",
                automaticRecovery: true
            )
            XCTFail("direct automatic recovery must not attempt session.resume")
        } catch let error as ChatError {
            XCTAssertEqual(
                error,
                .transport("Explicit retry required to resume this session")
            )
        }

        XCTAssertEqual(connection.pendingRequestCount, 0)
        XCTAssertNil(socket.lastSent)
        XCTAssertFalse(socket.wasClosed)
        await connection.close()
    }

    func testRecoveryPolicyKeepsExplicitRetryDistinctFromImplicitForegroundRecovery() {
        XCTAssertEqual(ChatConnection.defaultModelOptionsTimeoutNanoseconds, 5_000_000_000)
        XCTAssertTrue(
            ChatConnectionRecoveryPolicy.allowsResume(
                for: .explicitUserAction,
                isRelay: false
            )
        )
        XCTAssertFalse(
            ChatConnectionRecoveryPolicy.allowsResume(
                for: .automatic,
                isRelay: false
            )
        )
        XCTAssertTrue(
            ChatConnectionRecoveryPolicy.allowsResume(
                for: .automatic,
                isRelay: true
            )
        )
        XCTAssertEqual(
            ChatConnectionRecoveryPolicy.explicitRetryRequiredMessage,
            "This session needs an explicit retry to reconnect."
        )
    }
}
