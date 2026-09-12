import XCTest
import UIKit

final class MessageBubbleLayoutUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    func testCompactShortAndWrappingLongBubbles() throws {
        try verifyLayout(name: "iphone-portrait", largeText: false, wide: false)
    }

    func testAccessibilityTextKeepsEveryLine() throws {
        try verifyLayout(name: "iphone-large-text", largeText: true, wide: false)
    }

    func testWideLayoutKeepsShortBubbleCompact() throws {
        try verifyLayout(name: "iphone-wide", largeText: false, wide: true)
    }

    private func verifyLayout(name: String, largeText: Bool, wide: Bool) throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-chat-scroll", "-uitest-bubble-layout"]
        if largeText { app.launchArguments.append("-uitest-bubble-large-text") }
        app.launch()
        if wide { XCUIDevice.shared.orientation = .landscapeLeft }
        let measurements = app.staticTexts["Bubble layout measurements"]
        XCTAssertTrue(measurements.waitForExistence(timeout: 10))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let value = measurements.value as? String else { return false }
            return value.contains("user-short-bubble") && value.contains("assistant-long-content")
        }, object: measurements)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed)
        let raw = try XCTUnwrap(measurements.value as? String)
        let values = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: [CGFloat]])
        func frame(_ key: String) throws -> CGRect {
            let v = try XCTUnwrap(values[key], "Missing measured frame: \(key)")
            XCTAssertEqual(v.count, 4)
            return CGRect(x: v[0], y: v[1], width: v[2], height: v[3])
        }
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "bubble-\(name)"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let geometry = XCTAttachment(string: raw)
        geometry.name = "bubble-\(name)-geometry"
        geometry.lifetime = .keepAlways
        add(geometry)

        let short = try frame("user-short-bubble")
        let row = try frame("user-short-row")
        let long = try frame("user-long-bubble")
        let longRow = try frame("user-long-row")
        let text = try frame("user-long-text")
        let assistant = try frame("assistant-long-content")
        let assistantRow = try frame("assistant-long-row")
        // ScrollView accessibility includes the landscape unsafe edges; measure
        // its safe content proposal instead of assuming portrait-only insets.
        let transcript = try frame("transcript-safe")

        // These are GeometryReader frames of the actual background and row,
        // NOT the narrower accessibility bounds of the text inside the bubble.
        XCTAssertEqual(row.maxX, transcript.maxX - 12, accuracy: 1)
        XCTAssertEqual(row.minX, transcript.minX + 12, accuracy: 1)
        XCTAssertEqual(short.maxX, row.maxX, accuracy: 1)
        XCTAssertLessThan(short.width, row.width * 0.5)
        let category: UIContentSizeCategory = largeText ? .accessibilityMedium : .large
        let font = UIFont.preferredFont(forTextStyle: .body, compatibleWith:
            UITraitCollection(preferredContentSizeCategory: category))
        let shortTextWidth = ("test" as NSString).size(withAttributes: [.font: font]).width
        XCTAssertEqual(short.width, ceil(shortTextWidth) + 24, accuracy: 2)
        XCTAssertEqual(long.maxX, longRow.maxX, accuracy: 1)
        XCTAssertGreaterThanOrEqual(long.minX - longRow.minX, 48)
        XCTAssertLessThanOrEqual(long.width, longRow.width - 48)
        XCTAssertEqual(long.height, text.height + 16, accuracy: 1)
        XCTAssertEqual(long.width, text.width + 24, accuracy: 1)
        let longText = "A longer outgoing message should wrap naturally within the available width.\nEvery line stays readable.\nThis final sentence must appear completely, including END OF MESSAGE."
        let expected = (longText as NSString).boundingRect(
            with: CGSize(width: text.width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil
        )
        XCTAssertGreaterThanOrEqual(text.height + 2, ceil(expected.height), "All wrapped lines must fit vertically")
        XCTAssertGreaterThan(text.height, font.lineHeight * 2)
        XCTAssertEqual(assistant.minX, assistantRow.minX, accuracy: 1)
        XCTAssertGreaterThanOrEqual(assistant.width, assistantRow.width - 60)
        XCTAssertEqual(assistantRow.minX, row.minX, accuracy: 1)
        if wide { XCTAssertGreaterThan(row.width, 600) }
    }
}
