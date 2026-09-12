#if DEBUG
import SwiftUI
import UserNotifications

/// Synthetic events; production coordinator, scheduler, delegate and OS delivery.
/// The pause is AFTER the enqueue decision, reproducing a deferred local post.
struct NotificationArrivalFixtureView: View {
    @Environment(AppModel.self) private var model
    @State private var fixture = NotificationArrivalFixture()
    var body: some View {
        VStack(spacing: 18) {
            Text("Notification arrival fixture").font(.headline)
            Text("Visible chat: \(fixture.visible)")
            Text("The response is already visible in this chat.")
            Text(fixture.status).accessibilityIdentifier("arrival-status")
            Button("Queue then open current") { fixture.post(model, mode: .openCurrent) }
            Button("Post for other session") { fixture.post(model, mode: .other) }
            Button("Queue then background") { fixture.post(model, mode: .background) }
            Button("Repeat same completion") { fixture.repeatLast(model) }
            Button("Read delivered notifications") { Task { await fixture.readDelivered() } }
            Text("Delivered: \(fixture.delivered)").accessibilityIdentifier("arrival-delivered")
        }
        .padding()
        .task { await fixture.prepare(model) }
    }
}

@MainActor @Observable
private final class NotificationArrivalFixture {
    enum Mode { case openCurrent, other, background }
    var visible = "none"
    var status = "Preparing"
    var delivered = 0
    private let prefix = "arrival-\(UUID().uuidString)"
    private let client = DeferredArrivalClient()
    private var coordinator: NotificationCoordinator?
    private var last: ChatEvent?
    func prepare(_ model: AppModel) async {
        model.setServerOrigin("https://notification-fixture.example")
        let coordinator = NotificationCoordinator(client: client, store: UserDefaultsWatermarkStore(userDefaults: UserDefaults(suiteName: prefix)!))
        self.coordinator = coordinator
        coordinator.configure(origin: "https://notification-fixture.example")
        coordinator.sourceScope = model.notificationSourceScope
        coordinator.shouldDeliver = { model.shouldPresentNotification($0) }
        let granted = await client.requestAuthorization()
        status = granted ? "Ready" : "Permission denied"
    }
    func post(_ model: AppModel, mode: Mode) {
        guard let coordinator else { return }
        let sid = prefix + (mode == .other ? "-other" : mode == .background ? "-background" : "-current")
        model.setVisibleSession(mode == .openCurrent || mode == .background ? nil : prefix + "-current")
        visible = mode == .other ? "current" : "none"
        let label = mode == .other ? "Fixture other session" : mode == .background ? "Fixture background" : "Fixture current session"
        let event = ChatEvent.messageComplete(sessionID: sid, text: label, status: "finished", error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil)
        last = event
        client.beforeDelivery = { [weak self] in
            if mode != .other { model.setVisibleSession(sid); self?.visible = "current" }
            self?.status = "Queued"
        }
        Task {
            await coordinator.handleLive(event: event, sessionTitle: label, visibility: model.notificationVisibility)
            status = "Delivery finished"
        }
    }
    func repeatLast(_ model: AppModel) {
        guard let last, let coordinator else { return }
        Task {
            await coordinator.handleLive(event: last, sessionTitle: "Repeat", visibility: .init())
            status = "Repeat finished"
            await readDelivered()
        }
    }
    func readDelivered() async {
        delivered = await UNUserNotificationCenter.current().deliveredNotifications().filter { $0.request.content.threadIdentifier.hasPrefix(prefix) }.count
    }
}

private final class DeferredArrivalClient: LocalNotificationScheduling, @unchecked Sendable {
    let real = LocalNotificationClient()
    var beforeDelivery: (@MainActor () -> Void)?
    func requestAuthorization() async -> Bool { await real.requestAuthorization() }
    func authorizationGranted() async -> Bool { await real.authorizationGranted() }
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus { await real.authorizationStatus() }
    func cancel(sessionID: String) async { await real.cancel(sessionID: sessionID) }
    func post(_ notification: PendingNotification) async { await real.post(notification) }
    func post(_ notification: PendingNotification, route: SessionOpenRoute?, identity: NotificationSessionIdentity?) async {
        await beforeDelivery?()
        try? await Task.sleep(for: .seconds(4))
        await real.post(notification, route: route, identity: identity)
    }
}
#endif
