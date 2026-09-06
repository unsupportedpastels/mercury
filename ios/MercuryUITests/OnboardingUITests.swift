import XCTest

/// Offline onboarding checks; never submits a server or starts authentication.
final class OnboardingUITests: XCTestCase {
    func testEmptyOriginCannotContinueAndKeyboardKeepsAddressVisible() {
        let app = XCUIApplication()
        app.launch()
        let button = app.buttons["Continue"]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        XCTAssertFalse(button.isEnabled, "An empty origin must not be submitted")
        guard !button.isEnabled else { return }

        let field = app.textFields.firstMatch
        field.tap()
        field.typeText("   ")
        XCTAssertFalse(button.isEnabled, "Whitespace is not a server address")
        field.typeText("hermes.example.com")
        XCTAssertTrue(button.isEnabled)
        XCTAssertTrue(field.isHittable)
        XCTAssertTrue(app.keyboards.firstMatch.exists)
        XCTAssertTrue(app.staticTexts["Server address"].exists)
        XCTAssertTrue(app.staticTexts["Relay pairs with your Hermes host by scanning a QR code."].exists)
        let keyboard = XCTAttachment(screenshot: app.screenshot())
        keyboard.name = "mercury-ios-onboarding-keyboard"
        keyboard.lifetime = .keepAlways
        add(keyboard)
        app.terminate()
        app.launch()
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        XCTAssertFalse(button.isEnabled)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        let initial = XCTAttachment(screenshot: app.screenshot())
        initial.name = "mercury-ios-onboarding-updated"
        initial.lifetime = .keepAlways
        add(initial)
    }
}
