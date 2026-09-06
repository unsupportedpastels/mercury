import XCTest
@testable import Mercury

final class RelayImageReaderTests: XCTestCase {
    func testExactSizeMimeAndCanonicalBase64() throws {
        let valid: [String: Any] = ["mime_type": "image/png", "size": 8, "base64": "iVBORw0KGgo="]
        XCTAssertEqual(try RelayImageReader.decode(valid).count, 8)
        for (key, value) in [("size", 7 as Any), ("size", true as Any), ("size", 2097153 as Any),
                             ("mime_type", "image/jpeg" as Any), ("mime_type", "image/svg+xml" as Any),
                             ("base64", "iVBORw0KGgo" as Any)] {
            var bad = valid
            bad[key] = value
            XCTAssertThrowsError(try RelayImageReader.decode(bad))
        }
    }

    @MainActor
    func testCancelledImageDrainsBeforeNextRead() async throws {
        let reader = RelayImageReader()
        let firstRead = expectation(description: "first image dispatched")
        let secondStarted = expectation(description: "second task started")
        var release: CheckedContinuation<Void, Never>?
        var statuses = 0
        var reads = 0
        let request: @MainActor (String, [String: Any]) async throws -> [String: Any] = { method, params in
            if method == "relay.status" {
                statuses += 1
                return ["capabilities": ["image_read": ["method": "relay.image.read", "max_bytes": 2097152, "mime_types": ["image/png"]]]]
            }
            reads += 1
            XCTAssertEqual(Set(params.keys), Set(["profile", "path"]))
            if reads == 1 {
                await withCheckedContinuation { release = $0; firstRead.fulfill() }
            }
            return ["mime_type": "image/png", "size": 8, "base64": "iVBORw0KGgo="]
        }
        let first = Task { try await reader.read(profile: "default", path: "/tmp/a.png", request: request) }
        await fulfillment(of: [firstRead], timeout: 3)
        let second = Task {
            secondStarted.fulfill()
            return try await reader.read(profile: "default", path: "/tmp/b.png", request: request)
        }
        await fulfillment(of: [secondStarted], timeout: 3)
        first.cancel()
        XCTAssertEqual(statuses, 1)
        release?.resume()
        do { _ = try await first.value; XCTFail("Cancelled result must not publish") }
        catch is CancellationError { }
        let bytes = try await second.value
        XCTAssertEqual(bytes.count, 8)
        XCTAssertEqual(statuses, 2)
        XCTAssertEqual(reads, 2)
    }

    func testOldHostIsUnsupportedWithoutImageRequest() async {
        do {
            _ = try await RelayImageReader.shared.read(profile: "default", path: "/tmp/a.png") { method, _ in
                XCTAssertEqual(method, "relay.status")
                return [:]
            }
            XCTFail("Old host must fail closed")
        } catch {
            XCTAssertTrue(error is RelayImageUnsupportedError)
        }
    }
}
