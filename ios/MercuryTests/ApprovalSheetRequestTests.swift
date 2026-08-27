import XCTest
@testable import Mercury

final class ApprovalSheetRequestTests: XCTestCase {
    func testClarifyRequestIdentityIsStableAcrossValueCopies() {
        let first = ApprovalSheet.Request.clarify(
            .clarifyRequest(
                sessionID: "session",
                requestID: "request-1",
                question: "Which option?",
                choices: ["A", "B"],
                multiSelect: false
            )
        )
        let second = ApprovalSheet.Request.clarify(
            .clarifyRequest(
                sessionID: "session",
                requestID: "request-1",
                question: "Which option?",
                choices: ["A", "B"],
                multiSelect: false
            )
        )

        XCTAssertEqual(first.id, "clarify:request-1")
        XCTAssertEqual(first.id, second.id)
    }

    func testDifferentClarifyRequestsHaveDifferentIdentities() {
        let first = ApprovalSheet.Request.clarify(
            .clarifyRequest(
                sessionID: "session",
                requestID: "request-1",
                question: "First?",
                choices: [],
                multiSelect: false
            )
        )
        let second = ApprovalSheet.Request.clarify(
            .clarifyRequest(
                sessionID: "session",
                requestID: "request-2",
                question: "Second?",
                choices: [],
                multiSelect: false
            )
        )

        XCTAssertNotEqual(first.id, second.id)
    }
}