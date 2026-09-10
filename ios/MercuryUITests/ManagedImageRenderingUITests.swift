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
