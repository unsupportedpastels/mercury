import Foundation
import XCTest

/// Real REST/WebSocket/composer lifecycle against the isolated synthetic server.
/// Test controls are used by this driver only, never by app production code.
@MainActor
final class SessionProgressEndToEndUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testReadOnlyRecoveryRejectedSendRelaunchAndLiveCompletion() async throws {
        guard let origin = ProcessInfo.processInfo.environment["FAKE_HERMES_ORIGIN"],
              let key = ProcessInfo.processInfo.environment["FAKE_HERMES_TEST_KEY"] else {
            throw XCTSkip("Isolated progress fixture not requested")
        }
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state", "-uitest-probe", origin]
        app.launch()
        signInIfNeeded(app)
        openSession(app)
        openActivity(app)
        XCTAssertTrue(app.staticTexts["Inspect progress contract"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Verify progress recovery"].exists)
        XCTAssertTrue(app.staticTexts["Install test build"].exists)
        XCTAssertTrue(app.staticTexts["In progress"].exists)
        capture(app, "ios-progress-restored-before-refresh")
        let before = try await counter(origin: origin, key: key)
        XCTAssertEqual(before["revision"] as? Int, 1)
        _ = try await counter(origin: origin, key: key, advance: true)
        app.buttons["Get progress update (read-only)"].tap()
        let done = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.staticTexts["In progress"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [done], timeout: 10), .completed)
        let after = try await counter(origin: origin, key: key)
        XCTAssertGreaterThan(after["history_reads"] as? Int ?? 0, before["history_reads"] as? Int ?? 0)
        let beforeRPCs = before["rpc_counts"] as? [String: Int] ?? [:]
        let afterRPCs = after["rpc_counts"] as? [String: Int] ?? [:]
        for method in ["session.resume", "session.create", "prompt.submit", "session.interrupt", "config.set"] {
            XCTAssertEqual(afterRPCs[method, default: 0], beforeRPCs[method, default: 0], "Read-only refresh sent \(method)")
        }
        XCTAssertTrue(app.staticTexts["Verify progress recovery"].exists)
        capture(app, "ios-progress-read-only-refreshed")
        app.navigationBars["Activity"].buttons["Done"].tap()

        let composer = app.descendants(matching: .any)["Message composer input"]
        composer.tap(); composer.typeText("synthetic-reject-send")
        app.buttons["Send message"].tap()
        XCTAssertTrue(app.staticTexts["Send failed — check the connection and try again."].waitForExistence(timeout: 10))
        XCTAssertEqual(composer.value as? String, "synthetic-reject-send")
        openActivity(app)
        XCTAssertTrue(app.staticTexts["Inspect progress contract"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Verify progress recovery"].exists)
        capture(app, "ios-progress-rejected-send-preserves-history")

        app.terminate()
        app.launchArguments = ["-uitest-probe", origin]
        app.launch()
        // The synthetic server issues a session-only cookie (no Max-Age).
        // Reauthenticate after process death; saved progress must still recover.
        signInIfNeeded(app)
        openSession(app)
        openActivity(app)
        XCTAssertTrue(app.staticTexts["Inspect progress contract"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Verify progress recovery"].exists)
        XCTAssertFalse(app.staticTexts["In progress"].exists)
        capture(app, "ios-progress-process-relaunch-restored")
        app.navigationBars["Activity"].buttons["Done"].tap()
        let reopenedComposer = app.descendants(matching: .any)["Message composer input"]
        reopenedComposer.tap(); reopenedComposer.typeText("synthetic-success")
        app.buttons["Send message"].tap()
        // Streaming text includes the native cursor glyph; completion removes it.
        let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'The answer is 42'")).firstMatch
        XCTAssertTrue(answer.waitForExistence(timeout: 10))
        let line = app.buttons["Composer activity line"]
        XCTAssertTrue(line.exists)
        capture(app, "ios-progress-real-websocket-working")
        app.buttons["Stop Hermes response"].tap()
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !line.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed)
        XCTAssertTrue(answer.exists)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Operation interrupted'")).firstMatch.exists)
        openActivity(app)
        XCTAssertTrue(app.staticTexts["Inspect progress contract"].waitForExistence(timeout: 5), "Live tool.complete snapshot was dropped")
        capture(app, "ios-progress-real-websocket-complete")
    }

    private func signInIfNeeded(_ app: XCUIApplication) {
        let passwordButton = app.buttons["Sign in with username and password"]
        if passwordButton.waitForExistence(timeout: 8) {
            passwordButton.tap()
            let user = app.textFields["Username"]
            XCTAssertTrue(user.waitForExistence(timeout: 5))
            user.tap(); user.typeText("fixture")
            app.secureTextFields["Password"].tap()
            app.secureTextFields["Password"].typeText("e2epass")
            app.buttons["Sign in"].tap()
        }
    }

    private func openSession(_ app: XCUIApplication) {
        let row = app.staticTexts["E2E Session"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "Session list unavailable: \(app.debugDescription)")
        row.tap()
        XCTAssertTrue(app.descendants(matching: .any)["Message composer input"].waitForExistence(timeout: 15))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.buttons["Change session model"].isEnabled && !app.buttons["Composer activity line"].exists
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed, "Initial resume must finish before measuring refresh")
    }
    private func openActivity(_ app: XCUIApplication) {
        app.buttons["Open session details"].tap()
        let button = app.buttons["Session activity"]
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        button.tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
    }
    private func counter(origin: String, key: String, advance: Bool = false) async throws -> [String: Any] {
        let url = try XCTUnwrap(URL(string: origin + "/__test__/progress"))
        var request = URLRequest(url: url)
        request.httpMethod = advance ? "POST" : "GET"
        request.setValue(key, forHTTPHeaderField: "X-Fake-Hermes-Test-Key")
        if advance { request.httpBody = Data("{}".utf8); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
