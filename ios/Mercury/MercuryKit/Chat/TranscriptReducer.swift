import Foundation
import MercuryCore

// MARK: - Transcript state machine (facade over the shared KMP core)
//
// The transcript reduction rules live in the shared core's TranscriptEngine
// (shared/mercury-core, the AGENTS.md cross-platform rule). This file
// keeps the exact pre-existing Swift value-type API — ChatView, the sheets,
// and the 57-test TranscriptReducerTests suite are unchanged — while every
// decision is delegated to the engine's immutable snapshots. The parity
// notes that used to live here now live on the engine.

/// Pure transcript state for one chat session.
struct TranscriptState: Sendable, Equatable {

    // MARK: Row model

    struct Row: Identifiable, Sendable, Equatable {
        /// Engine-assigned stable identity (monotonic per state lineage).
        let coreID: Int64
        var role: String
        var text: String
        var completed: Bool
        var toolName: String? = nil
        var reasoningText: String = ""
        var displayKind: String? = nil

        /// Stable UUID derived from the engine identity, for SwiftUI.
        var id: UUID {
            UUID(uuidString: String(
                format: "00000000-0000-4000-8000-%012llx", coreID
            )) ?? UUID()
        }

        /// Identity-stable equality: rows describing the same content compare
        /// equal even when their engine ids differ.
        static func == (lhs: Row, rhs: Row) -> Bool {
            lhs.role == rhs.role && lhs.text == rhs.text && lhs.completed == rhs.completed
                && lhs.toolName == rhs.toolName
                && lhs.reasoningText == rhs.reasoningText
                && lhs.displayKind == rhs.displayKind
        }

        init(coreID: Int64 = 0, role: String, text: String, completed: Bool,
             toolName: String? = nil, reasoningText: String = "", displayKind: String? = nil) {
            self.coreID = coreID
            self.role = role
            self.text = text
            self.completed = completed
            self.toolName = toolName
            self.reasoningText = reasoningText
            self.displayKind = displayKind
        }

        init(_ core: MercuryCore.TranscriptRow) {
            self.init(
                coreID: core.id,
                role: core.role,
                text: core.text,
                completed: core.completed,
                toolName: core.toolName,
                reasoningText: core.reasoningText, displayKind: core.displayKind
            )
        }

        var core: MercuryCore.TranscriptRow {
            MercuryCore.TranscriptRow(
                id: coreID, role: role, text: text, completed: completed,
                toolName: toolName, reasoningText: reasoningText, displayKind: displayKind
            )
        }
    }

    struct RestoredMessage: Sendable, Equatable {
        var role: String
        var content: String
        var toolName: String? = nil
        var reasoningText: String = ""
        var displayKind: String? = nil

        var core: MercuryCore.RestoredMessage {
            MercuryCore.RestoredMessage(
                role: role, content: content, toolName: toolName, reasoningText: reasoningText,
                displayKind: displayKind
            )
        }
    }

    // MARK: Tool activity

    enum ToolRowState: Sendable, Equatable {
        case running
        case completed
    }

    struct ToolRow: Sendable, Equatable {
        var toolID: String
        var name: String
        var context: String?
        var summary: String?
        var state: ToolRowState
    }

    static let maxToolRows = Int(MercuryCore.TranscriptEngine.shared.MAX_TOOL_ROWS)
    static let maxToolFieldLength = Int(MercuryCore.TranscriptEngine.shared.MAX_TOOL_FIELD_LENGTH)
    static let maxToolDetailLength = Int(MercuryCore.TranscriptEngine.shared.MAX_TOOL_DETAIL_LENGTH)

    static let interruptSentinelPrefix = MercuryCore.InterruptSentinel.shared.PREFIX

    static func isInterruptSentinel(_ text: String) -> Bool {
        MercuryCore.InterruptSentinel.shared.isInterruptSentinel(text: text)
    }

    // MARK: Pending interaction

    enum PendingRequest: Sendable, Equatable {
        case approval(ChatEvent)
        case clarify(ChatEvent)
    }

    // MARK: Core snapshot

    private var core: MercuryCore.TranscriptSnapshot
    /// The original Swift event backing `pendingRequest`, kept alongside the
    /// engine's decision so sheets receive the event unchanged.
    private var pendingSwiftRequest: PendingRequest?

    // MARK: Configuration / session identity

    var adoptsLiveTitles: Bool {
        get { core.adoptsLiveTitles }
        set { core = core.withAdoptsLiveTitles(value: newValue) }
    }

    var ownSessionIDs: Set<String> {
        get { Set(core.ownSessionIds) }
        set { core = core.withOwnSessionIds(ids: newValue) }
    }

    // MARK: Observable transcript state

    var rows: [Row] { core.rows.map(Row.init) }
    var lastError: String? { core.lastError }
    var pendingRequest: PendingRequest? { pendingSwiftRequest }
    var pendingRequestSnapshot: MercuryCore.PendingTranscriptRequest? { core.pendingRequest }

