import SwiftUI

/// Which kind of Hermes backend the user wants to connect to. Drives the
/// Connect screen: self-hosted asks for an origin, Cloud signs in to the
/// Portal and lists the agents on the account.
enum ConnectionMode: String, CaseIterable, Identifiable {
    case selfHosted
    case hermesCloud
    case mercuryRelay

    var id: String { rawValue }

    var title: String {
        switch self {
        case .selfHosted: "Self-hosted"
        case .hermesCloud: "Hermes Cloud"
        case .mercuryRelay: "Relay"
        }
    }

    var subtitle: String {
        switch self {
        case .selfHosted:
            "Your own server — VPS, homelab, or LAN"
        case .hermesCloud:
            "Always-on agents from Nous Portal"
        case .mercuryRelay:
            "End-to-end encrypted pairing with your Mercury host"
        }
    }

    var icon: String {
        switch self {
        case .selfHosted: "server.rack"
        case .hermesCloud: "cloud.fill"
        case .mercuryRelay: "qrcode.viewfinder"
        }
    }
}
