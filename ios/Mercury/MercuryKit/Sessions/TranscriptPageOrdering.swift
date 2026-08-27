import Foundation

/// Released Hermes Serve returns `order=latest` as the latest bounded page in
/// chronological (oldest-to-newest) order. Preserve that order so the newest
/// completed assistant response remains the bottom transcript row.
enum TranscriptPageOrdering {
    static func forDisplay(_ messages: [TranscriptMessage]) -> [TranscriptMessage] {
        messages
    }
}
