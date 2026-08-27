import XCTest

/// Live M1 (WS ticket mint + open) + M2 (streaming turn) driver against an
/// operator-supplied Hermes origin.
///
/// `xcodebuild test` reinstalls the app each run, wiping the simulator
/// keychain, so this test signs in first (Nous PKCE via ASWebAuthenticationSession)
/// and only then drives the chat flow:
///   sign in → list → "+" (new session over WS `session.create` = ticket mint
///   + open) → send a prompt → assistant reply streams back into the transcript.
///
/// The Nous browser session is typically still authorized, so tapping "Sign in
/// with Nous" completes without an email code. If it is not, we fall back to
/// the email + Privy-code path, reading the fresh code from /tmp/portal_code.txt
/// (written by the IMAP fetcher on the build host).
final class LiveChatDriverUITests: XCTestCase {
    func testNewSessionStreamsLiveReply() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()

        guard try signIn(app) else { return }

        // Authenticated list: the "New session" (+) button is list-only.
        let newSession = app.buttons["New session"]
        XCTAssertTrue(
            newSession.waitForExistence(timeout: 40),
            "expected authenticated session list after sign-in; got:\n\(app.debugDescription)"
        )

        // Open a brand-new chat → ChatView.newSession() mints a WS ticket,
        // opens the socket, and issues session.create (M1).
        newSession.tap()

        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(
            composer.waitForExistence(timeout: 30),
            "chat composer never appeared:\n\(app.debugDescription)"
        )
        let offline = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[cd] 'offline'")
        ).firstMatch
        XCTAssertFalse(offline.exists, "chat went offline — WS ticket mint/open (M1) failed")

        // Deterministic prompt so the streamed reply is checkable (M2).
        let sentinel = "MERCURYPONG"
        composer.tap()
        composer.typeText("Reply with exactly this one word and nothing else: \(sentinel)")
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 5), "send button missing")
        XCTAssertTrue(app.keyboards.firstMatch.exists, "typing the prompt did not present the keyboard")
        send.tap()

        // Send is an explicit user-owned focus boundary: submitting should
        // dismiss the keyboard so the composer settles back at the screen
        // bottom instead of remaining above the former IME frame.
        let keyboardDismissed = NSPredicate(format: "exists == false")
        expectation(for: keyboardDismissed, evaluatedWith: app.keyboards.firstMatch)
        waitForExpectations(timeout: 5)

        // The accepted submission must clear the composer immediately, before
        // the streamed assistant reply or tool activity completes.
        let clearDeadline = Date().addingTimeInterval(5)
        while (composer.value as? String) != "" && Date() < clearDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        let composerValue = composer.value as? String
        XCTAssertTrue(
            composerValue == nil || composerValue == "" || composerValue == "Message Hermes",
            "accepted prompt remained in the composer after Send: \(composerValue ?? "nil")"
        )

        // Assistant reply arrives via streamed deltas — require the sentinel in
        // a static text that is NOT our own prompt echo.
        let assistant = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[cd] %@ AND NOT (label CONTAINS[cd] 'Reply with')", sentinel)
        ).firstMatch
        let streamed = assistant.waitForExistence(timeout: 120)

        print("CHAT_STREAMED=\(streamed)")
        XCTAssertTrue(
            streamed,
            "no streamed assistant reply containing the sentinel:\n\(app.debugDescription)"
        )

        // A streamed delta can arrive while the turn is still active. Wait for
        // the terminal event so the screenshot proves the idle Android-parity
        // controls (plus + mic + subdued disabled Send), not only gold Stop.
        let idleSend = app.buttons["Send message"]
        XCTAssertTrue(
            idleSend.waitForExistence(timeout: 30),
            "streamed reply never returned the composer to idle:\n\(app.debugDescription)"
        )
        let idleShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        idleShot.name = "Mercury composer idle Android colors"
        idleShot.lifetime = .keepAlways
        add(idleShot)

        // A sendable draft proves the teal primary/on-primary pair separately.
        composer.tap()
        composer.typeText("Draft")
        let enabledShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        enabledShot.name = "Mercury composer enabled Android colors"
        enabledShot.lifetime = .keepAlways
        add(enabledShot)
    }

    /// Reproduces the reported tool-heavy GitHub question and requires the
    /// final assistant text, not just the completed activity summary.
    func testToolHeavyQuestionRendersFinalAnswer() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()

        guard try signIn(app) else { return }
        let newSession = app.buttons["New session"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 60), app.debugDescription)
        newSession.tap()

        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        composer.tap()
        composer.typeText(
            "Read https://github.com/NousResearch/hermes-agent/issues/95028 and answer: " +
            "How many PRs has this user rebased as his own? Search the issue and repository " +
            "as needed. End the final answer with exactly MERCURY_TOOL_FINAL_SENTINEL."
        )
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        send.tap()

        let finalAnswer = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "MERCURY_TOOL_FINAL_SENTINEL")
        ).firstMatch
        XCTAssertTrue(
            finalAnswer.waitForExistence(timeout: 180),
            "tool activity completed without a visible final assistant answer:\n\(app.debugDescription)"
        )

        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "Tool-heavy question final answer"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    /// Reproduces closing Mercury during a live turn, then reopening the same
    /// durable session and requiring the completed response to be visible.
    func testTerminateDuringProcessingReopensCompletedResponse() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()

        guard try signIn(app) else { return }
        let newSession = app.buttons["New session"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 60), app.debugDescription)
        newSession.tap()

        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        composer.tap()
        composer.typeText(
            "Read https://github.com/NousResearch/hermes-agent/issues/95028 and answer: " +
            "How many PRs has this user rebased as his own? End the final answer with exactly " +
            "MERCURY_REOPEN_FINAL_SENTINEL."
        )
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        send.tap()

        // Kill the app while the request is still in its tool/response phase.
        sleep(1)
        app.terminate()
        app.launch()

        let sessionList = app.buttons["New session"]
        XCTAssertTrue(sessionList.waitForExistence(timeout: 90), app.debugDescription)

        // The generated title is server-owned; use the prompt's distinctive
        // issue number as the fallback row selector exposed by accessibility.
        let reopened = app.buttons.matching(
            NSPredicate(format: "label CONTAINS \"Count user's rebased PRs\"")
        ).firstMatch
        XCTAssertTrue(
            reopened.waitForExistence(timeout: 60),
            "reopened session row was not found after termination:\n\(app.debugDescription)"
        )
        reopened.tap()

        let finalAnswer = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "MERCURY_REOPEN_FINAL_SENTINEL")
        ).firstMatch
        XCTAssertTrue(
            finalAnswer.waitForExistence(timeout: 180),
            "reopened session did not show the completed assistant answer:\n\(app.debugDescription)"
        )
    }

    /// Live M7/M8 milestone gate against the released self-hosted server.
    /// Exercises only simulator state: no physical-device build, install, or launch.
    func testM7M8SessionControlsAndHostReference() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()

        guard try signIn(app) else { return }
        let newSession = app.buttons["New session"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 60), app.debugDescription)
        newSession.tap()

        var composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)

        // M7.1: load the real advertised model catalog and make a session-only change.
        let modelButton = app.buttons["Session model"]
        XCTAssertTrue(modelButton.waitForExistence(timeout: 30), app.debugDescription)
        modelButton.tap()
        XCTAssertTrue(app.navigationBars["Session Model"].waitForExistence(timeout: 30), app.debugDescription)
        let deepSeekFlash = app.buttons.matching(
            NSPredicate(
                format: "label CONTAINS[cd] 'deepseek-v4-flash-0731' AND label CONTAINS[cd] 'nous'"
            )
        ).firstMatch
        for _ in 0..<24 where !deepSeekFlash.exists {
            app.swipeUp()
        }
        if deepSeekFlash.exists {
            app.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertTrue(
            deepSeekFlash.waitForExistence(timeout: 10),
            "Nous DeepSeek Flash was not advertised after scrolling the catalog:\n\(app.debugDescription)"
        )
        XCTAssertTrue(deepSeekFlash.isHittable, app.debugDescription)
        deepSeekFlash.tap()

        let modelUpdated = app.staticTexts["Session model updated."]
        let modelError = app.staticTexts["Could not change the session model."]
        let modelDeadline = Date().addingTimeInterval(90)
        while Date() < modelDeadline && !modelUpdated.exists && !modelError.exists {
            let confirm = app.buttons["Use Model"]
            if confirm.exists && confirm.isHittable {
                confirm.tap()
            }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        }
        XCTAssertFalse(modelError.exists, app.debugDescription)
        XCTAssertTrue(modelUpdated.exists, app.debugDescription)
        print("M7_NOUS_DEEPSEEK_FLASH_SWITCH=true")

        // M7.2: force a long-running tool turn, then route composer text through session.steer.
        composer = app.textFields["Message Hermes"]
        composer.tap()
        composer.typeText("Use the terminal tool to run sleep 12. Do not skip the tool. After it finishes reply exactly ORIGINAL_RESULT.")
        app.buttons["Send message"].tap()
        XCTAssertTrue(app.buttons["Stop Hermes response"].waitForExistence(timeout: 60), app.debugDescription)
        attachScreenshot(named: "M7 active turn steering")

        let steeringComposer = app.textFields["Message Hermes"]
        XCTAssertTrue(steeringComposer.waitForExistence(timeout: 10), app.debugDescription)
        steeringComposer.tap()
        steeringComposer.typeText("When the tool finishes, reply exactly MERCURY_STEER_OK instead.")
        app.buttons["Steer active turn"].tap()
        XCTAssertTrue(
            app.staticTexts["Guidance queued for the active turn."].waitForExistence(timeout: 30),
            app.debugDescription
        )
        let steeredReply = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[cd] 'MERCURY_STEER_OK' AND NOT (label CONTAINS[cd] 'When the tool')")
        ).firstMatch
        XCTAssertTrue(steeredReply.waitForExistence(timeout: 180), app.debugDescription)

        // M7.3: usage/context data must load over the live runtime connection.
        let contextButton = app.buttons["Session context"]
        XCTAssertTrue(contextButton.waitForExistence(timeout: 30), app.debugDescription)
        contextButton.tap()
        XCTAssertTrue(app.staticTexts["Context usage"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Total"].waitForExistence(timeout: 30), app.debugDescription)
        attachScreenshot(named: "M7 live context usage")
        app.buttons["Done"].tap()

        // M8.2: navigate only via server-returned canonical rows, stage a host
        // reference without auto-send, then prove the agent can read it.
        app.buttons["Add attachment"].tap()
        XCTAssertTrue(app.buttons["Reference Host File"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["Reference Host File"].tap()
        XCTAssertTrue(app.navigationBars["Host Files"].waitForExistence(timeout: 30), app.debugDescription)

        let repoFolder = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[cd] 'hermes-android'")
        ).firstMatch
        for _ in 0..<16 where !repoFolder.exists {
            app.swipeUp()
        }
        XCTAssertTrue(repoFolder.waitForExistence(timeout: 10), app.debugDescription)
        repoFolder.tap()
        let readme = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[cd] 'README.md'")
        ).firstMatch
        for _ in 0..<8 where !readme.exists {
            app.swipeUp()
        }
        XCTAssertTrue(readme.waitForExistence(timeout: 10), app.debugDescription)
        readme.tap()
        XCTAssertTrue(app.staticTexts["README.md"].waitForExistence(timeout: 20), app.debugDescription)
        attachScreenshot(named: "M8 staged host file reference")

        composer = app.textFields["Message Hermes"]
        composer.tap()
        composer.typeText("Read the referenced file and reply with exactly its first Markdown heading text, without the leading hash or any explanation.")
        app.buttons["Send message"].tap()
        let fileReply = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[cd] 'Mercury — a Hermes companion'")
        ).firstMatch
        XCTAssertTrue(fileReply.waitForExistence(timeout: 180), app.debugDescription)
        attachScreenshot(named: "M8 live host reference reply")

        // M8.3: the metadata-only project socket must load without taking over a chat runtime.
        app.navigationBars.buttons.firstMatch.tap()
        let projects = app.buttons["Projects"]
        XCTAssertTrue(projects.waitForExistence(timeout: 30), app.debugDescription)
        projects.tap()
        XCTAssertTrue(app.navigationBars["Projects"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.staticTexts["Projects unavailable"].exists, app.debugDescription)
        attachScreenshot(named: "M8 live projects")

        print("M7_M8_LIVE_GATE=true")
    }

    private func attachScreenshot(named name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Simulator-only M9/M10/M11 parity gate against the same released host as Android.
    func testM9M11SettingsCronOfflineAndVoice() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()
        guard try signIn(app) else { return }

        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 60), app.debugDescription)
        settings.tap()
        XCTAssertTrue(app.staticTexts["Scheduled jobs"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Offline & privacy"].exists)
        XCTAssertTrue(app.staticTexts["Voice"].exists)

        app.staticTexts["Scheduled jobs"].tap()
        XCTAssertTrue(app.navigationBars["Cron Jobs"].waitForExistence(timeout: 30), app.debugDescription)
        let runNow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Run ' AND label ENDSWITH ' now'"))
            .firstMatch
        XCTAssertTrue(runNow.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Last outcome:"].exists)
        attachScreenshot(named: "Mercury live cron jobs")

        app.navigationBars["Cron Jobs"].buttons["Settings"].tap()
        app.staticTexts["Offline & privacy"].tap()
        let cacheToggle = app.switches["Save conversations for offline reading"]
        XCTAssertTrue(cacheToggle.waitForExistence(timeout: 10), app.debugDescription)
        let originalValue = cacheToggle.value as? String
        cacheToggle.tap()
        let changedDeadline = Date().addingTimeInterval(10)
        while cacheToggle.value as? String == originalValue, Date() < changedDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        cacheToggle.tap()
        let restoredDeadline = Date().addingTimeInterval(10)
        while cacheToggle.value as? String != originalValue, Date() < restoredDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        XCTAssertEqual(cacheToggle.value as? String, originalValue)

        app.navigationBars["Offline & Privacy"].buttons["Settings"].tap()
        app.staticTexts["Voice"].tap()
        XCTAssertTrue(app.staticTexts["On-device dictation"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Read aloud"].exists)

        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Mercury M9-M11 settings"
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testM11ShareStagesDraftWithoutAutoSend() throws {
        let app = XCUIApplication()
        let sentinel = "MERCURY_SHARE_SIMULATOR_ONLY"
        app.launchArguments = [
            "-uitest-probe", try LiveUITestConfiguration.origin(),
            "-uitest-share-text", sentinel,
        ]
        app.launch()
        guard try signIn(app) else { return }

        let newChat = app.buttons["New chat"]
        XCTAssertTrue(newChat.waitForExistence(timeout: 30), app.debugDescription)
        newChat.tap()

        let composer = app.textFields.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertEqual(composer.value as? String, sentinel)
        XCTAssertEqual(
            app.staticTexts.matching(NSPredicate(format: "label == %@", sentinel)).count,
            0,
            "shared content must remain in the composer instead of appearing as a sent transcript row"
        )
        XCTAssertTrue(app.buttons["Dictate message"].exists)

        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Mercury shared draft and dictation"
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testM11ReadAloudStartsForLiveAssistantReply() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-probe", try LiveUITestConfiguration.origin()]
        app.launch()
        guard try signIn(app) else { return }

        let existing = app.staticTexts["Output exact string READ_ALOUD_READY"].firstMatch
        if existing.waitForExistence(timeout: 10) {
            existing.tap()
        } else {
            let newSession = app.buttons["New session"]
            XCTAssertTrue(newSession.waitForExistence(timeout: 30), app.debugDescription)
            newSession.tap()
            let composer = app.textFields.firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
            composer.tap()
            composer.typeText("Reply with exactly READ_ALOUD_READY and nothing else")
            app.buttons["Send message"].tap()
        }

        let assistant = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@ AND NOT (label CONTAINS 'Reply with')", "READ_ALOUD_READY")
        ).firstMatch
        XCTAssertTrue(assistant.waitForExistence(timeout: 120), app.debugDescription)
        let readButton = app.buttons["Read message aloud"]
        XCTAssertTrue(readButton.waitForExistence(timeout: 30), app.debugDescription)
        readButton.tap()
        let stopButton = app.buttons["Stop reading aloud"]
        let failedButton = app.buttons["Retry read aloud"]
        let speechDeadline = Date().addingTimeInterval(30)
        while !stopButton.exists && !failedButton.exists && Date() < speechDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        if failedButton.exists {
            let reason = failedButton.value as? String ?? "unknown"
            if reason.contains("Speech request rejected") {
                throw XCTSkip("Live server TTS configuration rejected synthesis: \(reason)")
            }
            XCTFail("Read aloud failed: \(reason)")
            return
        }
        XCTAssertTrue(stopButton.exists, app.debugDescription)

        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Mercury live read aloud"
        shot.lifetime = .keepAlways
        add(shot)
    }

    // MARK: - Sign-in

    @discardableResult
    private func signIn(_ app: XCUIApplication) throws -> Bool {
        func fail(_ message: String) -> Bool {
            XCTFail(message)
            return false
        }
        let signInButton = app.buttons["Sign in with Nous"]
        // If we somehow already have a session, skip straight through.
        guard signInButton.waitForExistence(timeout: 30) else {
            return app.buttons["New session"].exists || fail("Neither sign-in nor the authenticated session list appeared")
        }

        let newSession = app.buttons["New session"]
        let email = app.textFields.matching(
            NSPredicate(format: "placeholderValue CONTAINS 'your@email'")
        ).firstMatch
        let providerError = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[cd] 'Provider unreachable'")
        ).firstMatch

        // The released server can transiently fail its upstream Portal TLS
        // connection. That leaves ASWebAuthenticationSession on an explicit
        // error page with Mercury's sign-in button disabled; cancel that
        // browser session and retry only that proven transient case.
        let maxAttempts = 3
        for attempt in 0..<maxAttempts {
            let enabledDeadline = Date().addingTimeInterval(10)
            while !signInButton.isEnabled && Date() < enabledDeadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            }
            guard signInButton.isEnabled else { return fail("Sign-in action stayed disabled") }
            signInButton.tap()

            // System confirmation sheet for the web auth session.
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let confirmDeadline = Date().addingTimeInterval(25)
            while Date() < confirmDeadline {
                if springboard.buttons["Continue"].exists {
                    springboard.buttons["Continue"].tap(); break
                } else if app.buttons["Continue"].exists {
                    app.buttons["Continue"].tap(); break
                }
                RunLoop.current.run(until: Date().addingTimeInterval(1))
            }

            // The web auth session persists Nous cookies (prefersEphemeral =
            // false), so the authorize page normally auto-redirects through
            // the loopback callback with NO email code. Poll for either the
            // authenticated list, the slow email path, or the explicit server
            // error page.
            let signInDeadline = Date().addingTimeInterval(90)
            var sawProviderError = false
            while Date() < signInDeadline {
                if newSession.exists { return true }
                if email.exists { break }
                if providerError.exists {
                    sawProviderError = true
                    cancelBrowserAuth(app)
                    break
                }
                RunLoop.current.run(until: Date().addingTimeInterval(2))
            }

            if sawProviderError {
                if attempt + 1 < maxAttempts { continue }
                break
            }

            // Slow path: enter the Portal email and fetch the six-digit code
            // from the build host's pre-arranged IMAP handoff.
            guard email.exists else { return fail("Portal authorization exposed neither auto-redirect nor email entry") }
            email.tap()
            email.typeText(try LiveUITestConfiguration.portalEmail())
            email.typeText("\n")

            guard app.staticTexts["Enter confirmation code"].waitForExistence(timeout: 30) else {
                return fail("Portal confirmation-code screen did not appear")
            }

            var code = ""
            let codeDeadline = Date().addingTimeInterval(90)
            while Date() < codeDeadline {
                code = FileManager.default.contents(atPath: "/tmp/portal_code.txt")
                    .flatMap { String(data: $0, encoding: .utf8) }?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if code.count == 6 { break }
                RunLoop.current.run(until: Date().addingTimeInterval(2))
            }
            guard code.count == 6 else { return fail("A fresh six-digit Portal code was not available") }

            for (index, digit) in code.enumerated() {
                let box = app.textFields.element(boundBy: index)
                guard box.waitForExistence(timeout: 5) else { return fail("Portal confirmation-code field was unavailable") }
                box.tap()
                box.typeText(String(digit))
            }
            return newSession.waitForExistence(timeout: 60)
        }

        XCTFail("Nous sign-in did not reach the authenticated session list after bounded retries")
        return false
    }

    private func cancelBrowserAuth(_ app: XCUIApplication) {
        if app.buttons["Cancel"].exists {
            app.buttons["Cancel"].tap()
        } else {
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            if springboard.buttons["Cancel"].exists {
                springboard.buttons["Cancel"].tap()
            }
        }
        RunLoop.current.run(until: Date().addingTimeInterval(1))
    }
}
