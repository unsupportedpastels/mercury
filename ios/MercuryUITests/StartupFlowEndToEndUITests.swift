import Foundation
import XCTest

/// Bounded startup/UI regression coverage against the opt-in fake Hermes
/// scenario. The ordinary UI-test scheme stays hermetic when the scenario is
/// not requested.
///
/// Run the harness with a fake backend started as:
///   FAKE_HERMES_SCENARIO=ios-startup python3 tools/fake-hermes/fake_hermes.py 8787
/// and the UI tests as:
///   FAKE_HERMES_SCENARIO=ios-startup FAKE_HERMES_ORIGIN=http://127.0.0.1:8787 xcodebuild …
final class StartupFlowEndToEndUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testHomeChromeShowsProfilesLeftSearchAndSettingsRightWithoutHostFiles() throws {
        let app = try launchConnectedApp()

        let profile = app.buttons["Profile: default"]
        let search = app.buttons["Search sessions"]
        let settings = app.buttons["Settings"]
        XCTAssertTrue(profile.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(settings.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertLessThan(profile.frame.midX, search.frame.midX, "profile must stay on the leading side")
        XCTAssertLessThan(search.frame.midX, settings.frame.midX, "search must precede settings on the trailing side")
        XCTAssertGreaterThan(search.frame.midX, app.navigationBars["Mercury"].frame.midX)
        capture("startup-home-toolbar", app: app)

        profile.tap()
        let defaultChoice = app.descendants(matching: .any).matching(
            NSPredicate(format: "label == %@", "default")
        ).firstMatch
        let workChoice = app.descendants(matching: .any).matching(
            NSPredicate(format: "label == %@", "work")
        ).firstMatch
        XCTAssertTrue(defaultChoice.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(workChoice.waitForExistence(timeout: 10), app.debugDescription)
        capture("startup-profiles", app: app)
        workChoice.tap()
        XCTAssertTrue(app.staticTexts["Work Session"].waitForExistence(timeout: 30), app.debugDescription)

        let hostFiles = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS[c] %@", "Host files")
        ).firstMatch
        XCTAssertFalse(hostFiles.exists, "the home toolbar must not expose Host files")

        settings.tap()
        let servers = app.staticTexts["Servers"]
        XCTAssertTrue(servers.waitForExistence(timeout: 10), app.debugDescription)
        servers.tap()
        XCTAssertTrue(app.navigationBars["Servers"].waitForExistence(timeout: 10), app.debugDescription)
        capture("startup-settings-servers", app: app)
        let settingsBack = app.navigationBars["Servers"].buttons["Settings"]
        XCTAssertTrue(settingsBack.waitForExistence(timeout: 5), "Servers must retain native Back navigation")
        settingsBack.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    }

    func testProjectsAddBrowseCreateSelectFolderAndExposeBack() throws {
        let app = try launchConnectedApp()
        let viewAll = app.buttons["View all projects"]
        XCTAssertTrue(viewAll.waitForExistence(timeout: 30), app.debugDescription)
        viewAll.tap()

        let projectsNavigationBar = app.navigationBars["Projects"]
        XCTAssertTrue(projectsNavigationBar.waitForExistence(timeout: 20), app.debugDescription)
        let createProject = app.buttons["Create project"]
        XCTAssertTrue(createProject.waitForExistence(timeout: 10), app.debugDescription)
        createProject.tap()

        XCTAssertTrue(app.navigationBars["New Project"].waitForExistence(timeout: 10), app.debugDescription)
        let projectSuffix = String(UUID().uuidString.prefix(8)).lowercased()
        let projectName = "Startup UI Project \(projectSuffix)"
        let folderName = "ios-startup-\(projectSuffix)"

        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10), app.debugDescription)
        name.tap()
        name.typeText(projectName)

        let chooseFolder = app.buttons["Browse server folders"]
        XCTAssertTrue(chooseFolder.waitForExistence(timeout: 10), app.debugDescription)
        chooseFolder.tap()

        XCTAssertTrue(app.navigationBars["Host Files"].waitForExistence(timeout: 20), app.debugDescription)
        let filesBack = app.navigationBars["Host Files"].buttons["Back"]
        XCTAssertTrue(filesBack.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertLessThan(filesBack.frame.midX, app.navigationBars["Host Files"].frame.midX)
        capture("startup-host-files", app: app)
        filesBack.tap()
        XCTAssertTrue(app.navigationBars["New Project"].waitForExistence(timeout: 10), app.debugDescription)
        chooseFolder.tap()
        let workspace = app.buttons["workspace"]
        XCTAssertTrue(workspace.waitForExistence(timeout: 20), app.debugDescription)
        workspace.tap()

        let createFolder = app.buttons["Create folder"]
        XCTAssertTrue(createFolder.waitForExistence(timeout: 10), app.debugDescription)
        createFolder.tap()
        let folderNameField = app.textFields["Folder name"]
        XCTAssertTrue(folderNameField.waitForExistence(timeout: 10), app.debugDescription)
        folderNameField.typeText(folderName)
        app.alerts["Create Folder"].buttons["Create"].tap()

        let selectedPath = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", folderName)
        ).firstMatch
        XCTAssertTrue(selectedPath.waitForExistence(timeout: 20), app.debugDescription)
        capture("startup-created-folder", app: app)
        let chooseThisFolder = app.buttons["Choose this folder"]
        XCTAssertTrue(chooseThisFolder.waitForExistence(timeout: 10), app.debugDescription)
        chooseThisFolder.tap()

