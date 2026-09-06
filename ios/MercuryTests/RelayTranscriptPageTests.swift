import XCTest
@testable import Mercury

final class RelayTranscriptPageTests: XCTestCase {
    func testEmptyFilteredPageAdvancesRawCursorAndKeepsHistoryAvailable() throws {
        let page = try RelayTranscriptPage.decode(["messages": [], "raw_returned": 100, "next_offset": 200, "has_more": true], offset: 100, limit: 100)
        XCTAssertTrue(page.messages.isEmpty)
        XCTAssertEqual(page.nextOffset, 200)
        XCTAssertTrue(page.hasMore)
    }
    func testOldHostFallbackAndNonAdvancingCursorDoNotLoop() throws {
        let page = try RelayTranscriptPage.decode(["messages": [], "raw_returned": 0, "next_offset": 100, "has_more": true], offset: 100, limit: 100)
        XCTAssertEqual(page.nextOffset, 100)
        XCTAssertFalse(page.hasMore)
        let old = try RelayTranscriptPage.decode(["messages": []], offset: 100, limit: 100)
        XCTAssertFalse(old.hasMore)
    }
}
