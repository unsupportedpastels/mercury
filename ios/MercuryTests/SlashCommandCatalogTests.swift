import Foundation
import XCTest
@testable import Mercury

final class SlashCommandCatalogTests: XCTestCase {
    func testModelPickerRequiresExactTrimmedCommand() {
        XCTAssertTrue(isModelPickerCommand("/model"))
        XCTAssertTrue(isModelPickerCommand("  /model\n"))
        XCTAssertFalse(isModelPickerCommand("/models"))
        XCTAssertFalse(isModelPickerCommand("/model gpt-5"))
        XCTAssertFalse(isModelPickerCommand("please /model"))
    }

    func testSteerAcceptsOnlyTheCommandOrSpaceSeparatedPayload() {
        XCTAssertTrue(isSteerCommand("/steer"))
        XCTAssertTrue(isSteerCommand("  /steer Focus on the tests"))
        XCTAssertFalse(isSteerCommand("/steering Focus on the tests"))
        XCTAssertFalse(isSteerCommand("please /steer Focus on the tests"))
        XCTAssertFalse(isSteerCommand("/steer\tFocus on the tests"))
    }

    func testReasoningRequiresOneCanonicalEffort() {
        XCTAssertEqual(reasoningEffortCommand("/reasoning medium"), "medium")
        XCTAssertEqual(reasoningEffortCommand("  /reasoning XHIGH\n"), "xhigh")
        XCTAssertEqual(reasoningEffortCommand("/reasoning none"), "none")
        XCTAssertEqual(reasoningEffortCommand("/reasoning ultra"), "ultra")
        XCTAssertNil(reasoningEffortCommand("/reasoning"))
        XCTAssertNil(reasoningEffortCommand("/reasoning medium extra"))
        XCTAssertNil(reasoningEffortCommand("/reasoning fastest"))
        XCTAssertNil(reasoningEffortCommand("please /reasoning medium"))
    }

    func testRecognizesOnlyLeadingSingleSegmentSlashContexts() {
        for text in ["/", "/h", "/help", "/goal status", "/reasoning h", "/cron ad"] {
            XCTAssertTrue(isSlashCommandContext(text), text)
        }

        for text in ["", "open /help", "/home/user/file", " /help", "what does /goal do"] {
            XCTAssertFalse(isSlashCommandContext(text), text)
        }
    }

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

    func testApplyClampsOffsetsAndOnlyDropsAnImmediatelyDuplicatedSlash() {
        XCTAssertEqual(
            applySlashCompletion("/go", item: SlashCompletionItem(text: "/goal"), replaceFrom: 99),
            "/go/goal"
        )
        XCTAssertEqual(
            applySlashCompletion("/go", item: SlashCompletionItem(text: "goal"), replaceFrom: -3),
            "goal"
        )
        XCTAssertEqual(
            applySlashCompletion("/go", item: SlashCompletionItem(text: "/goal"), replaceFrom: -3),
            "/goal"
        )
        XCTAssertEqual(
            applySlashCompletion("run /he", item: SlashCompletionItem(text: "/help"), replaceFrom: 5),
            "run /help"
        )
    }

    func testApplyUsesWireUTF16Offsets() {
        XCTAssertEqual(
            applySlashCompletion("😀/he", item: SlashCompletionItem(text: "/help"), replaceFrom: 3),
            "😀/help"
        )
    }
}
