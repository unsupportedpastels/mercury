import Foundation
import XCTest

enum LiveUITestConfiguration {
    static func origin() throws -> String {
        guard let raw = ProcessInfo.processInfo.environment["MERCURY_LIVE_ORIGIN"]?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty,
              let components = URLComponents(string: raw),
              components.user == nil,
              components.password == nil,
              components.host != nil,
              components.path.isEmpty,
              components.query == nil,
              components.fragment == nil,
              components.scheme == "https"
        else {
            throw XCTSkip("Set MERCURY_LIVE_ORIGIN to an HTTPS Hermes origin to run live UI tests")
        }
        return raw
    }

    static func portalEmail() throws -> String {
        guard let value = ProcessInfo.processInfo.environment["MERCURY_PORTAL_TEST_EMAIL"]?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else {
            throw XCTSkip("Set MERCURY_PORTAL_TEST_EMAIL to run interactive Portal UI tests")
        }
        return value
    }
}