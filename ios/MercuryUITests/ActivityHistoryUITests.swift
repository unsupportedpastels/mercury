import XCTest

/// Synthetic UI evidence only: no live connections, sessions, or credentials.
final class ActivityHistoryUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testProcessHistoryIsNeutralAndUnavailableTasksCanBeDismissed() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks", "-uitest-unavailable-history"]
        app.launch()
        let strip = app.staticTexts["Background tasks · status unavailable"]
        XCTAssertTrue(strip.waitForExistence(timeout: 15))
        XCTAssertFalse(app.descendants(matching: .any)["Active work indicator"].exists)
        app.buttons["Details"].tap()
        XCTAssertTrue(app.staticTexts["Historical task with unavailable status"].waitForExistence(timeout: 5))
        capture("ios-unavailable-activity-history")
        app.buttons["Dismiss unavailable"].tap()
        XCTAssertFalse(strip.exists)
        XCTAssertTrue(app.textFields["fixture-composer"].exists)
        capture("ios-dismissed-activity-history")
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
