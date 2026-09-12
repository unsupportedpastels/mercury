import Foundation
import XCTest

/// SYNTHETIC network experiment, not a reproduction on the user's host.
/// Mounts release ChatView by tapping its real session NavigationLink; performs
/// live completion -> native Back -> same session -> REST/resume/REST.
/// Run ONLY on a disposable simulator with first_response_navigation_fake.py.
final class FirstResponseNavigationBoundaryUITests: XCTestCase {
    private let answerParts = ["Synthetic first answer alpha.", "Synthetic first answer omega."]
    private var origin = ""

    override func setUpWithError() throws {
        continueAfterFailure = false
        #if !targetEnvironment(simulator)
        throw XCTSkip("Disposable simulator only; never navigate physical/private chats")
        #endif
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "-first-response-fake-origin"), args.indices.contains(index + 1) {
            origin = args[index + 1]
        } else {
            origin = ProcessInfo.processInfo.environment["FIRST_RESPONSE_FAKE_ORIGIN"] ?? ""
        }
        guard let url = URL(string: origin), url.scheme == "http",
              url.host == "127.0.0.1", url.port != nil else {
            throw XCTSkip("Set FIRST_RESPONSE_FAKE_ORIGIN to the loopback synthetic server, with explicit port")
        }
        XCTAssertEqual(try control()["synthetic"] as? Bool, true)
    }

    func testFreshMultisegmentFirstAnswerSurvivesActualNavigation() throws {
        try navigate(mode: "fresh")
    }

    func testDuplicateDurableAssistantRowsRetainFirstAnswerAfterNavigation() throws {
        try navigate(mode: "duplicate")
    }

    /// Deliberately asserts the desired preservation invariant. Expected RED
    /// on the current loader if the synthetic stale response replaces resume.
    /// Do not convert this to an expected failure or call it a real-host race.
    func testSyntheticStalePostResumeReadMustNotEraseFirstAnswer() throws {
        try navigate(mode: "stale")
    }

    private func navigate(mode: String) throws {
        _ = try control(["action": "reset", "mode": mode])
        defer { _ = try? control(["action": "release"]) }
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state", "-uitest-probe", origin]
        app.launch()
        let session = app.staticTexts["E2E Session"]
        if !session.waitForExistence(timeout: 3) {
            let login = app.buttons["Sign in with username and password"]
            XCTAssertTrue(login.waitForExistence(timeout: 30))
            login.tap()
            let username = app.textFields["Username"]
            XCTAssertTrue(username.waitForExistence(timeout: 10))
            username.tap()
            username.typeText("admin")
            let password = app.secureTextFields["Password"]
            password.tap()
            password.typeText("e2epass") // public, disposable fake credential
            app.buttons["Sign in"].tap()
        }
        XCTAssertTrue(session.waitForExistence(timeout: 30))
        session.tap()
        try awaitState { state in
            let counts = state["rpc_counts"] as? [String: Int] ?? [:]
            return counts["model.options", default: 0] >= 1
        }
        let composer = app.textFields["Message Hermes"]
        XCTAssertTrue(composer.waitForExistence(timeout: 15))
        composer.tap()
        composer.typeText("synthetic first navigation turn")
        let send = app.buttons["Send message"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: send)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
        send.tap()
        assertAnswer(app)
        let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                                               object: app.buttons["Stop Hermes response"])
        XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["Send message"].waitForExistence(timeout: 10))
        try awaitState { $0["completed"] as? Bool == true }
        capture("first-completed", app)

        // Native Back actually destroys the idle route; do not relaunch or
        // reconstruct TranscriptState in place of this boundary.
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.exists)
        XCTAssertTrue(back.label == "Back" || back.label == "Mercury",
                      "Unexpected navigation control; refusing to tap another action")
        back.tap()
        XCTAssertTrue(app.navigationBars["Mercury"].waitForExistence(timeout: 15))
        XCTAssertTrue(session.waitForExistence(timeout: 15))
        _ = try control(["action": "arm"])
        let before = try control()["rpc_counts"] as? [String: Int] ?? [:]
        session.tap()
        try awaitState { $0["held"] as? Bool == true }
        assertAnswer(app) // full initial REST + resume visible before final read returns
        capture("reopened-before-followup-\(mode)", app)
        _ = try control(["action": "release"])
        try awaitState { state in
            let counts = state["rpc_counts"] as? [String: Int] ?? [:]
            return state["released"] as? Bool == true
                && counts["model.options", default: 0] > before["model.options", default: 0]
        }
        // model.options is requested only after loadTranscript returns and the
        // connection is published. This is a network fence, not a guessed sleep.
        let receipt = try control()
        XCTAssertEqual((receipt["rpc_counts"] as? [String: Int])?["prompt.submit"], 1)
        XCTAssertEqual((receipt["rpc_counts"] as? [String: Int])?["session.resume"], 2)
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: receipt, options: [.prettyPrinted]),
                                       uniformTypeIdentifier: "public.json")
        attachment.name = "synthetic-network-order-\(mode)"
        attachment.lifetime = .keepAlways
        add(attachment)
        capture("reopened-after-followup-\(mode)", app)
        assertAnswer(app)
    }

    private func assertAnswer(_ app: XCUIApplication) {
        // Both paragraphs must survive, not just an assistant role/row count.
        for part in answerParts {
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", part))
                .firstMatch.waitForExistence(timeout: 10), "Missing exact synthetic answer paragraph: \(part)")
        }
    }

    private func capture(_ name: String, _ app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    private func awaitState(_ predicate: @escaping ([String: Any]) -> Bool) throws {
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline {
            if predicate(try control()) { return }
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTFail("Synthetic network boundary not reached")
        throw NSError(domain: "FirstResponseNavigationBoundary", code: 1)
    }

    private func control(_ body: [String: String]? = nil) throws -> [String: Any] {
        var request = URLRequest(url: URL(string: origin + "/__test__/first-response-navigation")!)
        request.timeoutInterval = 5
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let done = expectation(description: "synthetic-only control")
        var output: Result<[String: Any], Error>?
        URLSession.shared.dataTask(with: request) { data, response, error in
            defer { done.fulfill() }
            do {
                if let error { throw error }
                guard (response as? HTTPURLResponse)?.statusCode == 200, let data,
                      let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw NSError(domain: "FirstResponseNavigationBoundary", code: 2)
                }
                output = .success(object)
            } catch { output = .failure(error) }
        }.resume()
        guard XCTWaiter.wait(for: [done], timeout: 6) == .completed, let output else {
            throw NSError(domain: "FirstResponseNavigationBoundary", code: 3)
        }
        return try output.get()
    }
}