    func matchesPendingRequest(_ expected: MercuryCore.PendingTranscriptRequest?) -> Bool {
        MercuryCore.TranscriptEngine.shared.matchesPendingRequest(state: core, expected: expected)
    }

    mutating func resolvePendingRequest(_ expected: MercuryCore.PendingTranscriptRequest?) {
        core = MercuryCore.TranscriptEngine.shared.resolvePendingRequest(state: core, expected: expected)
        if core.pendingRequest == nil { pendingSwiftRequest = nil }
    }
    var adoptedTitle: String? { core.adoptedTitle }
    var latestStatusText: String? { core.latestStatusText }
    var statusUpdateCount: Int { Int(core.statusUpdateCount) }
    var generatingStatusText: String? { core.generatingStatusText }

    var tools: [ToolRow] {
        core.tools.map { tool in
            ToolRow(
                toolID: tool.toolId,
                name: tool.name,
                context: tool.context,
                summary: tool.summary,
                state: tool.state == MercuryCore.ToolRowState.running ? .running : .completed
            )
        }
    }

    init(isNewSession: Bool = false) {
        core = MercuryCore.TranscriptEngine.shared.initial(isNewSession: isNewSession)
    }

    static func == (lhs: TranscriptState, rhs: TranscriptState) -> Bool {
        lhs.core == rhs.core && lhs.pendingSwiftRequest == rhs.pendingSwiftRequest
    }

    // MARK: Event application

    mutating func apply(_ event: ChatEvent) {
        guard let coreEvent = event.core else { return }
        core = MercuryCore.TranscriptEngine.shared.apply(state: core, event: coreEvent)
        switch event {
        case .approvalRequest:
            pendingSwiftRequest = .approval(event)
        case .clarifyRequest:
            pendingSwiftRequest = .clarify(event)
        default:
            if core.pendingRequest == nil { pendingSwiftRequest = nil }
        }
    }

    // MARK: Derived queries

    var hasStreamingAssistant: Bool { core.hasStreamingAssistant }

    func isOwnSession(_ eventSessionID: String) -> Bool {
        core.isOwnSession(eventSessionId: eventSessionID)
    }

    // MARK: Direct transcript mutations (non-event paths)

    mutating func loadTranscript(_ messages: [(role: String, content: String)]) {
        loadTranscript(messages.map { RestoredMessage(role: $0.role, content: $0.content) })
    }

    mutating func loadTranscript(_ messages: [RestoredMessage]) {
        core = MercuryCore.TranscriptEngine.shared.loadTranscript(
            state: core, messages: messages.map(\.core)
        )
    }

    @discardableResult
    mutating func reconcileForegroundTranscript(
        _ messages: [RestoredMessage],
        turnWasActive: Bool
    ) -> Bool {
        let result = MercuryCore.TranscriptEngine.shared.reconcileForegroundTranscript(
            state: core, messages: messages.map(\.core), turnWasActive: turnWasActive
        )
        core = result.state
        return result.keptLocalSuffix
    }

    mutating func prependHistory(_ messages: [RestoredMessage]) {
        core = MercuryCore.TranscriptEngine.shared.prependHistory(
            state: core, messages: messages.map(\.core)
        )
    }

    mutating func ensureInflightAssistantRow(text: String, completed: Bool) {
        core = MercuryCore.TranscriptEngine.shared.ensureInflightAssistantRow(
            state: core, text: text, completed: completed
        )
    }

    mutating func finishStreamingAssistant() {
        core = MercuryCore.TranscriptEngine.shared.finishStreamingAssistant(state: core)
    }

    mutating func appendUserMessage(_ text: String) {
        core = MercuryCore.TranscriptEngine.shared.appendUserMessage(state: core, text: text)
    }
}

// MARK: - Renderable timeline units

enum TranscriptEntry: Identifiable, Equatable {
    case message(TranscriptState.Row)
    case toolRun([TranscriptState.Row])
    case workBurst(reasoning: [TranscriptState.Row], tools: [TranscriptState.Row])

    /// Case-qualified identity: a row's entry can morph between cases as a
    /// turn streams (reasoning-only workBurst → message once prose arrives).
    /// Sharing the bare row UUID across cases makes SwiftUI treat the morph
    /// as "same item" and keep the stale subtree — the streamed answer never
    /// replaces the collapsed activity line.
    var id: String {
        switch self {
        case .message(let row): return "m-\(row.id.uuidString)"
        case .toolRun(let rows): return "t-\(rows[0].id.uuidString)"
        case .workBurst(let reasoning, let tools):
            return "w-\((reasoning.first?.id ?? tools[0].id).uuidString)"
        }
    }
}

