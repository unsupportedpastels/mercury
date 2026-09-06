import XCTest
@testable import Mercury

final class ServerOriginTests: XCTestCase {

    // MARK: - normalize

    func testDefaultsToHTTPSWhenSchemeMissing() {
        XCTAssertEqual(ServerOrigin.normalize("hermes.example.com"), "https://hermes.example.com")
    }

    func testLowercasesUppercaseHostAndScheme() {
        XCTAssertNil(ServerOrigin.normalize("HTTP://Hermes.Example.COM"))
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
        XCTAssertNil(ServerOrigin.normalize("http://hermes.example.com:443"))
    }

    func testUseTlsFlagPicksSchemeForBareHosts() {
        XCTAssertEqual(ServerOrigin.normalize("192.168.1.5:8080", useTls: false), "http://192.168.1.5:8080")
        XCTAssertNil(ServerOrigin.normalize("hermes.example.com", useTls: false))
        // An explicit scheme always wins over the flag.
        XCTAssertEqual(ServerOrigin.normalize("https://hermes.example.com", useTls: false), "https://hermes.example.com")
    }

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
