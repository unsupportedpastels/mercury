import Foundation

// MARK: - Explicit session model catalog

/// Hard bounds mirror Android's authoritative chat gateway contract.
let maxModelProviders = 64
let maxModelsPerProvider = 512
let maxModelProviderChars = 128
let maxModelIDChars = 512

struct ModelSelection: Sendable, Equatable {
    let provider: String
    let model: String
}

/// A nil value means that the server did not explicitly advertise the
/// capability. Unknown capability fields are intentionally ignored.
struct ModelCapabilities: Sendable, Equatable {
    let fast: Bool?
    let reasoning: Bool?

    init(fast: Bool? = nil, reasoning: Bool? = nil) {
        self.fast = fast
        self.reasoning = reasoning
    }

    var hasExplicitCapability: Bool { fast != nil || reasoning != nil }
}

struct ModelProviderOption: Sendable, Equatable {
    let slug: String
    let name: String
    let models: [String]
    let capabilities: [String: ModelCapabilities]
}

struct ModelOptions: Sendable, Equatable {
    let current: ModelSelection?
    let providers: [ModelProviderOption]

    func capabilities(for selection: ModelSelection?) -> ModelCapabilities? {
        guard let selection else { return nil }
        return providers.first { $0.slug == selection.provider }?.capabilities[selection.model]
    }
}

struct ModelSwitchResult: Sendable, Equatable {
    let accepted: Bool
    let deferred: Bool
    let confirmationRequired: Bool
    let confirmationMessage: String?
}

enum ReasoningEffort: String, CaseIterable, Sendable {
    case none
    case minimal
    case low
    case medium
    case high
    case xhigh
    case max
    case ultra

    static func canonical(_ value: String) -> String? {
        let canonical = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return Self(rawValue: canonical)?.rawValue
    }
}
