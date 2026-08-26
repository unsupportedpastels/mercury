import XCTest
@testable import Mercury

final class TranscriptHistoryTests: XCTestCase {
    func testFullPageMeansMoreHistoryRemains() {
        XCTAssertTrue(TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: TranscriptHistoryPolicy.pageSize))
        XCTAssertFalse(TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: TranscriptHistoryPolicy.pageSize - 1))
        XCTAssertFalse(TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: 0))
    }

    func testNextOffsetUsesAccumulatedLoadedCount() {
        XCTAssertEqual(
            TranscriptHistoryPolicy.nextOffset(loadedCount: 150),
            150
        )
    }
}
