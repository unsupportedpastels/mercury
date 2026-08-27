import Foundation
import XCTest
@testable import Mercury

final class MercuryDeepLinkTests: XCTestCase {
    private let serverID = UUID(uuidString: "00000000-0000-0000-0000-000000000042")!

    func testRoundTripsASCIIIDAndProfile() throws {
        let route = SessionOpenRoute(
            durableSessionID: "session-123",
            serverID: serverID,
            profile: "default"
        )

        let url = try XCTUnwrap(
            MercuryDeepLink.sessionURL(
                durableSessionID: route.durableSessionID,
                serverID: route.serverID,
                profile: route.profile
            )
        )

        XCTAssertEqual(MercuryDeepLink.parse(url), route)
    }

    func testRoundTripsUnicodeIDAndProfile() throws {
        let route = SessionOpenRoute(
            durableSessionID: "会话-☕️-42",
            serverID: serverID,
            profile: "工作/默认-日本語"
        )

        let url = try XCTUnwrap(
            MercuryDeepLink.sessionURL(
                durableSessionID: route.durableSessionID,
                serverID: route.serverID,
                profile: route.profile
            )
        )

        XCTAssertEqual(MercuryDeepLink.parse(url), route)
    }

    func testSchemeComparisonIsCaseInsensitive() throws {
        let url = try XCTUnwrap(makeURL(scheme: "MERCURY"))
        XCTAssertNotNil(MercuryDeepLink.parse(url))
    }

    func testWrongSchemeIsRejected() throws {
        let url = try XCTUnwrap(makeURL(scheme: "https"))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testWrongHostIsRejected() throws {
        let url = try XCTUnwrap(makeURL(host: "other"))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testMissingIDIsRejected() throws {
        let url = try XCTUnwrap(makeURL(id: nil))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testMissingServerIsRejected() throws {
        let url = try XCTUnwrap(makeURL(server: nil))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testMissingProfileIsRejected() throws {
        let url = try XCTUnwrap(makeURL(profile: nil))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testBlankIDIsRejected() throws {
        let url = try XCTUnwrap(makeURL(id: "   "))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testMalformedUUIDIsRejected() throws {
        let url = try XCTUnwrap(makeURL(server: "not-a-uuid"))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testControlCharactersInValuesAreRejected() throws {
        for control in ["\n", "\0", "\u{1}"] {
            let url = try XCTUnwrap(makeURL(id: "session\(control)"))
            XCTAssertNil(MercuryDeepLink.parse(url), "Expected control character to be rejected")
        }
    }

    func testDuplicateIDParameterIsRejected() throws {
        let url = try XCTUnwrap(
            makeURL(additionalItems: [URLQueryItem(name: "id", value: "second")])
        )
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testDuplicateServerParameterIsRejected() throws {
        let url = try XCTUnwrap(
            makeURL(additionalItems: [URLQueryItem(name: "server", value: serverID.uuidString)])
        )
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testDuplicateProfileParameterIsRejected() throws {
        let url = try XCTUnwrap(
            makeURL(additionalItems: [URLQueryItem(name: "profile", value: "other")])
        )
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testOversizedIDIsRejected() throws {
        let url = try XCTUnwrap(makeURL(id: String(repeating: "i", count: 257)))
        XCTAssertNil(MercuryDeepLink.parse(url))
        XCTAssertNil(
            MercuryDeepLink.sessionURL(
                durableSessionID: String(repeating: "i", count: 257),
                serverID: serverID,
                profile: "default"
            )
        )
    }

    func testOversizedProfileIsRejected() throws {
        let url = try XCTUnwrap(makeURL(profile: String(repeating: "p", count: 65)))
        XCTAssertNil(MercuryDeepLink.parse(url))
        XCTAssertNil(
            MercuryDeepLink.sessionURL(
                durableSessionID: "session",
                serverID: serverID,
                profile: String(repeating: "p", count: 65)
            )
        )
    }

    func testOriginParameterIsRejected() throws {
        let url = try XCTUnwrap(
            makeURL(additionalItems: [
                URLQueryItem(name: "origin", value: "https://server.example")
            ])
        )
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testExtraPathIsRejected() throws {
        let url = try XCTUnwrap(makeURL(path: "/extra"))
        XCTAssertNil(MercuryDeepLink.parse(url))
    }

    func testBuilderRejectsBlankSessionID() {
        XCTAssertNil(
            MercuryDeepLink.sessionURL(
                durableSessionID: "\t\n",
                serverID: serverID,
                profile: "default"
            )
        )
    }

    func testBuilderOutputParsesBackToIdenticalRoute() throws {
        let route = SessionOpenRoute(
            durableSessionID: "build-and-parse",
            serverID: serverID,
            profile: "research"
        )
        let url = try XCTUnwrap(
            MercuryDeepLink.sessionURL(
                durableSessionID: route.durableSessionID,
                serverID: route.serverID,
                profile: route.profile
            )
        )

        XCTAssertEqual(MercuryDeepLink.parse(url), route)
    }

    func testReservedCharactersInSessionIDRoundTrip() throws {
        let sessionID = "amp&equals=question?hash#slash/"
        let url = try XCTUnwrap(
            MercuryDeepLink.sessionURL(
                durableSessionID: sessionID,
                serverID: serverID,
                profile: "default"
            )
        )

        XCTAssertEqual(MercuryDeepLink.parse(url)?.durableSessionID, sessionID)
    }

    func testPercentEncodedReservedCharactersInSessionIDParseCorrectly() throws {
        let url = try XCTUnwrap(
            URL(string: "mercury://session?id=amp%26equals%3Dquestion%3Fhash%23slash%2F&server=\(serverID.uuidString)&profile=default")
        )

        XCTAssertEqual(
            MercuryDeepLink.parse(url)?.durableSessionID,
            "amp&equals=question?hash#slash/"
        )
    }

    private func makeURL(
        id: String? = "session",
        server: String? = "00000000-0000-0000-0000-000000000042",
        profile: String? = "default",
        scheme: String = "mercury",
        host: String = "session",
        path: String = "",
        additionalItems: [URLQueryItem] = []
    ) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.path = path
        components.queryItems = [
            id.map { URLQueryItem(name: "id", value: $0) },
            server.map { URLQueryItem(name: "server", value: $0) },
            profile.map { URLQueryItem(name: "profile", value: $0) }
        ].compactMap { $0 } + additionalItems
        return components.url
    }
}
