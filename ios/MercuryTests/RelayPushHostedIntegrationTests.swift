import XCTest
@testable import Mercury

/// Explicit operator-only simulator gate. The private fixture contains an
/// approved ephemeral test pairing and an actual Apple-issued simulator token.
/// No live credentials or defaults are embedded in this test.
@MainActor
final class RelayPushHostedIntegrationTests: XCTestCase {
    func testRealEncryptedRegistration() async throws {
        #if targetEnvironment(simulator)
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let fixture = directory.appendingPathComponent("apns-e2e.json")
        guard FileManager.default.fileExists(atPath: fixture.path) else {
            throw XCTSkip("Operator-only: install private apns-e2e.json in simulator app support")
        }
        let raw = try Data(contentsOf: fixture)
        guard raw.count <= 8192,
              let values = try JSONSerialization.jsonObject(with: raw) as? [String: String],
              let origin = values["origin"], origin.hasPrefix("https://"),
              let installation = Data(base64Encoded: values["installation"] ?? ""), installation.count == 32,
              let host = Data(base64Encoded: values["host"] ?? ""), host.count == 32,
              let privateKey = Data(base64Encoded: values["private_key"] ?? ""), privateKey.count == 32,
              let device = values["device"], let routing = values["routing_token"],
              let token = values["device_token"], token.count >= 32, token.count <= 200, token.count % 2 == 0,
              token.allSatisfy({ $0.isHexDigit }), let id = UUID(uuidString: values["id"] ?? "") else {
            XCTFail("Invalid private operator fixture"); return
        }
        let target = RelayPairedTarget(id: id, label: "APNs sandbox test", relayOrigin: origin,
            installationID: installation, hostPublicKey: host, deviceID: device,
            deviceStaticPrivateKey: privateKey, fingerprint: "Operator approved test pairing",
            status: .approved, createdAtEpochSeconds: Int64(Date().timeIntervalSince1970),
            lastUsedEpochSeconds: nil, relayRoutingToken: routing)
        var bytes = Data()
        var index = token.startIndex
        while index < token.endIndex {
            let end = token.index(index, offsetBy: 2)
            bytes.append(try XCTUnwrap(UInt8(token[index..<end], radix: 16)))
            index = end
        }
        let pool = RelayConnectionPool()
        let connection = try await pool.acquire(target: target, profile: "default", channel: "apns-e2e")
        let push = RelayPushCoordinator()
        push.select(target); push.setEnabled(true); push.receivedToken(bytes)
        push.connected(target: target, identity: ObjectIdentifier(connection)) { method, params in
            try await connection.relayRequest(method, params: params)
        }
        await push.waitForWork()
        XCTAssertTrue(push.ownsDelivery(for: target), "Real encrypted push registration failed")
        let result = directory.appendingPathComponent("apns-e2e-result.json")
        try JSONSerialization.data(withJSONObject: ["registered": push.ownsDelivery(for: target)])
            .write(to: result, options: [.atomic, .completeFileProtection])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: result.path)
        await connection.close()
        #else
        throw XCTSkip("Simulator only")
        #endif
    }
}
