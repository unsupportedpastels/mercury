import UIKit
import XCTest

/// Autonomous relay repro driver: pairs this simulator as a relay device
/// using a minted offer payload (no camera, no OAuth), waits for host-side
/// approval, opens a fresh session over the relay, and runs a tool-invoking
/// turn to observe whether the streamed answer renders.
///
/// Environment:
///   MERCURY_RELAY_PAYLOAD_B64 — base64 of the pairing QR payload JSON,
///   minted host-side (create_offer + pairing_payload_text). The host
///   operator (or an auto-approve loop) must approve the pending device
///   while this test waits.
final class RelaySimulatorToolTurnUITests: XCTestCase {
    func testRelayPairAndToolTurnStreams() throws {
        guard let b64 = ProcessInfo.processInfo.environment["MERCURY_RELAY_PAYLOAD_B64"],
              let data = Data(base64Encoded: b64),
              let payload = String(data: data, encoding: .utf8),
              !payload.isEmpty
        else {
            throw XCTSkip("Set MERCURY_RELAY_PAYLOAD_B64 to a minted pairing payload")
        }

        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state"]
        app.launch()

        // Connect screen → Relay tab.
        let relaySegment = app.buttons["Relay"]
        XCTAssertTrue(relaySegment.waitForExistence(timeout: 30), app.debugDescription)
        relaySegment.tap()

        let pairButton = app.buttons["Pair with QR code"]
        XCTAssertTrue(pairButton.waitForExistence(timeout: 10), app.debugDescription)
        pairButton.tap()

        // Paste-code fallback (the simulator has no camera).
        let paste = app.textFields.matching(
            NSPredicate(format: "placeholderValue CONTAINS 'paste the pairing code'")
        ).firstMatch
        XCTAssertTrue(paste.waitForExistence(timeout: 15), app.debugDescription)
        paste.tap()
        UIPasteboard.general.string = payload
        paste.doubleTap()
        let pasteMenu = app.menuItems["Paste"]
        if pasteMenu.waitForExistence(timeout: 5) {
            pasteMenu.tap()
        } else {
            paste.typeText(payload)
        }
        app.buttons["Pair"].tap()

        // The auto-approver on the host approves the pending device; the app
        // polls and flips to the approved screen.
        let approvedTitle = app.staticTexts["Device approved"]
        XCTAssertTrue(approvedTitle.waitForExistence(timeout: 120), app.debugDescription)
        app.buttons["Done"].tap()

        // Tap the approved target to enter the normal connected experience.
        // Transient relay hiccups surface a failure banner back on the
        // connect screen; retry the tap a few times like a person would.
        let newSession = app.buttons["New session"]
        var entered = false
        for _ in 0..<4 {
            // A failed attempt re-creates the connect screen on the
            // self-hosted tab; reselect Relay before finding the target.
            if relaySegment.waitForExistence(timeout: 5) { relaySegment.tap() }
            let target = app.buttons.matching(
                NSPredicate(format: "label CONTAINS 'Approved'")
            ).firstMatch
            guard target.waitForExistence(timeout: 15) else { continue }
            target.tap()
            if newSession.waitForExistence(timeout: 30) { entered = true; break }
        }
        XCTAssertTrue(entered, app.debugDescription)
        newSession.tap()

        let composer = app.textFields.matching(
            NSPredicate(format: "placeholderValue CONTAINS 'Message Hermes'")
        ).firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 30), app.debugDescription)
        composer.tap()
        // The completion marker must be computed by the model so it can never
        // match this prompt's own transcript row.
        composer.typeText(
            "Use a tool to list the files in your current directory. "
                + "After the tool finishes, reply with the word TOOLDONE, a "
                + "hyphen, and the result of 2+2 joined together, then the "
                + "number of entries."
        )
        let send = app.buttons["Send message"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), app.debugDescription)
        send.tap()

        // Wait out the tool turn; the marker only renders if post-tool
        // deltas/completions actually reach the transcript.
        var seen = false
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline && !seen {
            RunLoop.current.run(until: Date().addingTimeInterval(2))
            // Best-effort: accept any tool approval the turn raises.
            for label in ["Approve", "Allow once", "Allow", "Yes"] {
                let approve = app.buttons[label]
                if approve.exists { approve.tap(); break }
            }
            seen = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS 'TOOLDONE-4'")
            ).count >= 1
        }

        // Full accessibility dump: includes the temporary ev/delta/rows debug
        // line and every rendered transcript row for offline analysis.
        print("RELAY_TOOL_TURN_DUMP_BEGIN\n\(app.debugDescription)\nRELAY_TOOL_TURN_DUMP_END")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "relay tool turn"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(seen, "tool-turn answer never rendered (known bug repro)")
    }
}
