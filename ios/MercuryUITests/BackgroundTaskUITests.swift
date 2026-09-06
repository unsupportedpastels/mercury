import XCTest

final class BackgroundTaskUITests: XCTestCase {
    func testRecoveredTerminalHasNoFalseActivityAgeAndKeepsComposer() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks"]
        app.launch()
        XCTAssertTrue(app.buttons["Recover synthetic tasks"].waitForExistence(timeout: 10))
        app.buttons["Recover synthetic tasks"].tap()
        XCTAssertTrue(app.staticTexts["Background tasks · 0 active"].exists)
        app.buttons["Details"].tap()
        XCTAssertEqual(app.staticTexts.matching(identifier: "Review lifecycle regressions").count, 1)
        XCTAssertTrue(app.staticTexts["Finished"].exists)
        XCTAssertTrue(app.staticTexts["Completion time unavailable"].exists)
        XCTAssertTrue(app.textFields["fixture-composer"].isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "shared-recovery-terminal-fixture"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.buttons["Dismiss completed"].tap()
        XCTAssertFalse(app.staticTexts["Background tasks · 0 active"].exists)
    }

    func testDetailsAndComposerStayVisibleAfterParentResponse() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Background tasks · 1 active"].waitForExistence(timeout: 10))
        app.buttons["Details"].tap()
        XCTAssertTrue(app.staticTexts["Review lifecycle regressions"].exists)
        XCTAssertTrue(app.textFields["fixture-composer"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "background-task-details-fixture"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.buttons["Hide details"].tap()
        XCTAssertTrue(app.staticTexts["Background tasks · 1 active"].exists)
    }
}
