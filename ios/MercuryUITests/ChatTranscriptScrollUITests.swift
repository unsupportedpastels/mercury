import XCTest

/// Exercises the production SwiftUI transcript, backed only by synthetic rows.
final class ChatTranscriptScrollUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testJumpAndManualReturnResumeFollowingWithoutStreamingYank() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-chat-scroll"]
        app.launch()

        let timeline = app.scrollViews["Chat transcript"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Synthetic latest message"].waitForExistence(timeout: 5))

        timeline.swipeDown()
        timeline.swipeDown()
        let jump = app.buttons["Scroll to latest message"]
        XCTAssertTrue(jump.waitForExistence(timeout: 5))
        let awayScreenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        awayScreenshot.name = "ios-chat-scroll-away-control"
        awayScreenshot.lifetime = .keepAlways
        add(awayScreenshot)

        app.buttons["Append synthetic stream chunk"].tap()
        XCTAssertTrue(jump.exists, "Incoming streaming content must not yank an away reader to the tail")

        jump.tap()
        let streaming = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "Synthetic streaming response")
        ).firstMatch
        XCTAssertTrue(streaming.waitForExistence(timeout: 5))
        let jumpGone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: jump
        )
        XCTAssertEqual(XCTWaiter.wait(for: [jumpGone], timeout: 5), .completed)
        app.buttons["Append synthetic stream chunk"].tap()
        assertFollowingAfterGrowth(jump)

        timeline.swipeDown()
        timeline.swipeDown()
        XCTAssertTrue(jump.waitForExistence(timeout: 5))
        for _ in 0..<8 where jump.exists {
            timeline.swipeUp()
        }
        XCTAssertFalse(jump.exists, "Manually returning to the true end must resume follow mode")
        app.buttons["Append synthetic stream chunk"].tap()
        assertFollowingAfterGrowth(jump)

        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "ios-chat-scroll-at-latest"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testOneTapReachesTrueTailAndFollowsVariableHeightStreamingBurst() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-chat-scroll", "-uitest-chat-scroll-stress"]
        app.launch()

        let timeline = app.scrollViews["Chat transcript"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Synthetic latest message"].waitForExistence(timeout: 10))

        for _ in 0..<4 { timeline.swipeDown() }
        let jump = app.buttons["Scroll to latest message"]
        XCTAssertTrue(jump.waitForExistence(timeout: 5))

        app.buttons["Start synthetic stream burst"].tap()
        XCTAssertTrue(jump.exists, "Streaming must preserve the reader's away position")
        jump.tap()

        let streaming = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "Synthetic streaming response")
        ).firstMatch
        XCTAssertTrue(streaming.waitForExistence(timeout: 5))
        let atTrueTail = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: jump
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [atTrueTail], timeout: 5),
            .completed,
            "One tap must reach the true tail and remain there through streaming layout growth"
        )

        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "ios-chat-scroll-stress-at-true-tail"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testMeasuredWindowKeepsOlderHistoryAccessibleAndJumpReturnsToLatest() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-chat-scroll", "-uitest-chat-scroll-stress"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Synthetic latest message"].waitForExistence(timeout: 15))
        let oldest = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Synthetic message 1\n")).firstMatch
        XCTAssertFalse(oldest.exists, "The latest measured window must not instantiate all loaded history")
        app.buttons["Load earlier messages"].tap()
        XCTAssertTrue(oldest.waitForExistence(timeout: 5), "Earlier rows remain available without refetching or discarding history")
        app.buttons["Scroll to latest message"].tap()
        let latestWindow = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: oldest)
        XCTAssertEqual(XCTWaiter.wait(for: [latestWindow], timeout: 5), .completed)
        XCTAssertTrue(app.staticTexts["Synthetic latest message"].exists)
    }

    private func assertFollowingAfterGrowth(_ jump: XCUIElement) {
        let atBottom = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: jump
        )
        XCTAssertEqual(XCTWaiter.wait(for: [atBottom], timeout: 5), .completed,
                       "Growing streaming text must stay at the tail after follow resumes")
    }
}