        XCTAssertTrue(app.navigationBars["New Project"].waitForExistence(timeout: 10), app.debugDescription)
        let create = app.navigationBars["New Project"].buttons["Create"]
        XCTAssertTrue(create.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(create.isEnabled, app.debugDescription)
        create.tap()

        XCTAssertTrue(projectsNavigationBar.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(app.staticTexts[projectName].waitForExistence(timeout: 20), app.debugDescription)
        let back = app.buttons["Back"]
        XCTAssertTrue(back.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertLessThan(back.frame.midX, projectsNavigationBar.frame.midX, "Projects Back must be top-left")
        back.tap()
        XCTAssertTrue(app.navigationBars["Mercury"].waitForExistence(timeout: 20), app.debugDescription)
    }

    func testExistingSessionAcceptsTwoConsecutiveTurnsWithoutReopen() throws {
        let app = try launchConnectedApp()
        let session = app.staticTexts["E2E Session"]
        XCTAssertTrue(session.waitForExistence(timeout: 30), app.debugDescription)
        session.tap()

        let suffix = UUID().uuidString
        send("first startup turn \(suffix)", expect: "First startup response", in: app)
        send("second startup turn \(suffix)", expect: "Second startup response", in: app)
        let context = app.buttons.matching(identifier: "Open session details")
        XCTAssertTrue(context.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(context.count, 1, "Only the composer context control should remain")
        XCTAssertFalse(app.navigationBars.buttons["Open session details"].exists)
        capture("startup-two-turn-chat", app: app)
    }

    func testDelayedFirstAckDoesNotBlockSecondTurnOrRestoreFirstDraft() throws {
        let app = try launchConnectedApp(requireDelayedPromptAck: true)
        let session = app.staticTexts["E2E Session"]
        XCTAssertTrue(session.waitForExistence(timeout: 30), app.debugDescription)
        session.tap()

        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        let suffix = UUID().uuidString
        let firstDraft = "first delayed turn \(suffix)"
        let secondDraft = "second delayed turn \(suffix)"
        composer.tap()
        composer.typeText(firstDraft)
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        send.tap()

        // The startup fake releases message.complete before prompt.submit's
        // first response. Sending immediately after the final reply keeps the
        // first request's ACK outstanding while the second request is made.
        let first = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "First startup response: \(firstDraft)")
        ).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 30), app.debugDescription)

        composer.tap()
        composer.typeText(secondDraft)
        XCTAssertTrue(send.isEnabled, "the late first ACK must not keep the second send disabled")
        send.tap()

        let second = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Second startup response: \(secondDraft)")
        ).firstMatch
        XCTAssertTrue(second.waitForExistence(timeout: 30), app.debugDescription)
        let draft = (composer.value as? String) ?? ""
        XCTAssertFalse(draft.contains(firstDraft), "a late first response restored the accepted draft")
        XCTAssertFalse(app.staticTexts["Send failed — check the connection and try again."].exists)
        capture("startup-delayed-ack-chat", app: app)
    }

    @discardableResult
    private func launchConnectedApp(requireDelayedPromptAck: Bool = false) throws -> XCUIApplication {
        guard let origin = ProcessInfo.processInfo.environment["FAKE_HERMES_ORIGIN"]?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !origin.isEmpty else {
            throw XCTSkip("FAKE_HERMES_ORIGIN not set; startup fake UI tests not requested")
        }
        let environment = ProcessInfo.processInfo.environment
        let scenario = environment["FAKE_HERMES_SCENARIO"]?.lowercased()
        guard scenario == "ios-startup"
                || scenario == "startup"
                || scenario == "startup-flow"
                || scenario == "startup_flow"
                || environment["FAKE_HERMES_STARTUP"] == "1" else {
            throw XCTSkip("Set FAKE_HERMES_SCENARIO=ios-startup to run startup fake UI tests")
        }
        if requireDelayedPromptAck {
            guard environment["FAKE_HERMES_DELAY_FIRST_PROMPT_ACK"] == "1" else {
                throw XCTSkip("Set FAKE_HERMES_DELAY_FIRST_PROMPT_ACK=1 for the delayed-ACK UI regression")
            }
        }

        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state", "-uitest-probe", origin]
        app.launch()

        // Cookies are deliberately preserved between app launches. A prior
        // test's still-valid fixture login may go straight to the home list.
        if app.staticTexts["E2E Session"].waitForExistence(timeout: 3) { return app }
        let passwordButton = app.buttons["Sign in with username and password"]
        XCTAssertTrue(passwordButton.waitForExistence(timeout: 30), app.debugDescription)
        passwordButton.tap()
        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10), app.debugDescription)
        username.tap()
        username.typeText("admin")
        let password = app.secureTextFields["Password"]
        XCTAssertTrue(password.waitForExistence(timeout: 10), app.debugDescription)
        password.tap()
        password.typeText("e2epass")
        app.buttons["Sign in"].tap()

        XCTAssertTrue(app.staticTexts["E2E Session"].waitForExistence(timeout: 30), app.debugDescription)
        return app
    }

    private func send(_ text: String, expect response: String, in app: XCUIApplication) {
        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        composer.tap()
        composer.typeText(text)
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        send.tap()

        let assistant = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "\(response): \(text)")
        ).firstMatch
        XCTAssertTrue(assistant.waitForExistence(timeout: 30), app.debugDescription)
    }

    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
