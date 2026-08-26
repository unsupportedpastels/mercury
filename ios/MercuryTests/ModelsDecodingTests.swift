import Foundation
import XCTest
@testable import Mercury

final class ModelsDecodingTests: XCTestCase {

    // MARK: - HermesStatus (snake_case keys)

    func testHermesStatusDecodesSnakeCaseKeys() throws {
        let json = """
        {
          "version": "0.6.2",
          "auth_required": true,
          "active_sessions": 3,
          "unknown_future_field": {"nested": true}
        }
        """
        let status = try JSONDecoder().decode(HermesStatus.self, from: Data(json.utf8))
        XCTAssertEqual(status.version, "0.6.2")
        XCTAssertTrue(status.authRequired)
        XCTAssertEqual(status.activeSessions, 3)
    }

    func testHermesStatusToleratesMissingOptionalFields() throws {
        let json = #"{"version": "1.0.0"}"#
        let status = try JSONDecoder().decode(HermesStatus.self, from: Data(json.utf8))
        XCTAssertEqual(status.version, "1.0.0")
        XCTAssertFalse(status.authRequired)
        XCTAssertNil(status.activeSessions)
    }

    // MARK: - AuthProvidersResponse

    func testAuthProvidersResponseDecodes() throws {
        let json = """
        {
          "providers": [
            {
              "name": "nous",
              "display_name": "Sign in with Nous",
              "supports_password": false,
              "extra_field_ignored": true
            },
            {
              "name": "local",
              "supports_password": true
            }
          ]
        }
        """
        let response = try JSONDecoder().decode(AuthProvidersResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.providers.count, 2)

        XCTAssertEqual(response.providers[0].name, "nous")
        XCTAssertEqual(response.providers[0].displayName, "Sign in with Nous")
        XCTAssertFalse(response.providers[0].supportsPassword)

        // display_name missing → falls back to name; supports_password missing → false.
        XCTAssertEqual(response.providers[1].displayName, "local")
        XCTAssertTrue(response.providers[1].supportsPassword)
    }

    func testAuthProvidersToleratesMissingProvidersArray() throws {
        let response = try JSONDecoder().decode(AuthProvidersResponse.self, from: Data(#"{}"#.utf8))
        XCTAssertTrue(response.providers.isEmpty)
    }

    // MARK: - SessionRow

    func testSessionRowWithMissingOptionalFieldsSucceeds() throws {
        let json = """
        {
          "id": "abc-123",
          "title": "Refactor auth flow",
          "last_active": "2026-08-22T10:30:00Z"
        }
        """
        let row = try JSONDecoder().decode(SessionRow.self, from: Data(json.utf8))
        XCTAssertEqual(row.id, "abc-123")
        XCTAssertEqual(row.title, "Refactor auth flow")
        XCTAssertNotNil(row.lastActive)
        XCTAssertEqual(row.preview, "")
        XCTAssertEqual(row.messageCount, 0)
        XCTAssertNil(row.model)
        XCTAssertNil(row.profile)
    }

    func testSessionRowAcceptsEpochSecondsLastActive() throws {
        let json = #"{"id": "e1", "message_count": 7, "last_active": 1755858600}"#
        let row = try JSONDecoder().decode(SessionRow.self, from: Data(json.utf8))
        XCTAssertEqual(row.messageCount, 7)
        XCTAssertEqual(
            row.lastActive?.timeIntervalSince1970 ?? -1,
            1755858600,
            accuracy: 1
        )
    }

    func testSessionRowWithoutRequiredIDFailsCleanly() {
        let json = #"{"title": "No identity", "preview": "should not decode"}"#
        XCTAssertThrowsError(try JSONDecoder().decode(SessionRow.self, from: Data(json.utf8)))
    }

    func testAuthProviderWithoutNameFailsCleanly() {
        let json = #"{"display_name": "No machine name"}"#
        XCTAssertThrowsError(try JSONDecoder().decode(AuthProvider.self, from: Data(json.utf8)))
    }
}
