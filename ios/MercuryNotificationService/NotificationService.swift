import UserNotifications
import MercuryNotificationPreviewKit

final class NotificationService: UNNotificationServiceExtension {
    private let lock = NSLock()
    private var handler: ((UNNotificationContent) -> Void)?
    private var fallback: UNNotificationContent?
    private let completionGate = PushPreviewCompletionGate()

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        guard let mutable = request.content.mutableCopy() as? UNMutableNotificationContent else { contentHandler(request.content); return }
        // These names are local-only outputs from an older development build.
        // Strip transport-supplied values on both success and fallback.
        mutable.userInfo.removeValue(forKey: "mercury.preview.sid")
        mutable.userInfo.removeValue(forKey: "mercury.preview.profile")
        lock.lock(); handler = contentHandler; fallback = mutable; lock.unlock()
        let environment = (Bundle.main.object(forInfoDictionaryKey: "MercuryAPNSEnvironment") as? String) ?? "sandbox"
        let now = Int64(Date().timeIntervalSince1970)
        guard let replay = PreviewReplayStore() else { finish(mutable); return }
        do {
            let preview = try PushPreviewProcessor.decrypt(userInfo: mutable.userInfo, environment: environment, now: now, keys: PreviewKeychainRepository(), replay: replay)
            if let title = preview.title { mutable.title = title }
            if let body = preview.body { mutable.body = body }
            if let sid = preview.routeSessionID, let profile = preview.routeProfile,
               let event = mutable.userInfo["mercury_event"] as? String,
               let wake = mutable.userInfo["mercury_wake"] as? String {
                PreviewRouteStore()?.record(.init(event: event, wake: wake, sessionID: sid, profile: profile, expiresAt: now + PreviewRouteStore.maxRetentionSeconds), now: now)
            }
            finish(mutable)
        } catch { finish(mutable) }
    }

    override func serviceExtensionTimeWillExpire() { lock.lock(); let content = fallback; lock.unlock(); if let content { finish(content) } }

    private func finish(_ content: UNNotificationContent) {
        guard completionGate.claim() else { return }
        lock.lock(); let callback = handler; handler = nil; fallback = nil; lock.unlock()
        callback?(content)
    }
}
