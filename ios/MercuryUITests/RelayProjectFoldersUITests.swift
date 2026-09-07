import UIKit
import XCTest

/// Opt-in simulator test against an isolated real-plugin Relay host. The host
/// must expose a managed root with an `existing` directory and approve only
/// this test device. Never run against an operator's personal project catalog.
final class RelayProjectFoldersUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testPairBrowseExistingCreateFolderAndRegisterProjects() throws {
        guard ProcessInfo.processInfo.environment["MERCURY_RELAY_FOLDER_QA"] == "1",
              let b64 = ProcessInfo.processInfo.environment["MERCURY_RELAY_PAYLOAD_B64"],
              let data = Data(base64Encoded: b64),
              let payload = String(data: data, encoding: .utf8), !payload.isEmpty else {
            throw XCTSkip("Requires an isolated Relay folder QA host and one-time pairing offer")
        }
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state"]
        app.launch()
        let relay = app.buttons["Relay"]
        XCTAssertTrue(relay.waitForExistence(timeout: 30))
        relay.tap()
        let pair = app.buttons["Pair with QR code"]
        XCTAssertTrue(pair.waitForExistence(timeout: 10))
        pair.tap()
        let field = app.textFields.matching(
            NSPredicate(format: "placeholderValue CONTAINS 'paste the pairing code'")
        ).firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        // Paste rather than typeText: XCTest logs the argument to typeText.
        // Never include the pairing form's accessibility tree in failure logs.
        UIPasteboard.general.string = payload
        field.doubleTap()
        let paste = app.menuItems["Paste"]
        XCTAssertTrue(paste.waitForExistence(timeout: 5))
        paste.tap()
        app.buttons["Pair"].tap()
        UIPasteboard.general.string = ""
        XCTAssertTrue(app.staticTexts["Device approved"].waitForExistence(timeout: 120))
        app.buttons["Done"].tap()
        let projects = app.buttons["View all projects"]
        if !projects.waitForExistence(timeout: 10) {
            if relay.exists { relay.tap() }
            let target = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Approved'")).firstMatch
            XCTAssertTrue(target.waitForExistence(timeout: 15))
            target.tap()
        }
        XCTAssertTrue(projects.waitForExistence(timeout: 30))
        projects.tap()

        let suffix = String(UUID().uuidString.prefix(8)).lowercased()
        openCreator(app, name: "Existing folder \(suffix)")
        app.buttons["Browse server folders"].tap()
        let existing = app.buttons["existing"]
        XCTAssertTrue(existing.waitForExistence(timeout: 30))
        existing.tap()
        let choose = app.buttons["Choose this folder"]
        XCTAssertTrue(choose.waitForExistence(timeout: 15))
        capture("relay-existing-folder")
        choose.tap()
        app.navigationBars["New Project"].buttons["Create"].tap()
        XCTAssertTrue(app.staticTexts["Existing folder \(suffix)"].waitForExistence(timeout: 30))

        openCreator(app, name: "Created folder \(suffix)")
        app.buttons["Browse server folders"].tap()
        XCTAssertTrue(existing.waitForExistence(timeout: 30))
        existing.tap()
        let createFolder = app.buttons["Create folder"]
        XCTAssertTrue(createFolder.waitForExistence(timeout: 15))
        createFolder.tap()
        let name = app.textFields["Folder name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.typeText("ios-\(suffix)")
        app.alerts["Create Folder"].buttons["Create"].tap()
        let canonical = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", "/ios-\(suffix)")).firstMatch
        XCTAssertTrue(canonical.waitForExistence(timeout: 30))
        capture("relay-created-folder")
        choose.tap()
        app.navigationBars["New Project"].buttons["Create"].tap()
        XCTAssertTrue(app.staticTexts["Created folder \(suffix)"].waitForExistence(timeout: 30))
        capture("relay-registered-projects")
    }

    private func openCreator(_ app: XCUIApplication, name: String) {
        let create = app.buttons["Create project"]
        XCTAssertTrue(create.waitForExistence(timeout: 20))
        create.tap()
        let field = app.textFields["Name"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText(name)
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
