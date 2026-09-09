import XCTest
@testable import Mercury

/// A registry poll that ends on a transient RPC failure hides a still-running
/// silent child once its activity window expires (PR #70 review).
final class BackgroundRegistryPollPolicyTests: XCTestCase {
    func testTransientFailuresRetryWhileUnsupportedMethodAndDeadConnectionStop() {
        XCTAssertTrue(ChatView.registryPollContinues(after: ChatError.protocolError("Hermes RPC request failed (-32000)"),
                                                     connectionClosed: false))
        XCTAssertTrue(ChatView.registryPollContinues(after: ChatError.transport("timed out"), connectionClosed: false))
        XCTAssertFalse(ChatView.registryPollContinues(after: ChatMethodNotFoundError(method: "delegation.status"),
                                                      connectionClosed: false))
        XCTAssertFalse(ChatView.registryPollContinues(after: CancellationError(), connectionClosed: false))
        XCTAssertFalse(ChatView.registryPollContinues(after: ChatError.transport("closed"), connectionClosed: true))
    }
}
