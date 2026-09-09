import XCTest

final class BackgroundTaskUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testRecoveredTerminalHasNoFalseActivityAgeAndKeepsComposer() {
        let app = launch()
        app.buttons["Fixture controls"].tap()
        app.buttons["Recover synthetic tasks"].tap()
        XCTAssertFalse(app.buttons["Composer activity line"].exists)
        app.buttons["Open session details"].tap()
        app.buttons["Session activity"].tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
        scrollTo(app.staticTexts["Review lifecycle regressions"], app: app)
        XCTAssertEqual(app.staticTexts.matching(identifier: "Review lifecycle regressions").count, 1)
        XCTAssertTrue(app.staticTexts["Finished"].exists)
        XCTAssertFalse(app.staticTexts["Completion time unavailable"].exists)
        capture(app, "shared-recovery-terminal-fixture")
        scrollTo(app.buttons["Dismiss completed"], app: app)
        app.buttons["Dismiss completed"].tap()
        XCTAssertFalse(app.staticTexts["Review lifecycle regressions"].exists)
        app.navigationBars["Activity"].buttons["Done"].tap()
        XCTAssertTrue(app.textFields["Message Hermes"].isHittable)
        XCTAssertFalse(app.buttons["Composer activity line"].exists)
    }

    func testDetailsAndComposerStayVisibleAfterParentResponse() {
        let app = launch()
        let line = app.buttons["Composer activity line"]
        XCTAssertEqual(line.label, "Activity: 1 background task")
        line.tap()
        scrollTo(app.staticTexts["Review lifecycle regressions"], app: app)
        XCTAssertTrue(app.staticTexts["Review lifecycle regressions"].exists)
        capture(app, "background-task-details-fixture")
        app.navigationBars["Activity"].buttons["Done"].tap()
        XCTAssertTrue(app.textFields["Message Hermes"].isHittable)
        XCTAssertEqual(line.label, "Activity: 1 background task")
    }
    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks"]
        app.launch()
        XCTAssertTrue(app.textFields["Message Hermes"].waitForExistence(timeout: 10))
        return app
    }
    private func scrollTo(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<6 { if element.exists && element.isHittable { return }; app.swipeUp() }
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
