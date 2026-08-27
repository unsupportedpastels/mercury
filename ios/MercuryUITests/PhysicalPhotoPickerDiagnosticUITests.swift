import XCTest

final class PhysicalPhotoPickerDiagnosticUITests: XCTestCase {
    func testPhotoPickerPresentationOnPhysicalIPhone() throws {
        let app = try launchComposer()
        attach("Physical iPhone chat before photo picker")

        let addAttachment = app.buttons["Attach files"]
        XCTAssertTrue(addAttachment.waitForExistence(timeout: 10), app.debugDescription)
        addAttachment.tap()

        let photoLibrary = app.buttons["Photo Library"]
        XCTAssertTrue(photoLibrary.waitForExistence(timeout: 10), app.debugDescription)
        attach("Physical iPhone attachment menu")
        photoLibrary.tap()
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 15), app.debugDescription)

        attach("Physical iPhone after Photo Library tap")
    }

    func testFilePickerPresentationOnPhysicalIPhone() throws {
        let app = try launchComposer()
        let addAttachment = app.buttons["Attach files"]
        XCTAssertTrue(addAttachment.waitForExistence(timeout: 10), app.debugDescription)
        addAttachment.tap()
        let chooseFile = app.buttons["Choose File"]
        XCTAssertTrue(chooseFile.waitForExistence(timeout: 10), app.debugDescription)
        chooseFile.tap()
        let browse = app.navigationBars["Browse"]
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(
            browse.waitForExistence(timeout: 15) || cancel.waitForExistence(timeout: 2),
            app.debugDescription
        )
        attach("Physical iPhone after Choose File tap")
    }

    private func launchComposer() throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))

        let browserCancel = app.buttons["Cancel"]
        if browserCancel.waitForExistence(timeout: 3) {
            browserCancel.tap()
            app.terminate()
            app.launch()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
        }

        let composer = app.textFields["Message Hermes"]
        if !composer.waitForExistence(timeout: 3) {
            let knownSession = app.descendants(matching: .any).matching(
                NSPredicate(format: "label CONTAINS[c] %@", "Keep latest iPhone build only")
            ).firstMatch
            XCTAssertTrue(knownSession.waitForExistence(timeout: 15), app.debugDescription)
            knownSession.tap()
        }
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        return app
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
