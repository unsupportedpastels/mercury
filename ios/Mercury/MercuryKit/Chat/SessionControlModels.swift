import Foundation

// MARK: - Runtime session control results

let maxSessionResultRows = 128
let maxContextCategories = 64
let maxSessionFieldChars = 512

struct SessionSteerResult: Sendable, Equatable {
    enum Status: String, Sendable, Equatable {
        case queued
        case rejected
    }

    let status: Status
    let text: String?
}

struct SessionUsage: Sendable, Equatable {
    let inputTokens: Int64?
    let outputTokens: Int64?
    let totalTokens: Int64?
    let contextUsedTokens: Int64?
    let contextMaxTokens: Int64?
    let contextPercent: Double?
    let calls: Int64?
    let creditsLines: [String]
    let rawInfo: String?
}

struct ContextBreakdownCategory: Sendable, Equatable {
    let name: String
    let tokens: Int64?
    let percent: Double?
}

struct SessionContextBreakdown: Sendable, Equatable {
    let categories: [ContextBreakdownCategory]
    let usedTokens: Int64?
    let maxTokens: Int64?
    let percent: Double?
}

/// JSON display rows remain dictionaries because the transcript reducer already
/// owns their role/content schema. The outer RPC result is nevertheless typed.
struct SessionCompressResult: @unchecked Sendable {
    let status: String?
    let aborted: Bool
    let messages: [[String: Any]]
    let usage: SessionUsage?
}

struct SessionUndoResult: Sendable, Equatable {
    let removed: Int
}

struct SessionBranchResult: @unchecked Sendable {
    let runtimeSessionID: String?
    let durableSessionID: String
    let title: String?
    let messages: [[String: Any]]
}

struct SlashCompletionResult: Sendable, Equatable {
    let items: [SlashCompletionItem]
    let replaceFrom: Int
}
