import XCTest

/// Synthetic UI evidence only: no live connections, sessions, or credentials.
final class ActivityHistoryUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testProcessHistoryIsNeutralAndUnavailableTasksCanBeDismissed() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks", "-uitest-unavailable-history"]
        app.launch()
        XCTAssertTrue(app.textFields["Message Hermes"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["Composer activity line"].exists)
        app.buttons["Open session details"].tap()
        app.buttons["Session activity"].tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
        scrollTo(app.staticTexts["Historical task with unavailable status"], app: app)
        XCTAssertTrue(app.staticTexts["Historical task with unavailable status"].exists)
        XCTAssertFalse(app.activityIndicators.firstMatch.exists)
        capture("ios-unavailable-activity-history")
        scrollTo(app.buttons["Dismiss unavailable"], app: app)
        app.buttons["Dismiss unavailable"].tap()
        XCTAssertFalse(app.staticTexts["Historical task with unavailable status"].exists)
        let process = app.descendants(matching: .any)["Process-local process fixture-process, running"]
        scrollTo(process, app: app)
        XCTAssertTrue(process.exists)
        app.navigationBars["Activity"].buttons["Done"].tap()
        XCTAssertTrue(app.textFields["Message Hermes"].isHittable)
        XCTAssertFalse(app.buttons["Composer activity line"].exists)
        capture("ios-dismissed-activity-history")
    }
    private func scrollTo(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<6 { if element.exists && element.isHittable { return }; app.swipeUp() }
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
