#if DEBUG
import SwiftUI
import UserNotifications

/// Hermetic UI fixture: real native registration state and delegate routing,
/// with an in-memory encrypted-RPC boundary substitute. No live host/account.
struct RelayPushFixtureView: View {
    @State private var push = RelayPushCoordinator(defaults: UserDefaults(suiteName: "push-ui-\(UUID())")!)
    @State private var destination = "No host selected"
    @State private var presentation = "Not delivered"
    @State private var unexpectedRPC = 0
    @State private var delegate = NotificationDelegate()
    @State private var owner = NSObject()
    @State private var wake = Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
    @State private var target = RelayPairedTarget(id: UUID(), label: "Push fixture host", relayOrigin: "https://relay.example.test",
        installationID: Data(repeating: 1, count: 16), hostPublicKey: Data(repeating: 2, count: 32),
        deviceID: "fixture", deviceStaticPrivateKey: Data(repeating: 3, count: 32), fingerprint: "fixture",
        status: .approved, createdAtEpochSeconds: 0, lastUsedEpochSeconds: nil)

    var body: some View {
        NavigationStack {
            List {
                Text(push.status).accessibilityIdentifier("push-fixture-status")
                Text(destination).accessibilityIdentifier("push-fixture-destination")
                Text(presentation).accessibilityIdentifier("push-fixture-presentation")
                Text("Mutation RPCs: \(unexpectedRPC)").accessibilityIdentifier("push-fixture-mutations")
                Button("Deliver generic foreground push") {
                    presentation = NotificationDelegate.presentationOptions(userInfo: payload).isEmpty ? "Generic banner suppressed" : "Banner shown"
                }
                Button("Tap generic push") {
                    delegate.handlePayload(payload, completionHandler: {})
                }
                Button("Deliver direct local notification") {
                    presentation = NotificationDelegate.presentationOptions(userInfo: ["mercury.sessionID": "fixture-local"]).contains(.banner) ? "Direct local banner preserved" : "Suppressed"
                }
                Button("Disable push") { push.setEnabled(false) }
            }
            .navigationTitle("Push fixture")
        }
        .task {
            delegate.onWake = { handle in
                if let mapped = push.target(for: handle, targets: [target]) {
                    destination = "Home · \(mapped.displayLabel)"
                }
            }
            push.select(target); push.setEnabled(true)
            push.receivedToken(Data(UUID().uuidString.utf8))
            let unsupported = ProcessInfo.processInfo.arguments.contains("-uitest-push-unsupported")
            push.connected(target: target, identity: ObjectIdentifier(owner)) { method, _ in
                if method == "relay.status" { return ["capabilities": ["push_notifications_v1": !unsupported]] }
                if method == "relay.push.register" { return ["registered": true, "wake_handle": wake] }
                if method == "relay.push.unregister" { return ["registered": false] }
                unexpectedRPC += 1
                throw URLError(.unsupportedURL)
            }
            await push.waitForWork()
        }
    }
    private var payload: [AnyHashable: Any] {
        ["aps": ["alert": ["title": "Mercury", "body": "An update is available. Open Mercury to continue."], "sound": "default"], "mercury_wake": wake]
    }
}
#endif
