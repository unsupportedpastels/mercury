import XCTest

final class DictationSendUITests: XCTestCase {
    func testSendStopsDictationAndSubmitsExactlyOnce() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-dictation-send"]
        app.launch()

        let send = app.buttons["Queue message"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(send.isEnabled)

        app.buttons["Start simulated dictation"].tap()
        let stopDictation = app.buttons["Stop dictation"]
        XCTAssertTrue(stopDictation.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.buttons["Stop Hermes response"].exists)

        app.buttons["Emit partial transcript"].tap()
        XCTAssertTrue(send.isEnabled, "Send must remain available once dictation produced text")
        send.tap()

        XCTAssertTrue(app.staticTexts["send this dictated message"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Submit count: 1"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Original active response remains streaming"].exists)
        XCTAssertTrue(app.buttons["Dictate message"].waitForExistence(timeout: 5))

        app.buttons["Emit late transcript"].tap()
        XCTAssertTrue(app.staticTexts["send this dictated message"].exists)
        XCTAssertTrue(app.staticTexts["Submit count: 1"].exists)
        let value = (app.textFields["Message composer input"].value as? String) ?? ""
        XCTAssertFalse(value.contains("late transcript"), "Late recognition callbacks must not repopulate the draft")
    }
}
