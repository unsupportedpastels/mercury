import Foundation
import UserNotifications

protocol LocalNotificationScheduling: Sendable {
    func requestAuthorization() async -> Bool
    func authorizationGranted() async -> Bool
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus
    func post(_ notification: PendingNotification) async
    func post(_ notification: PendingNotification, route: SessionOpenRoute?) async
    func cancel(sessionID: String) async
}

extension LocalNotificationScheduling {
    /// Route-aware post with a compatibility default so existing fakes and
    /// clients keep conforming; the real client overrides this to embed the
    /// multi-server route in the notification payload.
    func post(_ notification: PendingNotification, route: SessionOpenRoute?) async {
        await post(notification)
    }
}

enum NotificationIdentifier {
    static func inputKeys(sessionID: String) -> [String] {
        [
            "\(sessionID)|approval",
            "\(sessionID)|clarification",
            "\(sessionID)|secure"
        ]
    }

    static func belongs(identifier: String, toSession sessionID: String) -> Bool {
        guard !sessionID.isEmpty else {
            return false
        }
        return identifier.hasPrefix("\(sessionID)|")
    }
}

final class LocalNotificationClient: LocalNotificationScheduling, @unchecked Sendable {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func requestAuthorization() async -> Bool {
        do {
            return try await center.requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            return false
        }
    }

    func authorizationGranted() async -> Bool {
        let settings = await center.notificationSettings()
        return settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
    }

    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus {
        let status = await center.notificationSettings().authorizationStatus
        switch status {
        case .notDetermined:
            return .notDetermined
        case .denied:
            return .denied
        case .authorized:
            return .authorized
        case .provisional:
            return .provisional
        case .ephemeral:
            return .ephemeral
        @unknown default:
            return .unknown
        }
    }

    func post(_ notification: PendingNotification) async {
        await post(notification, route: nil)
    }

    func post(_ notification: PendingNotification, route: SessionOpenRoute?) async {
        let content = UNMutableNotificationContent()
        content.title = notification.heading
        if !notification.sessionTitle.isEmpty {
            content.subtitle = notification.sessionTitle
        }
        content.body = notification.body
        content.sound = .default
        content.threadIdentifier = notification.sessionID
        var userInfo: [String: Any] = ["mercury.sessionID": notification.sessionID]
        if let route,
           let url = MercuryDeepLink.sessionURL(
               durableSessionID: route.durableSessionID,
               serverID: route.serverID,
               profile: route.profile
           ) {
            // The route travels as the canonical mercury://session URL string,
            // so the tap handler and .onOpenURL share one strict parser.
            userInfo["mercury.route"] = url.absoluteString
        }
        content.userInfo = userInfo

        if case .completion = notification.kind {
            center.removePendingNotificationRequests(
                withIdentifiers: NotificationIdentifier.inputKeys(sessionID: notification.sessionID)
            )
        }

        let request = UNNotificationRequest(
            identifier: notification.dedupeKey,
            content: content,
            trigger: nil
        )
        try? await center.add(request)
    }

    func cancel(sessionID: String) async {
        let pending = await center.pendingNotificationRequests()
        let delivered = await center.deliveredNotifications()
        let pendingIDs = pending.map(\.identifier).filter {
            NotificationIdentifier.belongs(identifier: $0, toSession: sessionID)
        }
        let deliveredIDs = delivered.map(\.request.identifier).filter {
            NotificationIdentifier.belongs(identifier: $0, toSession: sessionID)
        }

        if !pendingIDs.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: pendingIDs)
        }
        if !deliveredIDs.isEmpty {
            center.removeDeliveredNotifications(withIdentifiers: deliveredIDs)
        }
    }
}
