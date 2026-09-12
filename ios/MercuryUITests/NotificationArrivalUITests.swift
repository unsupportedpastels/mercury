import XCTest

final class NotificationArrivalUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testDeferredCurrentSuppressedOtherDeliveredOnceAndBackgroundPreserved() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-notification-arrival"]
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            if springboard.buttons["Allow"].exists { springboard.buttons["Allow"].tap() }
            return app.staticTexts["arrival-status"].label == "Ready"
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed)

        app.buttons["Queue then open current"].tap()
        waitLabel(app.staticTexts["arrival-status"], "Delivery finished")
        app.buttons["Read delivered notifications"].tap()
        waitLabel(app.staticTexts["arrival-delivered"], "Delivered: 0")
        XCTAssertFalse(banner(springboard, "Fixture current session").exists)
        capture("current-chat-no-notification")

        app.buttons["Post for other session"].tap()
        XCTAssertTrue(banner(springboard, "Fixture other session").waitForExistence(timeout: 12))
        capture("other-session-real-banner")
        // Let the transient banner leave without tapping/navigating the session.
        waitLabel(app.staticTexts["arrival-status"], "Delivery finished")
        app.buttons["Read delivered notifications"].tap()
        waitLabel(app.staticTexts["arrival-delivered"], "Delivered: 1")
        app.buttons["Repeat same completion"].tap()
        waitLabel(app.staticTexts["arrival-status"], "Repeat finished")
        waitLabel(app.staticTexts["arrival-delivered"], "Delivered: 1")

        app.buttons["Queue then background"].tap()
        waitLabel(app.staticTexts["arrival-status"], "Queued")
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(banner(springboard, "Fixture background").waitForExistence(timeout: 12))
        // SpringBoard publishes the accessibility node before the banner's
        // entry animation. Reopening immediately races arrival and correctly
        // suppresses it as a foreground current-chat notification instead.
        Thread.sleep(forTimeInterval: 1)
        XCTAssertTrue(banner(springboard, "Fixture background").exists)
        capture("background-real-banner")
        // Background arrival is proven by the actual SpringBoard banner.
        // Do not assert cumulative list retention after activating Mercury:
        // returning to the app can retire previously delivered notifications.
        app.activate()
    }

    private func banner(_ springboard: XCUIApplication, _ text: String) -> XCUIElement {
        springboard.otherElements.containing(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }
    private func waitLabel(_ element: XCUIElement, _ label: String) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", label), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 12), .completed,
                       "Expected \(label); actual \(element.exists ? element.label : "missing element")")
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
