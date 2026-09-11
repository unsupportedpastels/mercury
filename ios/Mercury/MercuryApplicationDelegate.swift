import UIKit

/// APNs token callbacks never log the token or Apple's error details.
@MainActor
final class MercuryApplicationDelegate: NSObject, UIApplicationDelegate {
    static var onToken: ((Data) -> Void)?
    static var onFailure: (() -> Void)?
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Self.onToken?(deviceToken)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Self.onFailure?()
    }
}
