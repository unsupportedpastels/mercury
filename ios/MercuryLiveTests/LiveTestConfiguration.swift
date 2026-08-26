import Foundation
import XCTest

enum LiveTestConfiguration {
    static func selfHostedOrigin() throws -> String {
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
            throw XCTSkip("Set MERCURY_LIVE_ORIGIN to an HTTPS Hermes origin to run live tests")
        }
        return raw
    }
}