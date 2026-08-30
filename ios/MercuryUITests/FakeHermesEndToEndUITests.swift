import XCTest

/// True end-to-end drive of the release UI against the scripted fake Hermes
/// backend (tools/fake-hermes/fake_hermes.py on the build host): probe a
/// cleartext LAN origin, password sign-in, open the fake's session, send a
/// prompt over the real WebSocket, and verify the interrupt-sentinel
/// completion keeps the streamed partial and never renders
/// "Operation interrupted" prose.
///
/// Skips (does not fail) when FAKE_HERMES_ORIGIN is not set, so the ordinary
/// UI-test scheme stays hermetic. Run with e.g.
///   FAKE_HERMES_ORIGIN=http://192.0.2.10:8787 xcodebuild -scheme MercuryUITests …
final class FakeHermesEndToEndUITests: XCTestCase {

    func testBareHostCleartextConnectSendAndSentinelSuppression() throws {
        guard let origin = ProcessInfo.processInfo.environment["FAKE_HERMES_ORIGIN"],
              !origin.isEmpty else {
            throw XCTSkip("FAKE_HERMES_ORIGIN not set; fake-hermes E2E not requested")
        }

        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", origin]
        app.launch()

        // Probe lands on the sign-in screen advertising password auth.
        let passwordButton = app.buttons["Sign in with username and password"]
        XCTAssertTrue(
            passwordButton.waitForExistence(timeout: 30),
            "password sign-in never offered:\n\(app.debugDescription)"
        )
        passwordButton.tap()

        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10), "username field missing")
        username.tap()
        username.typeText("admin")
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText("e2epass")
        app.buttons["Sign in"].tap()

        // Authenticated list shows the fake's session; open it.
        let sessionRow = app.staticTexts["E2E Session"]
        XCTAssertTrue(
            sessionRow.waitForExistence(timeout: 30),
            "authenticated session list never showed the fake session:\n\(app.debugDescription)"
        )
        sessionRow.tap()

        // Send a prompt through the real composer and WebSocket.
        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 30), "chat composer never appeared")
        composer.tap()
        composer.typeText("stream then interrupt")
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 5), "send button missing")
        send.tap()

        // The fake streams "The answer is 42" then completes with the
        // interrupt sentinel. The partial must render; the sentinel must not.
        let partial = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'The answer is 42'")
        ).firstMatch
        XCTAssertTrue(partial.waitForExistence(timeout: 30), "streamed partial never rendered")

        // Give the sentinel completion time to arrive, then assert suppression.
        sleep(12)
        let sentinel = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Operation interrupted'")
        ).firstMatch
        XCTAssertFalse(sentinel.exists, "interrupt sentinel was rendered as assistant prose")
        XCTAssertTrue(partial.exists, "streamed partial was lost after sentinel completion")
    }
}
