import Foundation

/// Paging policy for "Load earlier" transcript history.
///
/// Released Hermes Serve returns `order=latest` windows in chronological
/// order, and `offset` counts backward from the newest record. A full page
/// therefore implies more history remains; a short page means the session's
/// beginning was reached.
enum TranscriptHistoryPolicy {
    static let pageSize = 50

    static func hasMoreHistory(fetchedCount: Int) -> Bool {
        fetchedCount >= pageSize
    }

    static func nextOffset(loadedCount: Int) -> Int {
        max(0, loadedCount)
    }
}
