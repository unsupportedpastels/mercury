import XCTest

/// Uses the unchanged official fake-Hermes file route with a real H264 MP4
/// fixture supplied by MERCURY_E2E_VIDEO_FILE on the backend. No test-only
/// playback bypass: authentication, extraction, disk download and AVKit run.
final class ManagedVideoUITests: XCTestCase {
    func testManagedVideoPlaysLocallyAndDisposesOnBackground() throws {
        guard ProcessInfo.processInfo.environment["FAKE_HERMES_VIDEO"] == "1",
              let origin = ProcessInfo.processInfo.environment["FAKE_HERMES_ORIGIN"], !origin.isEmpty else {
            throw XCTSkip("Set FAKE_HERMES_VIDEO=1 and FAKE_HERMES_ORIGIN with a video-enabled fake backend")
        }
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state", "-uitest-video-clock", "-uitest-probe", origin]
        app.launch()
        let login = app.buttons["Sign in with username and password"]
        XCTAssertTrue(login.waitForExistence(timeout: 30))
        login.tap()
        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10))
        XCTAssertEqual(username.value as? String, "admin")
        let password = app.secureTextFields["Password"]
        XCTAssertTrue(password.waitForExistence(timeout: 10))
        password.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        password.typeText("e2epass")
        app.buttons["Sign in"].tap()
        let row = app.staticTexts["E2E Session"]
        XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
        let preview = app.buttons["managed-video-preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 30), app.debugDescription)
        preview.tap()
        let close = app.buttons["managed-video-close"]
        XCTAssertTrue(close.waitForExistence(timeout: 30), app.debugDescription)
        let progress = app.staticTexts["managed-video-playback-time"]
        let advanced = NSPredicate(format: "label MATCHES %@", "Playback ([2-9]|[1-9][0-9]+) seconds")
        expectation(for: advanced, evaluatedWith: progress)
        waitForExpectations(timeout: 20)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Managed video native playback"; screenshot.lifetime = .keepAlways
        add(screenshot)
        close.tap()
        XCTAssertTrue(preview.waitForExistence(timeout: 10))
        preview.tap()
        XCTAssertTrue(close.waitForExistence(timeout: 30))
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(preview.waitForExistence(timeout: 10))
        XCTAssertFalse(close.exists, "Background must dispose fullscreen playback")
    }
}
