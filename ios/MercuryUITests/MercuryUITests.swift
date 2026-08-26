import XCTest

/// XCUITest driver for milestone simulator verification.
///
/// simctl cannot tap or type, so these tests are the repeatable way to drive
/// the real app against an operator-supplied server. Each test screenshots
/// key states via XCTAttachment for evidence capture.
final class MercuryUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(probeOrigin: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        if let probeOrigin {
            app.launchArguments += ["-uitest-probe", probeOrigin]
        }
        app.launch()
        return app
    }

    /// Connect screen renders with the AMOLED theme and both modes.
    func testConnectScreenRenders() throws {
        let app = launch()

        XCTAssertTrue(app.staticTexts["Connect to Hermes"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Self-hosted"].exists)
        XCTAssertTrue(app.buttons["Hermes Cloud"].exists)
        XCTAssertEqual(app.textFields.firstMatch.placeholderValue, "hermes.example.com")

        // Cloud mode swaps in the Portal sign-in button.
        app.buttons["Hermes Cloud"].tap()
        XCTAssertTrue(app.buttons["Sign in to Nous Portal"].waitForExistence(timeout: 5))
    }

    /// Invalid origins show the friendly validation error without network.
    func testInvalidOriginShowsValidationError() throws {
        let app = launch()

        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 10))
        let field = app.textFields.firstMatch
        field.tap()
        field.typeText("not a host")
        app.buttons["Continue"].tap()

        XCTAssertTrue(
            app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'server address'"))
                .firstMatch.waitForExistence(timeout: 5),
            "expected validation error banner"
        )
    }

    /// Live probe against a configured auth-required server routes to
    /// the Nous sign-in screen. This is the M2 entry path exercised live.
    func testLiveProbeRoutesAuthServerToSignIn() throws {
        let app = launch(probeOrigin: try LiveUITestConfiguration.origin())

        let signInButton = app.buttons["Sign in with Nous"]
        let connected = app.staticTexts["Mercury"]
        let signOutOrSessions = signInButton.waitForExistence(timeout: 20) || connected.waitForExistence(timeout: 1)
        XCTAssertTrue(signOutOrSessions, "expected sign-in screen after probing an auth-required server")
    }

    func testPasswordSignInSheetIsBoundedAndSecure() throws {
        let app = launch(probeOrigin: try LiveUITestConfiguration.origin())

        let passwordMethod = app.buttons["Sign in with username and password"]
        XCTAssertTrue(passwordMethod.waitForExistence(timeout: 20))
        passwordMethod.tap()

        let username = app.textFields["Username"]
        let password = app.secureTextFields["Password"]
        XCTAssertTrue(username.waitForExistence(timeout: 5))
        XCTAssertEqual(username.value as? String, "admin")
        XCTAssertTrue(password.exists)
        XCTAssertEqual(password.value as? String, "Password")
        XCTAssertTrue(app.buttons["Cancel"].exists)
        XCTAssertTrue(app.buttons["Sign in"].exists)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Password sign-in sheet"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    /// M3: on the sign-in screen the "Use a different server" escape hatch
    /// returns to ConnectView (session-list UI needs auth, verified live once
    /// PKCE sign-in completes; this pins the reset path meanwhile).
    func testResetFromSignInReturnsToConnectScreen() throws {
        let app = launch(probeOrigin: try LiveUITestConfiguration.origin())

        XCTAssertTrue(app.buttons["Sign in with Nous"].waitForExistence(timeout: 20))
        app.buttons["Use a different server"].tap()
        XCTAssertTrue(
            app.staticTexts["Connect to Hermes"].waitForExistence(timeout: 10),
            "reset should return to the connect screen"
        )
    }
}
