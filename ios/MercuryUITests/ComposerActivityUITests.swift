import XCTest

/// Exercises the production chat hierarchy with explicitly synthetic states.
final class ComposerActivityUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testChildOutlivesParentWithoutLegacyStrip() {
        let app = launch("Background")
        let line = app.buttons["Composer activity line"]
        XCTAssertTrue(line.waitForExistence(timeout: 10), "Expected one composer-owned activity line")
        XCTAssertEqual(app.buttons.matching(identifier: "Composer activity line").count, 1)
        XCTAssertEqual(line.label, "Activity: 1 background task")
        noLegacy(app)
        XCTAssertTrue(app.textFields["Message Hermes"].exists)
        capture(app, "ios-activity-child-after-parent")
        line.tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
        scrollTo(app.staticTexts["Review lifecycle regressions"], app: app)
        XCTAssertTrue(app.staticTexts["Review lifecycle regressions"].exists)
        XCTAssertTrue(app.buttons["Get progress update (read-only)"].exists)
        capture(app, "ios-activity-background-details")
    }

    func testWorkingLineTimerDetailsAndKeyboardPreserveControls() {
        let app = launch("Working")
        let line = app.buttons["Composer activity line"]
        XCTAssertTrue(line.waitForExistence(timeout: 10))
        XCTAssertTrue(line.label.hasPrefix("Activity: Running"))
        XCTAssertTrue(app.buttons["Stop Hermes response"].isHittable)
        XCTAssertTrue(app.buttons["Change session model"].isHittable)
        XCTAssertTrue(app.buttons["Open session details"].isHittable)
        noLegacy(app)
        let original = line.value as? String
        let ticks = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in (line.value as? String) != original }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ticks], timeout: 5), .completed)
        capture(app, "ios-activity-working")
        line.tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Thinking, expandable"].exists || app.buttons["Reasoning, expandable"].exists)
        scrollTo(app.staticTexts["Tools"], app: app)
        XCTAssertTrue(app.staticTexts["Tools"].exists)
        capture(app, "ios-activity-working-details")
        app.navigationBars["Activity"].buttons["Done"].tap()
        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        composer.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(composer.isHittable)
        XCTAssertTrue(line.isHittable)
        XCTAssertTrue(app.buttons["Stop Hermes response"].isHittable)
        XCTAssertTrue(app.buttons["Change session model"].isHittable)
        XCTAssertGreaterThan(app.keyboards.firstMatch.frame.height, 100)
        XCTAssertLessThan(app.keyboards.firstMatch.frame.minY, app.frame.maxY - 100)
        XCTAssertLessThan(line.frame.maxY, composer.frame.minY + 8)
        capture(app, "ios-activity-working-keyboard")
    }

    func testCompletedTurnFoldsAndSavedActivityRemainsReachable() {
        let app = launch("Complete")
        XCTAssertTrue(app.textFields["Message Hermes"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Composer activity line"].exists)
        noLegacy(app)
        XCTAssertTrue(app.staticTexts["The synthetic build is ready."].exists)
        let fold = app.buttons["Turn activity"]
        XCTAssertEqual(app.buttons.matching(identifier: "Turn activity").count, 1)
        XCTAssertFalse(app.staticTexts["I’ll check the build first."].exists)
        capture(app, "ios-activity-finished")
        fold.tap()
        XCTAssertTrue(app.staticTexts["I’ll check the build first."].waitForExistence(timeout: 5))
        capture(app, "ios-activity-finished-expanded")
        let context = app.buttons["Open session details"]
        XCTAssertTrue(context.isHittable)
        context.tap()
        let saved = app.buttons["Session activity"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        saved.tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Get progress update (read-only)"].exists)
    }

    func testNeedsYouAndOfflineAreStaticAndActionable() {
        let app = launch("Needs you")
        let line = app.buttons["Composer activity line"]
        XCTAssertTrue(line.waitForExistence(timeout: 10))
        XCTAssertEqual(line.label, "Activity: Needs you")
        XCTAssertEqual(line.value as? String, "NeedsYou")
        XCTAssertTrue(app.buttons["Respond to pending request"].isHittable)
        app.buttons["Respond to pending request"].tap()
        XCTAssertTrue(app.staticTexts["Choose a synthetic option"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Staging"].isEnabled, "A running turn must not disable its requested user input")
        app.buttons["Staging"].tap()
        XCTAssertTrue(app.buttons["Continue"].isEnabled)
        capture(app, "ios-activity-needs-you")
        app.terminate()
        let offline = launch("Offline")
        let offlineLine = offline.buttons["Composer activity line"]
        XCTAssertTrue(offlineLine.waitForExistence(timeout: 10))
        XCTAssertEqual(offlineLine.label, "Activity: Connection lost")
        XCTAssertEqual(offlineLine.value as? String, "ConnectionLost")
        offlineLine.tap()
        XCTAssertTrue(offline.buttons["Reconnect to recover job status"].waitForExistence(timeout: 5))
        capture(offline, "ios-activity-offline-details")
    }

    func testThinkingAndIdleHaveNoDuplicatePanels() {
        let app = launch("Thinking")
        let line = app.buttons["Composer activity line"]
        XCTAssertTrue(line.waitForExistence(timeout: 10))
        XCTAssertEqual(line.label, "Activity: Thinking")
        XCTAssertFalse(app.buttons["Thinking, expandable"].exists)
        app.textFields["Message Hermes"].tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        capture(app, "ios-activity-thinking-keyboard")
        app.terminate()
        let idle = launch("Idle")
        XCTAssertTrue(idle.textFields["Message Hermes"].waitForExistence(timeout: 10))
        XCTAssertFalse(idle.buttons["Composer activity line"].exists)
        XCTAssertFalse(idle.buttons["Turn activity"].exists)
        noLegacy(idle)
        capture(idle, "ios-activity-idle")
    }

    func testLongActivityAtLargeTextKeepsTimerAndComposerReachable() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks", "-uitest-activity", "Long", "-uitest-large-text"]
        app.launch()
        let line = app.buttons["Composer activity line"]
        XCTAssertTrue(line.waitForExistence(timeout: 10))
        app.textFields["Message Hermes"].tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(line.isHittable)
        XCTAssertTrue(app.buttons["Stop Hermes response"].isHittable)
        XCTAssertTrue(app.buttons["Change session model"].isHittable)
        XCTAssertTrue(app.buttons["Open session details"].isHittable)
        XCTAssertLessThanOrEqual(line.frame.height, 52)
        capture(app, "ios-activity-long-large-text-keyboard")
    }

    private func launch(_ scenario: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-background-tasks", "-uitest-activity", scenario]
        app.launch()
        return app
    }
    private func noLegacy(_ app: XCUIApplication) {
        for id in ["background-task-strip", "Activity stack", "Active work indicator"] {
            XCTAssertFalse(app.descendants(matching: .any)[id].exists)
        }
        XCTAssertFalse(app.staticTexts["Hermes is responding…"].exists)
    }
    private func scrollTo(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<6 {
            if element.exists && element.isHittable { return }
            app.swipeUp()
        }
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
