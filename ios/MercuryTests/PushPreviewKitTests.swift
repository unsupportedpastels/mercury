import XCTest
import CryptoKit
@testable import MercuryNotificationPreviewKit

final class PushPreviewKitTests: XCTestCase {
    private let wake = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private let event = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"
    private let kid = "AAECAwQFBgcICQoLDA0ODw"
    private let nonce = "AAECAwQFBgcICQoL"
    private let ciphertext = "8tl-IhMmiWLc6lGXuicsAKYdwos0AMTWiLUD5wGp0lCJohbRq3vD-0JAD_gR8fxXXKiLFpJMwbkomol19Tn3sw5YzqiEcFXL6DQk3Wxe5sPq7ncCX1rADj570mugDSCFf-5cw8fpF8WDa463qCYGVF7SWt6ll3QAK57a"
    private final class Keys: PreviewKeyReading { let record: PreviewKeyRecord?; init(_ r: PreviewKeyRecord?) { record = r }; func load(environment: String, wake: String, keyID: String, now: Int64) throws -> PreviewKeyRecord? { record } }
    private final class Replay: PreviewReplayChecking { var seen = Set<String>(); func claim(event: String, keyID: String, expiresAt: Int64, now: Int64) -> Bool { seen.insert(keyID + event).inserted } }
    private var info: [AnyHashable: Any] { ["mercury_wake": wake, "mercury_event": event, "mercury_preview": ["v": 1, "alg": "C20P", "kid": kid, "nonce": nonce, "ct": ciphertext]] }
    func testFrozenVectorDecryptsAndAADMatches() throws {
        let fixtureURL = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "v1-vector", withExtension: "json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: String])
        XCTAssertEqual(fixture["ciphertext"], ciphertext)
        XCTAssertEqual(fixture["plaintext"], "{\"v\":1,\"kind\":\"completion\",\"title\":\"Mercury test\",\"body\":\"Preview ready\",\"iat\":2000000000,\"exp\":2000000120}")
        let key = Data(0..<32), record = PreviewKeyRecord(key: key, keyID: kid, wake: wake, environment: "sandbox", createdAt: 0)
        let envelope = try PushPreviewEnvelope(userInfo: info)
        XCTAssertEqual(PushPreviewProcessor.aad(environment: "sandbox", envelope: envelope).map { String(format: "%02x", $0) }.joined(), "000000176d6572637572792e707573682d707265766965772e7631000000013100000004433230500000000773616e64626f780000002b41414543417751464267634943516f4c4441304f4478415245684d554652595847426b61477877644868380000002b494345694979516c4a69636f4b536f724c4330754c7a41784d6a4d304e5459334f446b364f7a7739506a380000001641414543417751464267634943516f4c4441304f4477")
        let preview = try PushPreviewProcessor.decrypt(userInfo: info, environment: "sandbox", now: 2_000_000_010, keys: Keys(record), replay: Replay())
        XCTAssertEqual(preview.title, "Mercury test"); XCTAssertEqual(preview.body, "Preview ready")
    }
    func testHostGeneratedCiphertextOpensThroughNativeProcessor() throws {
        let environmentPath = ProcessInfo.processInfo.environment["MERCURY_HOST_PREVIEW_VECTOR"]
        let appSupportPath = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        )[0].appendingPathComponent("host-preview-vector.json").path
        let path = environmentPath ?? appSupportPath
        guard FileManager.default.fileExists(atPath: path) else {
            throw XCTSkip("Cross-component gate supplies host-generated vector")
        }
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as? [String: String])
        let envelopeInfo: [AnyHashable: Any] = [
            "mercury_wake": try XCTUnwrap(fixture["wake_handle"]),
            "mercury_event": try XCTUnwrap(fixture["event_id"]),
            "mercury_preview": ["v": 1, "alg": "C20P", "kid": try XCTUnwrap(fixture["key_id"]),
                                "nonce": try XCTUnwrap(fixture["nonce"]), "ct": try XCTUnwrap(fixture["ciphertext"])]
        ]
        let key = try XCTUnwrap(PushPreviewEnvelope.decode(try XCTUnwrap(fixture["key"])))
        let record = PreviewKeyRecord(key: key, keyID: fixture["key_id"]!, wake: fixture["wake_handle"]!, environment: fixture["environment"]!, createdAt: 0)
        let preview = try PushPreviewProcessor.decrypt(userInfo: envelopeInfo, environment: fixture["environment"]!, now: Int64(fixture["now"]!)!, keys: Keys(record), replay: Replay())
        XCTAssertEqual(preview.title, fixture["expected_title"])
        XCTAssertEqual(preview.body, fixture["expected_body"])
    }
    func testAuthenticationScopeTamperAndReplayFailClosed() throws {
        let record = PreviewKeyRecord(key: Data(0..<32), keyID: kid, wake: wake, environment: "sandbox", createdAt: 0), replay = Replay()
        XCTAssertThrowsError(try PushPreviewProcessor.decrypt(userInfo: info, environment: "sandbox", now: 2_000_000_010, keys: Keys(nil), replay: replay))
        XCTAssertThrowsError(try PushPreviewProcessor.decrypt(userInfo: info, environment: "production", now: 2_000_000_010, keys: Keys(record), replay: replay))
        _ = try PushPreviewProcessor.decrypt(userInfo: info, environment: "sandbox", now: 2_000_000_010, keys: Keys(record), replay: replay)
        XCTAssertThrowsError(try PushPreviewProcessor.decrypt(userInfo: info, environment: "sandbox", now: 2_000_000_010, keys: Keys(record), replay: replay))
    }
    func testMalformedCanonicalAndTimeInputsFail() throws {
        var bad = info; var p = bad["mercury_preview"] as! [String: Any]; p["nonce"] = nonce + "="; bad["mercury_preview"] = p
        XCTAssertThrowsError(try PushPreviewEnvelope(userInfo: bad))
        let duplicate = Data("{\"v\":1,\"v\":1,\"kind\":\"completion\",\"iat\":10,\"exp\":11}".utf8)
        XCTAssertThrowsError(try PushPreviewPlaintext.parse(duplicate, now: 10))
        let escapedDuplicate = Data("{\"v\":1,\"\\u0076\":1,\"kind\":\"completion\",\"iat\":10,\"exp\":11}".utf8)
        XCTAssertThrowsError(try PushPreviewPlaintext.parse(escapedDuplicate, now: 10))
        let expired = Data("{\"v\":1,\"kind\":\"completion\",\"iat\":1,\"exp\":2}".utf8)
        XCTAssertThrowsError(try PushPreviewPlaintext.parse(expired, now: 10))
    }
    func testRouteLimitsUseCanonicalUTF8ByteBounds() throws {
        let valid = Data("{\"v\":1,\"kind\":\"completion\",\"route\":{\"sid\":\"\(String(repeating: "é", count: 64))\",\"profile\":\"\(String(repeating: "é", count: 32))\"},\"iat\":10,\"exp\":11}".utf8)
        XCTAssertNotNil(try PushPreviewPlaintext.parse(valid, now: 10).routeSessionID)
        for route in [
            "{\"sid\":\"\(String(repeating: "é", count: 65))\",\"profile\":\"default\"}",
            "{\"sid\":\"durable\",\"profile\":\"\(String(repeating: "é", count: 33))\"}",
        ] {
            let data = Data("{\"v\":1,\"kind\":\"completion\",\"route\":\(route),\"iat\":10,\"exp\":11}".utf8)
            XCTAssertThrowsError(try PushPreviewPlaintext.parse(data, now: 10))
        }
    }
    func testKeychainQueryPinsDedicatedNonSynchronizableGroup() {
        let query = PreviewKeychainRepository.query(environment: "sandbox", wake: wake, keyID: kid)
        XCTAssertEqual(query[kSecAttrService as String] as? String, PushPreviewConstants.keychainService)
        XCTAssertEqual(query[kSecAttrAccessGroup as String] as? String, PushPreviewConstants.accessGroup)
        XCTAssertEqual(query[kSecAttrSynchronizable as String] as? Bool, false)
        let record = PreviewKeyRecord(key: Data(0..<32), keyID: kid, wake: wake, environment: "sandbox", createdAt: 1)
        let add = PreviewKeychainRepository.addQuery(record: record, data: Data())
        XCTAssertEqual(add[kSecAttrAccessible as String] as? String, kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
    }
    func testExtensionDeadlineCompletionGateCanWinExactlyOnce() {
        let gate = PushPreviewCompletionGate()
        XCTAssertTrue(gate.claim())
        XCTAssertFalse(gate.claim())
        XCTAssertFalse(gate.claim())
    }
    func testAuthenticatedRouteStoreIsBoundAndConsumedExactlyOnce() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = PreviewRouteStore(url: url)
        let route = PreviewRouteRecord(event: event, wake: wake, sessionID: "durable", profile: "default", expiresAt: 120)
        store.record(route, now: 100)
        XCTAssertNil(store.consume(event: event, wake: String(repeating: "A", count: 43), now: 101))
        XCTAssertEqual(store.consume(event: event, wake: wake, now: 101), route)
        XCTAssertNil(store.consume(event: event, wake: wake, now: 101))
    }
    func testReplayClaimFailsClosedForCorruptStateAndWriteFailure() throws {
        let corruptURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("not-json".utf8).write(to: corruptURL)
        defer { try? FileManager.default.removeItem(at: corruptURL) }
        XCTAssertFalse(PreviewReplayStore(url: corruptURL).claim(event: event, keyID: kid, expiresAt: 120, now: 100))

        let missingParent = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("replay.json")
        XCTAssertFalse(PreviewReplayStore(url: missingParent).claim(event: event, keyID: kid, expiresAt: 120, now: 100))
    }
}