func coalesceTranscriptEntries(_ rows: [TranscriptState.Row], withinTurnActivity: Bool = false) -> [TranscriptEntry] {
    let entries = withinTurnActivity
        ? MercuryCore.ActivityTranscriptEntriesKt.activityTranscriptEntries(rows: rows.map(\.core))
        : MercuryCore.TranscriptEngineKt.coalesceTranscriptEntries(rows: rows.map(\.core))
    return entries.compactMap { entry -> TranscriptEntry? in
            switch entry {
            case let message as MercuryCore.TranscriptEntryMessage:
                return .message(TranscriptState.Row(message.row))
            case let toolRun as MercuryCore.TranscriptEntryToolRun:
                return .toolRun(toolRun.rows.map(TranscriptState.Row.init))
            case let burst as MercuryCore.TranscriptEntryWorkBurst:
                return .workBurst(
                    reasoning: burst.reasoning.map(TranscriptState.Row.init),
                    tools: burst.tools.map(TranscriptState.Row.init)
                )
            default:
                // A core variant this build does not know: skip it rather
                // than crash on a value that crossed the KMP boundary.
                return nil
            }
        }
}

/// Presentation-only explanation for a current turn with no final assistant
/// prose while a child is still actively observed. This never adds a transcript row.
func missingFinalResponseNotice(
    _ rows: [TranscriptState.Row],
    activeChildCount: Int,
    parentTurnSending: Bool
) -> String? {
    MercuryCore.TranscriptPresentationPolicy.shared.missingFinalResponseNotice(
        rows: rows.map(\.core),
        activeChildCount: Int32(activeChildCount),
        parentTurnSending: parentTurnSending
    )
}

// MARK: - Event conversion (Swift -> shared core)

private extension ChatEvent {
    var core: MercuryCore.ChatEvent? {
        switch self {
        case .messageStart(let sessionID, let text):
            return MercuryCore.ChatEventMessageStart(sessionId: sessionID, text: text)
        case .messageDelta(let sessionID, let text):
            return MercuryCore.ChatEventMessageDelta(sessionId: sessionID, text: text)
        case .messageComplete(let sessionID, let text, let status, let error, let reasoning,
                              let warning, let failureReason, let recoverable, let billing):
            return MercuryCore.ChatEventMessageComplete(
                sessionId: sessionID, text: text, status: status, error: error,
                reasoning: reasoning, warning: warning, failureReason: failureReason,
                recoverable: recoverable,
                billing: billing.map {
                    MercuryCore.BillingInfo(
                        provider: $0.provider, billingUrl: $0.billingURL,
                        isNous: $0.isNous, message: $0.message
                    )
                }
            )
        case .reasoningDelta(let sessionID, let text, let replace):
            return MercuryCore.ChatEventReasoningDelta(sessionId: sessionID, text: text, replace: replace)
        case .messageInterim(let sessionID, let text, let alreadyStreamed):
            return MercuryCore.ChatEventMessageInterim(
                sessionId: sessionID, text: text, alreadyStreamed: alreadyStreamed
            )
        case .toolGenerating(let sessionID, let name):
            return MercuryCore.ChatEventToolGenerating(sessionId: sessionID, name: name)
        case .sessionTitle(let sessionID, let title):
            return MercuryCore.ChatEventSessionTitle(sessionId: sessionID, title: title)
        case .error(let sessionID, let message):
            return MercuryCore.ChatEventError(sessionId: sessionID, message: message)
        case .toolStart(let sessionID, let toolID, let name, let context, _):
            return MercuryCore.ChatEventToolStart(
                sessionId: sessionID, toolId: toolID, name: name, context: context
            )
        case .toolComplete(let sessionID, let toolID, let name, let summary, _, _):
            return MercuryCore.ChatEventToolComplete(
                sessionId: sessionID, toolId: toolID, name: name, summary: summary
            )
        case .statusUpdate(let sessionID, let kind, let text):
            return MercuryCore.ChatEventStatusUpdate(sessionId: sessionID, kind: kind, text: text)
        case .clarifyRequest(let sessionID, let requestID, let question, let choices, let multiSelect, let questions):
            return MercuryCore.ChatEventClarifyRequest(
                sessionId: sessionID, requestId: requestID, question: question,
                choices: choices, multiSelect: multiSelect,
                questions: questions.map {
                    MercuryCore.ClarifyQuestion(qid: $0.qid, question: $0.question, choices: $0.choices, multiSelect: $0.multiSelect)
                }
            )
        case .clarifyExpire(let sessionID, let requestID):
            return MercuryCore.ChatEventClarifyExpire(sessionId: sessionID, requestId: requestID)
        case .approvalRequest(let sessionID, let requestID, let command, let description, let choices):
            return MercuryCore.ChatEventApprovalRequest(
                sessionId: sessionID, requestId: requestID, command: command,
                description: description, choices: choices
            )
        case .approvalExpire(let sessionID, let requestID):
            return MercuryCore.ChatEventApprovalExpire(sessionId: sessionID, requestId: requestID)
        case .backgroundTask, .sessionInfo, .unsupportedBlockingRequest, .unsupportedBlockingExpire:
            // Not modeled by the shared engine; no state change.
            return nil
        }
    }
}
