import Foundation

// MARK: - Chat event hierarchy
//
// Direct port of Android's `HermesChatEvent` sealed hierarchy
// (gateway/HermesChatGateway.kt). Field names camelCase; wire decoding lives
// in ChatConnection.handleEvent — this file is the typed surface only.

enum UnsupportedBlockingKind: Sendable, Equatable {
    case secret
    case sudo
    case terminalRead
    case previewRead
    case windowRead

    /// The request event type string this kind answers, per Android's mapping.
    var requestType: String {
        switch self {
        case .secret: return "secret.request"
        case .sudo: return "sudo.request"
        case .terminalRead: return "terminal.read.request"
        case .previewRead: return "preview.read.request"
        case .windowRead: return "window.read.request"
        }
    }

    /// The expire event type string for this kind.
    var expireType: String {
        switch self {
        case .secret: return "secret.expire"
        case .sudo: return "sudo.expire"
        case .terminalRead: return "terminal.read.expire"
        case .previewRead: return "preview.read.expire"
        case .windowRead: return "window.read.expire"
        }
    }
}

/// Structured billing-wall descriptor from `message.complete`.
struct BillingInfo: Sendable, Equatable {
    var provider: String?
    var billingURL: String?
    var isNous: Bool
    var message: String?
}

enum ChatEvent: Sendable, Equatable {
    case messageStart(sessionID: String, text: String?)
    case messageDelta(sessionID: String, text: String)
    /// `text` is nil when the server omitted it; consumers keep their streamed
    /// buffer in that case rather than replacing it with empty content.
    case messageComplete(
        sessionID: String,
        text: String?,
        status: String?,
        error: String?,
        reasoning: String?,
        warning: String?,
        failureReason: String?,
        recoverable: Bool,
        billing: BillingInfo?
    )
    /// Reasoning text; `replace` is true for authoritative
    /// `reasoning.available` snapshots.
    case reasoningDelta(sessionID: String, text: String, replace: Bool)
    /// Interim assistant commentary sealed as its own segment before tool calls.
    case messageInterim(sessionID: String, text: String, alreadyStreamed: Bool)
    /// The model is generating arguments for a tool.
    case toolGenerating(sessionID: String, name: String)
    /// Live session title rename pushed by the server.
    case sessionTitle(sessionID: String, title: String)
    /// Tolerant runtime metadata patch (`session.info`).
    case sessionInfo(
        sessionID: String,
        storedSessionID: String?,
        model: String?,
        provider: String?,
        reasoningEffort: String?,
        fastMode: Bool?,
        title: String?,
        running: Bool?
    )
    case error(sessionID: String, message: String)
    case toolStart(sessionID: String, toolID: String, name: String, context: String?)
    case toolComplete(sessionID: String, toolID: String, name: String, summary: String?)
    case statusUpdate(sessionID: String, kind: String, text: String)
    case clarifyRequest(sessionID: String, requestID: String, question: String, choices: [String], multiSelect: Bool)
    case clarifyExpire(sessionID: String, requestID: String)
    case approvalRequest(sessionID: String, requestID: String?, command: String?, description: String?, choices: [String])
    case approvalExpire(sessionID: String, requestID: String)
    case unsupportedBlockingRequest(sessionID: String, kind: UnsupportedBlockingKind, requestID: String, prompt: String?)
    case unsupportedBlockingExpire(sessionID: String, kind: UnsupportedBlockingKind, requestID: String)

    var sessionID: String {
        switch self {
        case .messageStart(let s, _), .messageDelta(let s, _),
             .messageComplete(let s, _, _, _, _, _, _, _, _),
             .reasoningDelta(let s, _, _), .messageInterim(let s, _, _),
             .toolGenerating(let s, _), .sessionTitle(let s, _),
             .sessionInfo(let s, _, _, _, _, _, _, _), .error(let s, _),
             .toolStart(let s, _, _, _), .toolComplete(let s, _, _, _),
             .statusUpdate(let s, _, _), .clarifyRequest(let s, _, _, _, _),
             .clarifyExpire(let s, _), .approvalRequest(let s, _, _, _, _),
             .approvalExpire(let s, _),
             .unsupportedBlockingRequest(let s, _, _, _),
             .unsupportedBlockingExpire(let s, _, _):
            return s
        }
    }
}

// MARK: - RPC result models

/// Result of `session.resume` / `session.create`, mirroring Android's
/// `ResumedChatSession`. Messages are raw JSON objects (tolerant pass-through;
/// rendering decodes them defensively).
struct ResumedChatSession: Sendable {
    var runtimeSessionID: String
    var durableSessionID: String?
    var resumed: Bool
    var messages: [[String: Any]]
    var running: Bool
    var inflight: InflightPrompt?
    var model: String?
    var provider: String?
    var reasoningEffort: String?
    var fastMode: Bool?

    /// Type-erased equality helper for tests (JSON objects compare via their
    /// serialized form).
    static func ~=(lhs: ResumedChatSession, rhs: ResumedChatSession) -> Bool {
        lhs.runtimeSessionID == rhs.runtimeSessionID &&
            lhs.durableSessionID == rhs.durableSessionID &&
            lhs.resumed == rhs.resumed &&
            lhs.running == rhs.running &&
            lhs.inflight == rhs.inflight &&
            lhs.model == rhs.model &&
            lhs.provider == rhs.provider &&
            lhs.reasoningEffort == rhs.reasoningEffort &&
            lhs.fastMode == rhs.fastMode &&
            (try? JSONSerialization.data(withJSONObject: lhs.messages)) ==
            (try? JSONSerialization.data(withJSONObject: rhs.messages))
    }
}

extension ResumedChatSession: Equatable {
    static func == (lhs: ResumedChatSession, rhs: ResumedChatSession) -> Bool { lhs ~= rhs }
}

/// An assistant prompt that was still executing when the connection opened.
struct InflightPrompt: Sendable, Equatable {
    var user: String?
    var assistant: String?
    var streaming: Bool
}

struct PromptSubmission: Sendable, Equatable {
    var status: String
}

/// Response to an interaction RPC (approval/clarify/interrupt), with the next
/// queued approval if one remains — mirrors Android's HermesChatResponse.
struct ChatResponse: Sendable, Equatable {
    enum Status: String, Sendable, Equatable {
        case ok
        case expired
        case interrupted
        case resolved
        case unknown

        static func fromWire(_ value: String?) -> Status {
            switch value?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "ok": return .ok
            case "expired": return .expired
            case "interrupted": return .interrupted
            case "resolved": return .resolved
            default: return .unknown
            }
        }
    }

    var status: Status
    var nextApproval: ChatEvent?
}
