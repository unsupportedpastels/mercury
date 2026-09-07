import Foundation
import MercuryCore

/// Typed namespace for a last-successful startup identity. The persisted form
/// contains only this kind plus a local UUID; catalogs and credential stores are
/// deliberately not used as a preference store.
enum StartupConnectionKind: String, Codable, CaseIterable, Sendable {
    case direct
    case relay
}

struct StartupConnectionIdentity: Codable, Equatable, Hashable, Sendable {
    let kind: StartupConnectionKind
    let id: UUID

    init(kind: StartupConnectionKind, id: UUID) {
        self.kind = kind
        self.id = id
    }
}

struct StartupConnectionCandidate: Equatable, Sendable {
    let identity: StartupConnectionIdentity
    let isUsable: Bool
}

enum StartupConnectionDecisionAction: Equatable, Sendable {
    case onboarding
    case autoConnect
    case chooseTarget
}

struct StartupConnectionDecision: Equatable, Sendable {
    let action: StartupConnectionDecisionAction
    let selected: StartupConnectionIdentity?
    let savedChoiceUnavailable: Bool
}

/// Adapter over the shared pure policy. Only typed IDs and the usable bit cross
/// the boundary; origins, cookies, tokens, and relay keys never do.
enum StartupConnectionDecisionPolicy {
    static func decide(
        candidates: [StartupConnectionCandidate],
        lastSuccessful: StartupConnectionIdentity?
    ) -> StartupConnectionDecision {
        let coreTargets = candidates.map {
            MercuryCore.StartupConnectionTarget(
                kind: $0.identity.kind.coreValue,
                id: $0.identity.id.uuidString,
                usable: $0.isUsable
            )
        }
        let coreChoice = lastSuccessful.map {
            MercuryCore.StartupConnectionChoice(
                kind: $0.kind.coreValue,
                id: $0.id.uuidString
            )
        }
        let result = MercuryCore.StartupConnectionPolicy.shared.decide(
            targets: coreTargets,
            lastSuccessful: coreChoice
        )
        let selected = result.selected.flatMap(StartupConnectionIdentity.init(core:))
        switch result.action {
        case .onboarding:
            return StartupConnectionDecision(
                action: .onboarding,
                selected: selected,
                savedChoiceUnavailable: result.savedChoiceUnavailable
            )
        case .autoConnect:
            return StartupConnectionDecision(
                action: .autoConnect,
                selected: selected,
                savedChoiceUnavailable: result.savedChoiceUnavailable
            )
        case .showPicker:
            return StartupConnectionDecision(
                action: .chooseTarget,
                selected: selected,
                savedChoiceUnavailable: result.savedChoiceUnavailable
            )
        default:
            // Keep the native client fail-closed if the generated framework ever
            // adds an action before this adapter is updated.
            return StartupConnectionDecision(
                action: .chooseTarget,
                selected: nil,
                savedChoiceUnavailable: true
            )
        }
    }
}

private extension StartupConnectionKind {
    var coreValue: MercuryCore.StartupConnectionKind {
        switch self {
        case .direct: return .direct
        case .relay: return .relay
        }
    }
}

private extension StartupConnectionIdentity {
    init?(core choice: MercuryCore.StartupConnectionChoice) {
        guard let id = UUID(uuidString: choice.id) else { return nil }
        switch choice.kind {
        case .direct: self.init(kind: .direct, id: id)
        case .relay: self.init(kind: .relay, id: id)
        default: return nil
        }
    }
}

/// A row projection for the native picker. It intentionally carries no relay
/// key material; AppModel resolves the identity back to its catalog/target only
/// after the user explicitly selects it.
struct StartupConnectionTargetRow: Identifiable, Equatable, Sendable {
    let identity: StartupConnectionIdentity
    let title: String
    let detail: String
    let isUsable: Bool
    let isPendingRelay: Bool

    var id: StartupConnectionIdentity { identity }
}

enum StartupConnectionState: Equatable, Sendable {
    case loading
    case onboarding
    case chooseTarget(savedChoiceUnavailable: Bool)
    case connecting(StartupConnectionIdentity)
    case failed(StartupConnectionIdentity?, String)
    case connected(StartupConnectionIdentity)
}

// MARK: - Last-successful preference storage

protocol StartupConnectionChoicePersisting: Sendable {
    func readStartupChoiceData() throws -> Data?
    func writeStartupChoiceData(_ data: Data) throws
    func clearStartupChoiceData()
}

/// UserDefaults is sufficient for this non-secret preference. Only a typed
/// local UUID and the direct/relay discriminator are encoded here; no origin,
/// credential, cookie, relay token, or relay key can enter this store's API.
final class UserDefaultsStartupConnectionChoicePersistence: StartupConnectionChoicePersisting, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    init(
        defaults: UserDefaults = .standard,
        key: String = "mercury.startup.last-successful-choice.v1"
    ) {
        self.defaults = defaults
        self.key = key
    }

    func readStartupChoiceData() throws -> Data? { defaults.data(forKey: key) }

    func writeStartupChoiceData(_ data: Data) throws { defaults.set(data, forKey: key) }

    func clearStartupChoiceData() { defaults.removeObject(forKey: key) }
}

actor StartupConnectionChoiceStore {
    private static let version = 1
    private static let maxPersistedBytes = 4 * 1024

    private let persistence: StartupConnectionChoicePersisting

    init(
        persistence: StartupConnectionChoicePersisting = UserDefaultsStartupConnectionChoicePersistence()
    ) {
        self.persistence = persistence
    }

    func load() throws -> StartupConnectionIdentity? {
        guard let data = try persistence.readStartupChoiceData(),
              data.count <= Self.maxPersistedBytes,
              let payload = try? JSONDecoder().decode(PersistedStartupConnectionChoice.self, from: data),
              payload.version == Self.version,
              let kind = StartupConnectionKind(rawValue: payload.kind),
              let id = UUID(uuidString: payload.id)
        else { return nil }
        return StartupConnectionIdentity(kind: kind, id: id)
    }

    func save(_ identity: StartupConnectionIdentity) throws {
        let data = try JSONEncoder().encode(
            PersistedStartupConnectionChoice(
                version: Self.version,
                kind: identity.kind.rawValue,
                id: identity.id.uuidString
            )
        )
        guard data.count <= Self.maxPersistedBytes else { throw StartupConnectionStoreError.oversized }
        try persistence.writeStartupChoiceData(data)
    }

    func clear() throws { persistence.clearStartupChoiceData() }
}

enum StartupConnectionStoreError: Error, Equatable {
    case oversized
}

private struct PersistedStartupConnectionChoice: Codable {
    let version: Int
    let kind: String
    let id: String
}
