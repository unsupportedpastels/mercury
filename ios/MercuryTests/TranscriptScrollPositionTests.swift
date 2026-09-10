import XCTest
@testable import Mercury

final class TranscriptScrollPositionTests: XCTestCase {
    func testTailBelowViewportIsAwayFromBottom() {
        XCTAssertFalse(TranscriptScrollPosition.isAtBottom(
            tailMaxY: 721,
            viewportHeight: 700,
            tolerance: 2
        ))
    }

    func testTailFlushOrWithinToleranceIsAtBottom() {
        XCTAssertTrue(TranscriptScrollPosition.isAtBottom(
            tailMaxY: 700,
            viewportHeight: 700,
            tolerance: 2
        ))
        XCTAssertTrue(TranscriptScrollPosition.isAtBottom(
            tailMaxY: 702,
            viewportHeight: 700,
            tolerance: 2
        ))
    }

    func testShortContentIsAtBottom() {
        XCTAssertTrue(TranscriptScrollPosition.isAtBottom(
            tailMaxY: 420,
            viewportHeight: 700,
            tolerance: 2
        ))
    }

    func testMissingOrAboveViewportTailIsNotAtBottom() {
        XCTAssertFalse(TranscriptScrollPosition.isAtBottom(
            tailMaxY: nil,
            viewportHeight: 700,
            tolerance: 2
        ))
        XCTAssertFalse(TranscriptScrollPosition.isAtBottom(
            tailMaxY: -3,
            viewportHeight: 700,
            tolerance: 2
        ))
    }
}
