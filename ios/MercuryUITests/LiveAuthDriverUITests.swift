import XCTest

/// Temporary live driver for the passwordless Nous Portal sign-in step.
final class LiveAuthDriverUITests: XCTestCase {
    func testEnterPortalCodeAndVerifyMercury() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()

        let signIn = app.buttons["Sign in with Nous"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 30), app.debugDescription)
        signIn.tap()

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let continueButton = springboard.buttons["Continue"]
        if continueButton.waitForExistence(timeout: 15) {
            continueButton.tap()
        }

        // The Portal may land on its public overview page (no session
        // cookie). In that case open the member sign-in form first.
        let welcomeForm = app.staticTexts["Welcome to Nous Portal"]
        if !welcomeForm.waitForExistence(timeout: 10) {
            let memberLink = app.links.matching(
                NSPredicate(format: "label CONTAINS[cd] 'member sign in'")
            ).firstMatch
            if memberLink.waitForExistence(timeout: 10) {
                memberLink.tap()
            }
        }
        // If a previous run left a valid Portal cookie, Mercury may already
        // be authenticated and showing the session list — skip the form.
        let alreadyAuthed = app.navigationBars["Mercury"].waitForExistence(timeout: 8)
            || app.buttons["New session"].exists
        if !alreadyAuthed {
            XCTAssertTrue(
                welcomeForm.waitForExistence(timeout: 30),
                "Portal login form did not appear\n\(app.debugDescription)"
            )

            let email = app.textFields.matching(
                NSPredicate(format: "placeholderValue CONTAINS 'your@email'")
            ).firstMatch
            XCTAssertTrue(email.waitForExistence(timeout: 20), app.debugDescription)
            email.tap()
            email.typeText(try LiveUITestConfiguration.portalEmail())
            email.typeText("\n")

            XCTAssertTrue(
                app.staticTexts["Enter confirmation code"].waitForExistence(timeout: 30),
                app.debugDescription
            )
            _ = app.staticTexts["and enter your code below."].waitForExistence(timeout: 10)

            // Fresh code arrives by email only after the email step; poll the
            // shared file the IMAP fetcher writes.
            var code = ""
            let codeDeadline = Date().addingTimeInterval(90)
            while Date() < codeDeadline {
                code = FileManager.default.contents(atPath: "/tmp/portal_code.txt")
                    .flatMap { String(data: $0, encoding: .utf8) }?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if code.count == 6 { break }
                RunLoop.current.run(until: Date().addingTimeInterval(2))
            }
            XCTAssertEqual(code.count, 6, "fresh code missing from /tmp/portal_code.txt")

            // Privy exposes six separate native textfields; enter one digit per box.
            for (index, digit) in code.enumerated() {
                let box = app.textFields.element(boundBy: index)
                XCTAssertTrue(box.waitForExistence(timeout: 5), "missing box \(index)")
                box.tap()
                box.typeText(String(digit))
            }
        }

        var reachedAuthed = false
        var sawError = false
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline && !reachedAuthed && !sawError {
            RunLoop.current.run(until: Date().addingTimeInterval(2))
            reachedAuthed = app.staticTexts["Mercury"].exists || app.staticTexts["Sessions"].exists
            sawError = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS[cd] 'invalid' OR label CONTAINS[cd] 'incorrect' OR label CONTAINS[cd] 'expired'")
            ).firstMatch.exists
        }

        print("MERCURY_AFTER_CODE\n\(app.debugDescription)")
        print("PORTAL_CODE_REJECTED=\(sawError)")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "Mercury after Portal code"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(reachedAuthed, sawError ? "code rejected by Portal" : "Mercury did not reach authenticated UI")
    }
}
