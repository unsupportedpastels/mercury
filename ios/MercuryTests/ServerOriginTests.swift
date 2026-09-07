import XCTest
@testable import Mercury

final class ServerOriginTests: XCTestCase {

    // MARK: - normalize

    func testPunycodesUnicodeHosts() {
        XCTAssertEqual(ServerOrigin.normalize("https://例え.テスト/"), "https://xn--r8jz45g.xn--zckzah")
        XCTAssertEqual(ServerOrigin.normalize("https://faß.de"), "https://fass.de")
        XCTAssertEqual(ServerOrigin.normalize("https://ς.gr"), "https://xn--4xa.gr")
        XCTAssertEqual(ServerOrigin.normalize("https://a\u{200C}b.example"), "https://ab.example")
        XCTAssertEqual(ServerOrigin.normalize("https://ＦＯＯ.example"), "https://foo.example")
        XCTAssertEqual(ServerOrigin.normalize("https://foo\u{00AD}bar.example"), "https://foobar.example")
        XCTAssertEqual(ServerOrigin.normalize("https://foo\u{3002}example"), "https://foo.example")
        XCTAssertEqual(ServerOrigin.normalize("https://example.com."), "https://example.com.")
        XCTAssertNil(ServerOrigin.normalize("https://ẞ.de"))
    }

    func testLegacyNormalizePreservesDefaultPortForMigration() {
        XCTAssertEqual(ServerOrigin.legacyNormalize("https://hermes.example.com:443"), "https://hermes.example.com:443")
        XCTAssertEqual(ServerOrigin.legacyNormalize("hermes.example.com"), "https://hermes.example.com")
    }

    func testLegacyCredentialAccountCandidatesRestoreElidedDefaultPorts() {
        XCTAssertEqual(
            ServerOrigin.legacyCredentialAccountCandidates(for: "https://hermes.example.com"),
            ["https://hermes.example.com:443"]
        )
        XCTAssertEqual(
            ServerOrigin.legacyCredentialAccountCandidates(for: "http://localhost"),
            ["http://localhost:80"]
        )
        XCTAssertEqual(
            ServerOrigin.legacyCredentialAccountCandidates(for: "https://hermes.example.com:8443"),
            []
        )
    }

    // MARK: - isLoopbackOrPrivate

    func testLoopbackDetection() {
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("http://127.0.0.1:8080"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("https://localhost"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("http://127.255.0.9"))
        XCTAssertEqual(ServerOrigin.normalize("http://localhost."), "http://localhost.")
        XCTAssertEqual(ServerOrigin.normalize("http://127.0.0.1."), "http://127.0.0.1.")
    }

    // MARK: - validationFailure (shared reason surfaces on iOS)

    func testPublicPlainHTTPIsRejectedWithTheSharedReason() {
        XCTAssertNil(ServerOrigin.normalize("http://hermes.example.com"))
        XCTAssertEqual(
            ServerOrigin.validationFailure("http://hermes.example.com"),
            "Plain HTTP is allowed only for local or private-network servers"
        )
        XCTAssertNil(ServerOrigin.normalize("hermes.example.com", useTls: false))
        XCTAssertNotNil(ServerOrigin.validationFailure("hermes.example.com", useTls: false))
        XCTAssertNil(ServerOrigin.validationFailure("192.168.1.20:8080", useTls: false))
        XCTAssertNil(ServerOrigin.validationFailure("hermes.example.com"))
    }

    // MARK: - allowsCleartextHTTP (cleartext allowed only for private hosts)

    func testCleartextAllowedOnlyForPrivateHosts() {
        // Private + http → allowed.
        XCTAssertTrue(ServerOrigin.allowsCleartextHTTP("http://192.168.1.20:8080"))
        XCTAssertTrue(ServerOrigin.allowsCleartextHTTP("http://localhost"))

        // Public + http → never allowed.
        XCTAssertFalse(ServerOrigin.allowsCleartextHTTP("http://hermes.example.com"))

        // Private but already https → cleartext rule is irrelevant/false.
        XCTAssertFalse(ServerOrigin.allowsCleartextHTTP("https://192.168.1.20"))

        // Garbage → false.
        XCTAssertFalse(ServerOrigin.allowsCleartextHTTP("nonsense"))
    }
}
