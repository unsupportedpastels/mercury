import XCTest
@testable import Mercury

final class ChatConnectionOwnershipTests: XCTestCase {
    func testStaleConnectionCompletionCannotReleaseReplacement() {
        var ownership = ChatConnectionOwnership()

        let first = ownership.publish()
        let replacement = ownership.publish()

        XCTAssertFalse(ownership.release(ifCurrent: first))
        XCTAssertTrue(ownership.isCurrent(replacement))
        XCTAssertTrue(ownership.release(ifCurrent: replacement))
        XCTAssertFalse(ownership.isCurrent(replacement))
    }

    func testCancelledOrDismissedAttemptCannotPublish() throws {
        var ownership = ChatConnectionOwnership()

        XCTAssertNil(ownership.publish(when: false))

        let active = try XCTUnwrap(ownership.publish(when: true))
        XCTAssertTrue(ownership.isCurrent(active))
    }

    func testInvalidationMakesEveryPublishedCallbackStale() {
        var ownership = ChatConnectionOwnership()
        let published = ownership.publish()

        ownership.invalidate()

        XCTAssertFalse(ownership.isCurrent(published))
        XCTAssertFalse(ownership.release(ifCurrent: published))
    }
}
