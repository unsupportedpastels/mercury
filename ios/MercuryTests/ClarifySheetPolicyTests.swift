import XCTest
@testable import Mercury

/// Android-parity clarify semantics: selectable choice rows, an always-present
/// "Other" free-text field, mutual exclusivity between typing and picking,
/// Skip (empty answer) and Continue, and multi-select joined with ", ".
final class ClarifySheetPolicyTests: XCTestCase {
    func testTypingClearsSelectedChoice() {
        var state = ClarifySheetPolicy.State(choices: ["Option one", "Option two"], multiSelect: false)
        state.select("Option one")
        state.typeAnswer("custom")

        XCTAssertEqual(state.pendingAnswer, "custom")
    }

    func testSelectingChoiceClearsTypedAnswer() {
        var state = ClarifySheetPolicy.State(choices: ["Option one", "Option two"], multiSelect: false)
        state.typeAnswer("custom")
        state.select("Option two")

        XCTAssertEqual(state.pendingAnswer, "Option two")
        XCTAssertEqual(state.answer, "")
    }

    func testMultiSelectJoinsChoicesWithComma() {
        var state = ClarifySheetPolicy.State(choices: ["A", "B", "C"], multiSelect: true)
        state.select("A")
        state.select("C")

        XCTAssertEqual(state.pendingAnswer, "A, C")
    }

    func testToggleDeselectsInMultiSelect() {
        var state = ClarifySheetPolicy.State(choices: ["A", "B"], multiSelect: true)
        state.select("A")
        state.select("A")

        XCTAssertFalse(state.canContinue)
    }

    func testSkipProducesEmptyAnswer() {
        XCTAssertEqual(ClarifySheetPolicy.skipAnswer, "")
    }

    func testContinueDisabledWithoutAnyAnswer() {
        var state = ClarifySheetPolicy.State(choices: ["Only choice"], multiSelect: false)
        XCTAssertFalse(state.canContinue)

        state.select("Only choice")
        XCTAssertTrue(state.canContinue)
    }

    func testOtherFieldLabelDependsOnChoices() {
        XCTAssertEqual(ClarifySheetPolicy.otherFieldLabel(hasChoices: true), "Other")
        XCTAssertEqual(ClarifySheetPolicy.otherFieldLabel(hasChoices: false), "Response")
    }
}
