import CryptoKit
import Foundation
import XCTest

@testable import Mercury

final class PKCETests: XCTestCase {

    // MARK: - PKCE generation

    func testChallengeMatchesSHA256() throws {
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        let digest = SHA256.hash(data: Data(verifier.utf8))
        let expected = Data(digest)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        let generated = PKCE.generate()
        XCTAssertEqual(generated.verifier.count, 43)
        XCTAssertFalse(generated.challenge.isEmpty)
        // Deterministic property: challenge of the fixed verifier equals the
        // independently computed base64url(SHA256(verifier)).
        let recomputed = PKCE.encodeBase64URLNoPadding(Data(digest))
        XCTAssertEqual(recomputed, expected)
        XCTAssertEqual(generated.challenge.count, 43)
    }

    func testVerifierLength43() {
        let pkce = PKCE.generate()
        XCTAssertEqual(pkce.verifier.count, 43)
        // Base64url alphabet only.
        XCTAssertTrue(pkce.verifier.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" })
        XCTAssertFalse(pkce.verifier.contains("="))
        XCTAssertEqual(pkce.challenge.count, 43)
    }

    func testStateUniqueAcross100Calls() {
        let states = Set((0..<100).map { _ in PKCE.randomState() })
        XCTAssertEqual(states.count, 100)
        // 16 bytes hex-encoded → 32 hex chars.
        XCTAssertTrue(states.allSatisfy { $0.count == 32 })
    }

    // MARK: - Authorize URL construction

    func testAuthorizeURLQueryParams() throws {
        let redirectURI = "http://127.0.0.1:54321/callback"
        let url = NativePKCEFlow.makeAuthorizeURL(
            origin: "https://portal.example.com",
            provider: "nous",
            state: "abc123state",
            challenge: "ch4ll3ng3_value",
            redirectURI: redirectURI
        )

        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host(), "portal.example.com")
        XCTAssertEqual(url.path, "/auth/native/authorize")

        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })

        // Matches the Hermes dashboard native_pkce contract (Android parity).
        XCTAssertEqual(query["provider"], "nous")
        XCTAssertEqual(query["state"], "abc123state")
        XCTAssertEqual(query["code_challenge"], "ch4ll3ng3_value")
        XCTAssertEqual(query["code_challenge_method"], "S256")
        XCTAssertEqual(query["redirect_uri"], redirectURI)
        // The old OAuth-style params must NOT be present.
        XCTAssertNil(query["response_type"])
        XCTAssertNil(query["client_id"])

        // redirect_uri must be percent-encoded in the raw query string.
        let rawQuery = try XCTUnwrap(components.percentEncodedQuery)
        XCTAssertTrue(rawQuery.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fcallback"))
    }

    // MARK: - Callback validation

    func testStateMismatchThrows() {
        let callback = URL(string: "http://127.0.0.1:49152/callback?code=authcode&state=wrong")!
        XCTAssertThrowsError(try NativePKCEFlow.validate(callbackURL: callback, expectedState: "expected")) { error in
            guard case FlowError.stateMismatch = error else {
                return XCTFail("expected stateMismatch, got \(error)")
            }
        }
    }

    func testValidateHappyPath() throws {
        let callback = URL(string: "http://127.0.0.1:49152/callback?code=X&state=S")!
        let result = try NativePKCEFlow.validate(callbackURL: callback, expectedState: "S")
        XCTAssertEqual(result, CallbackResult(code: "X", state: "S"))
    }

    func testValidateMissingCodeThrows() {
        let callback = URL(string: "http://127.0.0.1:49152/callback?state=S")!
        XCTAssertThrowsError(try NativePKCEFlow.validate(callbackURL: callback, expectedState: "S"))
    }
}
