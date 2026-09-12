import XCTest
import MercuryCore
@testable import Mercury

final class InternalCompletionNoticeTests: XCTestCase {
    private let notice = "[IMPORTANT: Background process proc_f05837cd4d75 completed normally (exit code 0).\nCommand: python check.py\nOutput:\nverified]"

    func testLiveAndRestoredFoldingPreservesSummariesAndOriginalNotice() {
        for active in [false, true] {
            let rows = [
                TranscriptState.Row(coreID: 1, role: "user", text: "run checks", completed: true),
                TranscriptState.Row(coreID: 2, role: "assistant", text: "Started checks.", completed: true),
                TranscriptState.Row(coreID: 3, role: "user", text: notice, completed: true),
                TranscriptState.Row(coreID: 4, role: "assistant", text: "Checks passed.", completed: !active)
            ]
            let folded = foldTranscriptTurns(rows, turnActive: active)
            let messages = folded.compactMap { entry -> String? in
                if case .message(let row) = entry { return row.text }
                return nil
            }
            XCTAssertEqual(messages, ["run checks", "Started checks.", "Checks passed."])
            guard case .activity(_, let steps, _, let count) = folded[2] else {
                return XCTFail("Expected collapsed activity entry rather than user bubble")
            }
            XCTAssertEqual(count, 1)
            XCTAssertEqual(steps, [rows[2]])
            XCTAssertEqual(steps[0].role, "user")
            XCTAssertEqual(steps[0].text, notice)
        }
    }

    func testOfficialMetadataDecodeRestorePaginationAndCoreRoundTrip() throws {
        let decoded = try JSONDecoder().decode(TranscriptMessage.self, from: Data(
            #"{"role":"user","content":"New envelope","display_kind":"async_delegation_complete"}"#.utf8))
        var state = TranscriptState()
        state.prependHistory([TranscriptState.RestoredMessage(role: decoded.role, content: decoded.content,
            displayKind: decoded.displayKind)])
        let row = try XCTUnwrap(state.rows.first)
        XCTAssertEqual(row.displayKind, "async_delegation_complete")
        XCTAssertTrue(InternalCompletionNotice.shared.isNotice(row: row.core))
        XCTAssertEqual(TranscriptState.Row(row.core), row)
        guard case .activity = foldTranscriptTurns(state.rows, turnActive: true).first else {
            return XCTFail("Typed notification was rendered as chat")
        }
    }

    func testOrdinaryUserQuotesAndAssistantTextRemainMessages() {
        for text in ["IMPORTANT: run checks", "Please explain \(notice)", "> \(notice)", "```\n\(notice)\n```"] {
            let row = TranscriptState.Row(role: "user", text: text, completed: true)
            guard case .message = foldTranscriptTurns([row], turnActive: false).first else {
                return XCTFail("Ordinary user text was folded")
            }
        }
        XCTAssertFalse(InternalCompletionNotice.shared.isNotice(role: "assistant", text: notice,
            displayKind: "async_delegation_complete"))
    }

    func testOfflineMetadataRoundTripAndOlderCacheCompatibility() throws {
        let message = OfflineCachedMessage(role: .user, text: "New envelope", displayKind: "async_delegation_complete")
        XCTAssertEqual(try JSONDecoder().decode(OfflineCachedMessage.self,
            from: JSONEncoder().encode(message)), message)
        let old = try JSONDecoder().decode(OfflineCachedMessage.self,
            from: Data(#"{"role":"user","text":"old text","reasoningText":""}"#.utf8))
        XCTAssertNil(old.displayKind)
    }
}
