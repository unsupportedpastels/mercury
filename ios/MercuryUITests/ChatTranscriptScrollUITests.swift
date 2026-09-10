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

    private func assertFollowingAfterGrowth(_ jump: XCUIElement) {
        let atBottom = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: jump
        )
        XCTAssertEqual(XCTWaiter.wait(for: [atBottom], timeout: 5), .completed,
                       "Growing streaming text must stay at the tail after follow resumes")
    }
}
