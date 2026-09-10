import XCTest

final class ManagedImageRenderingUITests: XCTestCase {
    func testMediaAndLocalMarkdownRenderThroughProductionImageView() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-managed-images"]
        app.launch()

        let rendered = app.images.matching(
            NSPredicate(format: "label == %@", "Generated image; tap to enlarge")
        )
        let countReached = NSPredicate { _, _ in rendered.count == 2 }
        expectation(for: countReached, evaluatedWith: nil)
        waitForExpectations(timeout: 20)
        XCTAssertEqual(rendered.count, 2, "Bare paths and remote Markdown images must not auto-fetch")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Image unavailable:")).firstMatch.exists)

        let before = app.staticTexts["PROSE BEFORE"]
        let between = app.staticTexts["PROSE BETWEEN"]
        let after = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@", "PROSE AFTER")
        ).firstMatch
        XCTAssertTrue(before.waitForExistence(timeout: 5))
        XCTAssertTrue(between.exists)
        XCTAssertTrue(after.exists)
        XCTAssertLessThan(before.frame.minY, rendered.element(boundBy: 0).frame.minY)
        XCTAssertLessThan(rendered.element(boundBy: 0).frame.minY, between.frame.minY)
        XCTAssertLessThan(between.frame.minY, rendered.element(boundBy: 1).frame.minY)
        XCTAssertLessThan(rendered.element(boundBy: 1).frame.minY, after.frame.minY)

        rendered.element(boundBy: 1).tap()
        XCTAssertTrue(app.buttons["Close enlarged image"].waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Local Markdown image fullscreen"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.buttons["Close enlarged image"].tap()

        XCTAssertEqual(rendered.count, 2)
        let inline = XCTAttachment(screenshot: app.screenshot())
        inline.name = "MEDIA and local Markdown inline images"
        inline.lifetime = .keepAlways
        add(inline)
    }
}
