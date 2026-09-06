import Foundation

/// Decodes one `relay.sessions.list` result. Any shape the contract does not
/// permit is a protocol violation, never an authoritative empty list: a
/// malformed reply must not make the inbox look empty or clear cached rows.
enum RelaySessionPage {
    static func decode(_ result: [String: Any], offset: Int, limit: Int) throws -> SessionPage {
        guard let rawRows = result["sessions"] as? [[String: Any]] else {
            throw RelayConnectionError.protocolViolation
        }
        let rows: [SessionRow]
        do {
            let data = try JSONSerialization.data(withJSONObject: rawRows)
            rows = try JSONDecoder().decode([SessionRow].self, from: data)
        } catch {
            throw RelayConnectionError.protocolViolation
        }
        let total = result["total"] as? Int
        let hasMore = total.map { offset + rows.count < $0 } ?? (rows.count == limit)
        return SessionPage(rows: rows, total: total, hasMore: hasMore)
    }
}
