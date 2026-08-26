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
        if allow.waitForExistence(timeout: 20) {
            allow.tap()
        }

        // The banner body/heading should now appear. Match on the distinctive
        // body text the DEBUG hook posts, falling back to the completion heading.
        let bannerBody = springboard.otherElements.containing(
            NSPredicate(format: "label CONTAINS[c] %@", "your task finished")
        ).firstMatch
        let bannerByStatic = springboard.staticTexts["Mercury finished"]

        let appeared = bannerBody.waitForExistence(timeout: 20)
            || bannerByStatic.waitForExistence(timeout: 5)

        XCTAssertTrue(
            appeared,
            "Expected a real iOS notification banner to render for the fired completion."
        )
    }
}
