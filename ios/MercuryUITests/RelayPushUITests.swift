import XCTest

final class RelayPushUITests: XCTestCase {
    private func launch(unsupported: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-push"] + (unsupported ? ["-uitest-push-unsupported"] : [])
        app.launch()
        XCTAssertTrue(app.staticTexts["push-fixture-status"].waitForExistence(timeout: 15))
        return app
    }
    func testGenericForegroundWithoutRouteAndDirectFallback() {
        let app = launch()
        app.buttons["Deliver generic foreground push"].tap()
        XCTAssertEqual(app.staticTexts["push-fixture-presentation"].label, "Generic banner preserved")
        app.buttons["Deliver direct local notification"].tap()
        XCTAssertEqual(app.staticTexts["push-fixture-presentation"].label, "Direct local banner preserved")
    }
    func testGenericTapResolvesMappedSessionWithoutMutation() {
        let app = launch()
        let active = NSPredicate(format: "label CONTAINS %@", "Relay push active")
        expectation(for: active, evaluatedWith: app.staticTexts["push-fixture-status"])
        waitForExpectations(timeout: 10)
        app.buttons["Tap generic push"].tap()
        XCTAssertTrue(
            app.staticTexts["Session · fixture-session · Push fixture host"].waitForExistence(timeout: 5)
        )
        XCTAssertEqual(app.staticTexts["push-fixture-mutations"].label, "Mutation RPCs: 0")
        XCTAssertFalse(app.buttons["Send"].exists)
    }
    func testUnsupportedHostAndDisabledMappingDoNotNavigate() {
        let app = launch(unsupported: true)
        let unsupported = NSPredicate(format: "label CONTAINS %@", "does not support")
        expectation(for: unsupported, evaluatedWith: app.staticTexts["push-fixture-status"])
        waitForExpectations(timeout: 10)
        app.buttons["Tap generic push"].tap()
        XCTAssertEqual(app.staticTexts["push-fixture-destination"].label, "No host selected")
        XCTAssertEqual(app.staticTexts["push-fixture-mutations"].label, "Mutation RPCs: 0")
        app.terminate()
        let supported = launch()
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Relay push active"), evaluatedWith: supported.staticTexts["push-fixture-status"])
        waitForExpectations(timeout: 10)
        supported.buttons["Disable push"].tap()
        supported.buttons["Tap generic push"].tap()
        XCTAssertEqual(supported.staticTexts["push-fixture-destination"].label, "No host selected")
    }
}
