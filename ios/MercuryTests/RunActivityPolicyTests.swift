import XCTest
import MercuryRunActivityKit

final class RunActivityPolicyTests: XCTestCase {
    func testBlankTitleUsesMercurySessionFallback() {
        XCTAssertEqual(RunActivitySanitizer.sanitizeTitle(" \n\t\r"), "Mercury session")
    }

    func testTitleAndActivityLineRemoveControlsAndBoundLength() {
        let title = RunActivitySanitizer.sanitizeTitle("a\u{0000}\n" + String(repeating: "b", count: 100))
        let line = RunActivitySanitizer.sanitizeActivityLine("a\u{0001}\n" + String(repeating: "b", count: 120))

        XCTAssertLessThanOrEqual(title.count, 60)
        XCTAssertLessThanOrEqual(line.count, 80)
        XCTAssertFalse(title.contains("\n"))
        XCTAssertFalse(title.contains("\u{0000}"))
        XCTAssertFalse(line.contains("\n"))
        XCTAssertFalse(line.contains("\u{0001}"))
    }

    func testToolLabelsUseOnlyGenericCategoriesAndNeverArguments() {
        XCTAssertEqual(
            RunActivitySanitizer.toolLabel(forToolName: "terminal.exec /private/secret.txt"),
            "Running command"
        )
        XCTAssertEqual(RunActivitySanitizer.toolLabel(forToolName: "BASH_RUN"), "Running command")
        XCTAssertEqual(
            RunActivitySanitizer.toolLabel(forToolName: "read_file /Users/example/private.txt"),
            "Reading files"
        )
        XCTAssertEqual(RunActivitySanitizer.toolLabel(forToolName: "json_patch /tmp/private.json"), "Reading files")
        XCTAssertEqual(RunActivitySanitizer.toolLabel(forToolName: "web_search"), "Searching")
        XCTAssertEqual(RunActivitySanitizer.toolLabel(forToolName: "calendar.lookup"), "Using tool")
    }

    func testFinalStatusesAndWaitingStatuses() {
        XCTAssertTrue(MercuryRunActivityStatus.complete.isFinal)
        XCTAssertTrue(MercuryRunActivityStatus.failed.isFinal)
        XCTAssertTrue(MercuryRunActivityStatus.cancelled.isFinal)
        XCTAssertTrue(MercuryRunActivityStatus.statusUnavailable.isFinal)
        XCTAssertFalse(MercuryRunActivityStatus.thinking.isFinal)
        XCTAssertFalse(MercuryRunActivityStatus.reconnecting.isFinal)

        XCTAssertTrue(MercuryRunActivityStatus.waitingForApproval.isWaitingForInput)
        XCTAssertTrue(MercuryRunActivityStatus.waitingForClarification.isWaitingForInput)
        XCTAssertTrue(MercuryRunActivityStatus.waitingForSecureInput.isWaitingForInput)
        XCTAssertFalse(MercuryRunActivityStatus.responding.isWaitingForInput)
    }

    func testDismissalIntervals() {
        XCTAssertEqual(RunActivityPolicy.dismissal(for: .complete)?.interval, 300)
        XCTAssertEqual(RunActivityPolicy.dismissal(for: .failed)?.interval, 30)
        XCTAssertEqual(RunActivityPolicy.dismissal(for: .cancelled)?.interval, 30)
        XCTAssertEqual(RunActivityPolicy.dismissal(for: .statusUnavailable)?.interval, 30)
        XCTAssertNil(RunActivityPolicy.dismissal(for: .thinking))
        XCTAssertNil(RunActivityPolicy.dismissal(for: .waitingForApproval))
    }

    func testResponseExcerptStripsHeadingsMarkdownJoinsParagraphAndCapsAt120() {
        let source = """
        # Private heading

        **First** line with __emphasis__ and `code`.
        ## Another heading
        Second line
        """
        let excerpt = RunActivitySanitizer.responseExcerpt(from: source)

        XCTAssertEqual(excerpt, "First line with emphasis and code. Second line")
        XCTAssertLessThanOrEqual(excerpt.count, 120)
        XCTAssertFalse(excerpt.contains("#"))
        XCTAssertFalse(excerpt.contains("**"))
        XCTAssertFalse(excerpt.contains("__"))
        XCTAssertFalse(excerpt.contains("`"))

        let longText = String(repeating: "x", count: 300)
        XCTAssertEqual(RunActivitySanitizer.responseExcerpt(from: longText).count, 120)
    }

    func testDisplayNamesAreGeneric() {
        XCTAssertEqual(RunActivityPolicy.displayName(for: .starting), "Starting")
        XCTAssertEqual(RunActivityPolicy.displayName(for: .usingTool), "Working")
        XCTAssertEqual(RunActivityPolicy.displayName(for: .waitingForApproval), "Needs approval")
        XCTAssertEqual(RunActivityPolicy.displayName(for: .waitingForClarification), "Needs your input")
        XCTAssertEqual(RunActivityPolicy.displayName(for: .waitingForSecureInput), "Needs secure input")
        XCTAssertEqual(RunActivityPolicy.displayName(for: .statusUnavailable), "Status unknown")
    }
}
