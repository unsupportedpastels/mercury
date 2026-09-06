import XCTest

/// Simulator verification that the REAL local-notification delivery path
/// (NotificationCoordinator → LocalNotificationClient → UNUserNotificationCenter)
/// produces an actual iOS banner. The hermetic unit tests use a fake client and
/// cannot prove UNUserNotificationCenter itself renders anything.
///
/// Flow: launch with `-uitest-fire-notification`, grant the permission prompt,
/// then the app fires a synthetic backgrounded completion. The delegate's
/// `willPresent` returns `.banner`, so the notification surfaces even while the
/// app is foregrounded, and springboard exposes it as a queryable element.
final class NotificationDeliveryUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testFiredNotificationRendersRealBanner() {
        let app = XCUIApplication()
        app.launchArguments += ["-uitest-fire-notification"]
        app.launch()

        // Grant the notification permission prompt (system alert on springboard).
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]

        // The banner body/heading should now appear. Match on the distinctive
        // body text the DEBUG hook posts, falling back to the completion heading.
        let bannerBody = springboard.otherElements.containing(
            NSPredicate(format: "label CONTAINS[c] %@", "your task finished")
        ).firstMatch
        let bannerByStatic = springboard.staticTexts["Mercury finished"]

        // On an already-authorized simulator there is no Allow prompt. Waiting
        // only for that alert lets the transient banner disappear before querying it.
        let delivery = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            if allow.exists { allow.tap() }
            return bannerBody.exists || bannerByStatic.exists
        }, object: nil)
        let appeared = XCTWaiter.wait(for: [delivery], timeout: 25) == .completed

        XCTAssertTrue(
            appeared,
            "Expected a real iOS notification banner to render for the fired completion."
        )
    }
}
