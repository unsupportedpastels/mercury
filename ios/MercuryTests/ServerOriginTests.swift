import XCTest
@testable import Mercury

final class ServerOriginTests: XCTestCase {

    // MARK: - normalize

    func testDefaultsToHTTPSWhenSchemeMissing() {
        XCTAssertEqual(ServerOrigin.normalize("hermes.example.com"), "https://hermes.example.com")
    }

    func testLowercasesUppercaseHostAndScheme() {
        XCTAssertEqual(
            ServerOrigin.normalize("HTTP://Hermes.Example.COM"),
            "http://hermes.example.com"
        )
        XCTAssertEqual(
            ServerOrigin.normalize("  Hermes.Example.Com  "),
            "https://hermes.example.com"
        )
    }

    func testStripsSingleTrailingSlash() {
        XCTAssertEqual(ServerOrigin.normalize("https://hermes.example.com/"), "https://hermes.example.com")
        XCTAssertEqual(ServerOrigin.normalize("hermes.example.com/"), "https://hermes.example.com")
    }

    func testRejectsPathsQueriesAndFragments() {
        XCTAssertNil(ServerOrigin.normalize("https://hermes.example.com/api"))
        XCTAssertNil(ServerOrigin.normalize("https://hermes.example.com/?q=1"))
        XCTAssertNil(ServerOrigin.normalize("https://hermes.example.com#frag"))
    }

    func testReturnsNilOnGarbage() {
        XCTAssertNil(ServerOrigin.normalize(""))
        XCTAssertNil(ServerOrigin.normalize("   "))
        XCTAssertNil(ServerOrigin.normalize("not a url at all"))
        XCTAssertNil(ServerOrigin.normalize("https://"))
        XCTAssertNil(ServerOrigin.normalize("ftp://hermes.example.com")) // non-HTTP scheme
        XCTAssertNil(ServerOrigin.normalize("http:hermes.example.com")) // scheme without //
        XCTAssertNil(ServerOrigin.normalize("://example.com"))
        XCTAssertNil(ServerOrigin.normalize("https://example.com:notaport"))
    }

    func testPreservesPortAndCleartextScheme() {
        XCTAssertEqual(ServerOrigin.normalize("10.1.2.3:8080"), "https://10.1.2.3:8080")
        XCTAssertEqual(ServerOrigin.normalize("http://localhost:8080"), "http://localhost:8080")
    }

    func testElidesDefaultPorts() {
        XCTAssertEqual(ServerOrigin.normalize("https://hermes.example.com:443"), "https://hermes.example.com")
        XCTAssertEqual(ServerOrigin.normalize("http://10.1.2.3:80"), "http://10.1.2.3")
        XCTAssertEqual(ServerOrigin.normalize("http://hermes.example.com:443"), "http://hermes.example.com:443")
    }

    func testUseTlsFlagPicksSchemeForBareHosts() {
        XCTAssertEqual(ServerOrigin.normalize("192.168.1.5:8080", useTls: false), "http://192.168.1.5:8080")
        XCTAssertEqual(ServerOrigin.normalize("hermes.example.com", useTls: false), "http://hermes.example.com")
        // An explicit scheme always wins over the flag.
        XCTAssertEqual(ServerOrigin.normalize("https://hermes.example.com", useTls: false), "https://hermes.example.com")
    }

    func testPunycodesUnicodeHosts() {
        XCTAssertEqual(ServerOrigin.normalize("https://例え.テスト/"), "https://xn--r8jz45g.xn--zckzah")
    }

    func testLegacyNormalizePreservesDefaultPortForMigration() {
        XCTAssertEqual(ServerOrigin.legacyNormalize("https://hermes.example.com:443"), "https://hermes.example.com:443")
        XCTAssertEqual(ServerOrigin.legacyNormalize("hermes.example.com"), "https://hermes.example.com")
    }

    // MARK: - isLoopbackOrPrivate

    func testLoopbackDetection() {
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("http://127.0.0.1:8080"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("https://localhost"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("http://127.255.0.9"))
    }

    func testRFC1918Detection() {
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("http://10.0.0.5"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("http://192.168.1.20:8080"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("https://172.16.0.1"))
        XCTAssertTrue(ServerOrigin.isLoopbackOrPrivate("https://172.31.255.255"))
    }

    func testPublicHostsAreNotPrivate() {
        XCTAssertFalse(ServerOrigin.isLoopbackOrPrivate("https://mercury.unsupportedpastels.dev"))
        XCTAssertFalse(ServerOrigin.isLoopbackOrPrivate("https://172.32.0.1"))
        XCTAssertFalse(ServerOrigin.isLoopbackOrPrivate("https://172.15.0.1"))
        XCTAssertFalse(ServerOrigin.isLoopbackOrPrivate("https://11.0.0.1"))
        XCTAssertFalse(ServerOrigin.isLoopbackOrPrivate("https://192.169.0.1"))
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
