import XCTest

final class StartupConnectionUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(_ fixture: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state", fixture]
        app.launch()
        return app
    }

    func testFirstRunWithoutTargetsShowsAddressOnboarding() {
        let app = launch("-uitest-startup-empty")

        XCTAssertTrue(app.staticTexts["Connect to Hermes"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.textFields.firstMatch.exists)
        XCTAssertTrue(app.staticTexts["Server address"].exists)
        XCTAssertTrue(app.buttons["Continue"].exists)
        XCTAssertFalse(app.buttons["Continue"].isEnabled)
        capture("startup-first-run", app: app)
    }

    func testMultipleConfiguredTargetsShowUnifiedPickerWithoutAutoconnect() {
        let app = launch("-uitest-startup-multiple")

        XCTAssertTrue(app.staticTexts["Choose a connection"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Direct test server"].exists)
        XCTAssertTrue(app.staticTexts["Pending relay test"].exists)
        XCTAssertTrue(app.staticTexts["Waiting for host approval"].exists)
        XCTAssertFalse(app.staticTexts["Mercury"].exists)
        capture("startup-connection-picker", app: app)
    }

    func testFailedLastChoiceOffersRetryAndExplicitChooseAnother() {
        let app = launch("-uitest-startup-failed")

        XCTAssertTrue(app.staticTexts["Could not connect to the last server."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Retry"].exists)
        XCTAssertTrue(app.buttons["Choose another"].exists)

        app.buttons["Choose another"].tap()
        XCTAssertTrue(app.staticTexts["Choose a connection"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Direct test server"].exists)
    }

    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
