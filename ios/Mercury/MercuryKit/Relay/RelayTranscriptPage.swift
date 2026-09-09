import Foundation
import MercuryCore

/// Relay filtering keeps raw-row cursors authoritative even on an empty visible page.
struct RelayTranscriptPage {
    var messages: [TranscriptMessage]
    var nextOffset: Int
    var hasMore: Bool
    var progress: MercuryCore.DurableProgress = MercuryCore.DurableProgressBridge.shared.initial()
    static func decode(_ result: [String: Any], offset: Int, limit: Int) throws -> Self {
        let raw = result["messages"] as? [[String: Any]] ?? []
        let data = try JSONSerialization.data(withJSONObject: raw)
        let messages = (try? JSONDecoder().decode([TranscriptMessage].self, from: data)) ?? []
        let rawCount = (result["raw_returned"] as? Int).flatMap { (0...limit).contains($0) ? $0 : nil } ?? raw.count
        let next = (result["next_offset"] as? Int).flatMap { $0 >= offset && $0 <= offset + limit ? $0 : nil } ?? offset + rawCount
        let hasMore = (result["has_more"] as? Bool) ?? (rawCount >= limit)
        return Self(messages: messages, nextOffset: next, hasMore: hasMore && next > offset, progress: SessionProgressBridge.parse(raw))
    }
}
