import Foundation
import XCTest
@testable import Mercury

final class SlashCommandCatalogTests: XCTestCase {
    func testAppliesRootAndArgumentCompletionsWithDesktopReplaceSemantics() {
        XCTAssertEqual(
            applySlashCompletion("/he", item: SlashCompletionItem(text: "help"), replaceFrom: 1),
            "/help"
        )
        XCTAssertEqual(
            applySlashCompletion("/det", item: SlashCompletionItem(text: "/details"), replaceFrom: 1),
            "/details"
        )
        XCTAssertEqual(
            applySlashCompletion("/reasoning h", item: SlashCompletionItem(text: "high"), replaceFrom: 11),
            "/reasoning high"
        )
        XCTAssertEqual(
            applySlashCompletion("/goa extra", item: SlashCompletionItem(text: "goal"), replaceFrom: 1),
            "/goal"
        )
    }

    func testApplyUsesWireUTF16Offsets() {
        XCTAssertEqual(
            applySlashCompletion("😀/he", item: SlashCompletionItem(text: "/help"), replaceFrom: 3),
            "😀/help"
        )
    }
}
