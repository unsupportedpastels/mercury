import SwiftUI

/// Which kind of Hermes backend the user wants to connect to. Drives the
/// Connect screen: self-hosted asks for an origin, Cloud signs in to the
/// Portal and lists the agents on the account.
enum ConnectionMode: String, CaseIterable, Identifiable {
    case selfHosted
    case hermesCloud

    var id: String { rawValue }

    var title: String {
        switch self {
        case .selfHosted: "Self-hosted"
        case .hermesCloud: "Hermes Cloud"
        }
    }

    var subtitle: String {
        switch self {
        case .selfHosted:
            "Your own server — VPS, homelab, or LAN"
        case .hermesCloud:
            "Always-on agents from Nous Portal"
        }
    }

    var icon: String {
        switch self {
        case .selfHosted: "server.rack"
        case .hermesCloud: "cloud.fill"
        }
    }
}
