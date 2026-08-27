import XCTest

/// Temporary live driver: signs in (reusing the Portal cookie when present),
/// opens an existing session, sends a real prompt, and verifies the streamed
/// assistant reply.
final class LiveChatTurnUITests: XCTestCase {
    func testStreamedReplyInExistingSession() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()

        var newSession = app.buttons["New session"]
        if !newSession.waitForExistence(timeout: 20) {
            // Cookie lost — run the full sign-in flow first.
            let signIn = app.buttons["Sign in with Nous"]
            XCTAssertTrue(signIn.waitForExistence(timeout: 30), app.debugDescription)
            signIn.tap()

            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            if springboard.buttons["Continue"].waitForExistence(timeout: 15) {
                springboard.buttons["Continue"].tap()
            }

            // Portal overview → member sign-in form (when logged out).
            let welcomeForm = app.staticTexts["Welcome to Nous Portal"]
            if !welcomeForm.waitForExistence(timeout: 10) {
                let memberLink = app.links.matching(
                    NSPredicate(format: "label CONTAINS[cd] 'member sign in'")
                ).firstMatch
                if memberLink.waitForExistence(timeout: 10) {
                    memberLink.tap()
                }
            }
            XCTAssertTrue(welcomeForm.waitForExistence(timeout: 30), app.debugDescription)

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
            for (index, digit) in code.enumerated() {
                let box = app.textFields.element(boundBy: index)
                XCTAssertTrue(box.waitForExistence(timeout: 5), "missing box \(index)")
                box.tap()
                box.typeText(String(digit))
            }
        }

        newSession = app.buttons["New session"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 60), app.debugDescription)

        // Open the most recent session.
        let firstSession = app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'Verify basic login without OAuth'")
        ).firstMatch
        XCTAssertTrue(firstSession.waitForExistence(timeout: 15), app.debugDescription)
        firstSession.tap()

        // Composer: type a short prompt and send.
        let composer = app.textViews.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 20), app.debugDescription)
        composer.tap()
        composer.typeText("Reply with exactly: MERCURY_IOS_STREAM_OK")
        let send = app.buttons["Send"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        send.tap()

        // Wait for the streamed reply marker (allow up to 120s for generation).
        var seen = false
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline && !seen {
            RunLoop.current.run(until: Date().addingTimeInterval(2))
            seen = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS 'MERCURY_IOS_STREAM_OK'")
            ).count >= 1
        }

        print("CHAT_AFTER_TURN\n\(app.debugDescription)")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "Mercury streaming turn"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(seen, "streamed reply never appeared")
    }
}
