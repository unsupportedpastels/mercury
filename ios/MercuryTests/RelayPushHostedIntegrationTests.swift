import XCTest
import UIKit
import UserNotifications
@testable import Mercury
import MercuryNotificationPreviewKit

private final class EncryptedPreviewProbe: NSObject, UNUserNotificationCenterDelegate {
    let delivered: XCTestExpectation
    private(set) var body: String?

    init(delivered: XCTestExpectation) { self.delivered = delivered }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        body = notification.request.content.body
        delivered.fulfill()
        completionHandler([.banner, .sound])
    }
}

/// Operator-only sandbox gate. The private fixture supplies an approved
/// ephemeral target; the simulator obtains its own Apple token through the
/// real UIApplicationDelegate callback and provisions a fresh preview key over
/// the production Swift Noise connection.
@MainActor
final class RelayPushHostedIntegrationTests: XCTestCase {
    func testRealEncryptedRegistration() async throws {
        #if targetEnvironment(simulator)
        guard ProcessInfo.processInfo.environment["MERCURY_APNS_E2E"] == "1" else {
            throw XCTSkip("Operator-only: set MERCURY_APNS_E2E=1")
        }
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let fixture = directory.appendingPathComponent("apns-e2e.json")
        guard FileManager.default.fileExists(atPath: fixture.path) else {
            throw XCTSkip("Operator-only: install private apns-e2e.json in simulator app support")
        }
        let raw = try Data(contentsOf: fixture)
        guard raw.count <= 8192,
              let values = try JSONSerialization.jsonObject(with: raw) as? [String: String],
              let runID = values["run_id"], UUID(uuidString: runID) != nil,
              let origin = values["origin"], origin.hasPrefix("https://"),
              let installation = Data(base64Encoded: values["installation"] ?? ""), installation.count == 32,
              let host = Data(base64Encoded: values["host"] ?? ""), host.count == 32,
              let privateKey = Data(base64Encoded: values["private_key"] ?? ""), privateKey.count == 32,
              let device = values["device"], let routing = values["routing_token"],
              let id = UUID(uuidString: values["id"] ?? "") else {
            XCTFail("Invalid private operator fixture"); return
        }
        let target = RelayPairedTarget(id: id, label: "APNs sandbox test", relayOrigin: origin,
            installationID: installation, hostPublicKey: host, deviceID: device,
            deviceStaticPrivateKey: privateKey, fingerprint: "Operator approved test pairing",
            status: .approved, createdAtEpochSeconds: Int64(Date().timeIntervalSince1970),
            lastUsedEpochSeconds: nil, relayRoutingToken: routing)

        let notificationCenter = UNUserNotificationCenter.current()
        let previewDelivered = XCTestExpectation(description: "APNs encrypted preview reached the foreground app")
        let previewProbe = EncryptedPreviewProbe(delivered: previewDelivered)
        notificationCenter.delegate = previewProbe
        _ = try await notificationCenter.requestAuthorization(options: [.alert, .sound])
        let tokenReady = expectation(description: "Apple issued simulator APNs token")
        var appleToken: Data?
        MercuryApplicationDelegate.onToken = { token in appleToken = token; tokenReady.fulfill() }
        MercuryApplicationDelegate.onFailure = { tokenReady.fulfill() }
        UIApplication.shared.registerForRemoteNotifications()
        await fulfillment(of: [tokenReady], timeout: 30)
        let token = try XCTUnwrap(appleToken, "Apple did not issue a simulator APNs token")

        let pool = RelayConnectionPool()
        let connection = try await pool.acquire(target: target, profile: "default", channel: "apns-e2e")
        let defaults = UserDefaults(suiteName: "apns-e2e-\(UUID())")!
        let push = RelayPushCoordinator(defaults: defaults)
        push.setPreview(enabled: true, includeTitle: true, includeResponseExcerpt: true)
        push.setPreviewCategories(completion: true, attention: true)
        push.select(target); push.setEnabled(true); push.receivedToken(token)
        var registeredKeyID: String?
        var registeredWake: String?
        push.connected(target: target, identity: ObjectIdentifier(connection)) { method, params in
            let result = try await connection.relayRequest(method, params: params)
            if method == "relay.push.preview.register", let preview = params["preview"] as? [String: Any] {
                registeredKeyID = preview["key_id"] as? String
                registeredWake = result["wake_handle"] as? String
            }
            return result
        }
        await push.waitForWork()
        XCTAssertTrue(push.ownsDelivery(for: target), "Real encrypted push registration did not become the local delivery owner: \(push.status)")
        XCTAssertTrue(push.status.contains("Encrypted previews active"), "Preview capability did not become active: \(push.status)")
        let keyID = try XCTUnwrap(registeredKeyID, "Registration request did not carry a preview key id")
        let wake = try XCTUnwrap(registeredWake, "Successful registration response did not carry wake_handle")
        XCTAssertNotNil(try PreviewKeychainRepository().load(environment: "sandbox", wake: wake, keyID: keyID, now: Int64(Date().timeIntervalSince1970)), "Preview key was not written through the shared Keychain path")

        await fulfillment(of: [previewDelivered], timeout: 60)
        XCTAssertEqual(previewProbe.body, "Integrated encrypted preview")

        let result = directory.appendingPathComponent("apns-e2e-result.json")
        try JSONSerialization.data(withJSONObject: ["run_id": runID, "registered": true, "encrypted_preview": true, "keychain_key": true, "body": previewProbe.body ?? ""])
            .write(to: result, options: [.atomic, .completeFileProtection])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: result.path)
        try await Task.sleep(nanoseconds: 10_000_000_000)
        await connection.close()
        #else
        throw XCTSkip("Simulator only")
        #endif
    }
}
