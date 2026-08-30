import Foundation

// MARK: - Transcript state machine
//
// Pure value-type model of the conversation transcript, extracted verbatim
// from ChatView's event handling so it can be unit-tested hermetically
// (no SwiftUI, no connection, no clock). ChatView owns the UI-only concerns
// (scroll/follow-bottom intent, reconnect policy, sheet presentation) and
// forwards every ChatEvent into `apply(_:)`.
//
// Deliberate parity notes — do not "simplify" these:
// - Only `.sessionTitle` is filtered by session identity. Streaming,
//   completion, and error events mutate state regardless of session ID,
//   exactly as the pre-extraction ChatView did.
// - No text bounding happens here: bounds are enforced upstream in
//   ChatConnection's frame decoding (boundedTextField / boundedRPCInput).
// - `messageComplete` with nil text keeps the streamed buffer; only a
//   non-nil final text replaces it.
// - Any approval/clarify expire clears whichever request is pending; expires
//   are not matched by request ID or kind (pre-extraction behavior).
// - Reasoning deltas land on the last incomplete assistant row (or open a
//   fresh reasoning-only row); reasoning text survives completion so the UI
//   can show it collapsed.
// - Interim commentary seals the current streaming segment as completed; a
//   later delta opens a fresh row. `alreadyStreamed` carries no extra
//   reducer behavior (Android parity).
// - Tool activity rows are bounded: at most 50 rows are retained, ids and
//   names at 256 chars, context/summary at 4096 chars (String.prefix).
// - messageComplete and error additionally finalize every running tool row
//   and clear the transient "generating arguments" status.
// - Event kinds this slice does not model (sessionInfo/unsupported blocking)
//   fall through `default: break`.

/// Pure transcript state for one chat session.
struct TranscriptState: Sendable, Equatable {

    // MARK: Row model (moved verbatim from ChatView)

    struct Row: Identifiable, Sendable, Equatable {
        let id = UUID()
        var role: String
        var text: String
        var completed: Bool
        /// Persisted tool identity used by the collapsed historical activity
        /// renderer. Nil for ordinary conversation rows and live text events.
        var toolName: String? = nil
        /// Accumulated chain-of-thought for this assistant segment. Empty for
        /// user rows and assistant rows without reasoning. Retained after the
        /// row completes so the UI can render it as a collapsed disclosure.
        var reasoningText: String = ""

        /// Identity-stable equality: two rows describing the same message
        /// content compare equal even though their UUIDs differ. This keeps
        /// whole-state assertions practical in tests without letting row
        /// identity (a view concern) leak into value semantics.
        static func == (lhs: Row, rhs: Row) -> Bool {
            lhs.role == rhs.role && lhs.text == rhs.text && lhs.completed == rhs.completed
                && lhs.toolName == rhs.toolName
                && lhs.reasoningText == rhs.reasoningText
        }
    }

    struct RestoredMessage: Sendable, Equatable {
        var role: String
        var content: String
        var toolName: String? = nil
        var reasoningText: String = ""
    }

    // MARK: Tool activity (bounded, Android parity)

    enum ToolRowState: Sendable, Equatable {
        case running
        case completed
    }

    /// One tool invocation shown in the transcript's activity feed.
    struct ToolRow: Sendable, Equatable {
        var toolID: String
        var name: String
        var context: String?
        var summary: String?
        var state: ToolRowState
    }

    /// Maximum retained tool rows; appends beyond this keep only the last 50.
    static let maxToolRows = 50

    /// Stable prefix of the server's local interrupt status text
    /// (`agent/conversation_loop.py` `INTERRUPT_WAITING_FOR_MODEL_PREFIX`).
    /// Emitted as an interrupted turn's final text when a stop/steer lands
    /// while the provider request is in flight. It is cancellation metadata,
    /// not assistant prose; official surfaces suppress it and so do we —
    /// both live (`messageComplete`) and from persisted history written by
    /// servers that predate the upstream transcript fix.
    static let interruptSentinelPrefix = "Operation interrupted: waiting for model response ("

    static func isInterruptSentinel(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .hasPrefix(interruptSentinelPrefix)
    }

    /// Bound applied to tool ids and names.
    static let maxToolFieldLength = 256
    /// Bound applied to tool context and summary strings.
    static let maxToolDetailLength = 4096

    // MARK: Pending interaction

    /// The currently presented blocking request, mirroring
    /// ApprovalSheet.Request's shape minus its SwiftUI ownership. The raw
    /// event is retained so the presenting view can hand it to the sheet
    /// unchanged.
    enum PendingRequest: Sendable, Equatable {
        case approval(ChatEvent)
        case clarify(ChatEvent)
    }

    // MARK: Configuration / session identity

    /// Live title renames are adopted ONLY in the new-chat flow (the "+"
    /// flow); existing sessions keep their navigation-supplied title until
    /// the list refreshes. Seeded from ChatView's `isNewSession`.
    var adoptsLiveTitles: Bool

    /// Session IDs considered "ours" for title-adoption filtering. The view
    /// seeds this exactly as its pre-extraction `isOurSession(_:)` compared:
    /// the durable session id the screen was opened with (only when
    /// non-empty), plus the runtime and durable ids once they are learned.
    /// Membership in this set is equivalent to the original three-way
    /// comparison because an empty navigation session id is never inserted.
    var ownSessionIDs: Set<String> = []

    // MARK: Observable transcript state

    private(set) var rows: [Row] = []

    /// Most recent `.error` payload. The view presents this as a composer
    /// banner and clears its sending flag; recording here keeps the capture
    /// testable without UIKit/SwiftUI.
    private(set) var lastError: String?

    /// Blocking request awaiting user interaction, if any.
    private(set) var pendingRequest: PendingRequest?

    /// Title adopted from a matching `.sessionTitle` event, if any.
    private(set) var adoptedTitle: String?

    /// Text of the most recent `.statusUpdate`. Pre-extraction, status text
    /// was explicitly discarded (`_ = statusText`) and only cleared a
    /// connection note; it is recorded here for observability while the
    /// note-clearing itself stays a view concern.
    private(set) var latestStatusText: String?

    /// Monotonic count of `.statusUpdate` events seen, so a view can detect
    /// that one arrived even when the text repeats the previous value.
    private(set) var statusUpdateCount = 0

    /// Bounded feed of tool invocations (most recent last). Appends beyond
    /// `maxToolRows` keep only the trailing window.
    private(set) var tools: [ToolRow] = []

    /// Transient "Generating <tool> arguments…" status set by
    /// `.toolGenerating` and cleared by toolStart/toolComplete (and by
    /// messageComplete/error finalization).
    private(set) var generatingStatusText: String?

    init(isNewSession: Bool = false) {
        self.adoptsLiveTitles = isNewSession
    }

    // MARK: Event application

    mutating func apply(_ event: ChatEvent) {
        switch event {
        case .messageStart(_, let text):
            rows.append(Row(role: "assistant", text: text ?? "", completed: false))

        case .messageDelta(_, let delta):
            if let last = rows.lastIndex(where: { !$0.completed && $0.role == "assistant" }) {
                rows[last].text += delta
            } else {
                // Deltas without a start frame still need a home.
                rows.append(Row(role: "assistant", text: delta, completed: false))
            }

        case .messageComplete(_, let text, _, _, _, _, _, _, _):
            // The server's "Operation interrupted: waiting for model response
            // (Ns elapsed)." final text is cancellation metadata, not
            // assistant prose — the TUI gateway, messaging gateway, and ACP
            // adapter all suppress it (hermes-agent #7921). Treat it as nil
            // final text so an interrupted turn keeps its streamed buffer,
            // and drop the row entirely when nothing was streamed.
            let sentinelSuppressed = text.map(Self.isInterruptSentinel) ?? false
            let finalText = sentinelSuppressed ? nil : text
            if let last = rows.lastIndex(where: { !$0.completed && $0.role == "assistant" }) {
                // Authoritative final text replaces the streamed buffer when present.
                if let finalText { rows[last].text = finalText }
                rows[last].completed = true
                if sentinelSuppressed,
                   isBlank(rows[last].text),
                   isBlank(rows[last].reasoningText) {
                    // The interrupted turn produced nothing visible; a blank
                    // completed bubble is worse than no bubble.
                    rows.remove(at: last)
                }
            } else if let finalText {
                rows.append(Row(role: "assistant", text: finalText, completed: true))
            }
            // A finished turn cannot leave tools running (Android parity).
            finishRunningTools()

        case .reasoningDelta(_, let text, let replace):
            guard !isBlank(text) else { break }
            if let last = rows.lastIndex(where: { !$0.completed && $0.role == "assistant" }) {
                rows[last].reasoningText = replace ? text : rows[last].reasoningText + text
            } else {
                // Reasoning before any streamed content opens its own row;
                // later message deltas land in its empty-text buffer.
                rows.append(Row(role: "assistant", text: "", completed: false, reasoningText: text))
            }

        case .messageInterim(_, let text, _):
            // `alreadyStreamed` carries no extra reducer behavior (Android
            // parity): the interim text seals the segment either way.
            guard !isBlank(text) else { break }
            if let last = rows.lastIndex(where: { !$0.completed && $0.role == "assistant" }) {
                rows[last].text = text
                rows[last].completed = true
            } else {
                rows.append(Row(role: "assistant", text: text, completed: true))
            }

        case .toolGenerating(_, let name):
            let boundedName = String(name.prefix(Self.maxToolFieldLength))
            generatingStatusText = String(
                ("Generating " + boundedName + " arguments…").prefix(Self.maxToolDetailLength)
            )

        case .error(_, let message):
            lastError = message
            finishRunningTools()

        case .toolStart(_, let toolID, let name, let context):
            let boundedID = String(toolID.prefix(Self.maxToolFieldLength))
            let boundedName = String(name.prefix(Self.maxToolFieldLength))
            let boundedContext = context.map { String($0.prefix(Self.maxToolDetailLength)) }
            if let index = tools.firstIndex(where: { $0.toolID == boundedID }) {
                guard tools[index].state != .completed else {
                    // Completed tool invocations are final; a late start for
                    // an already-finished id is ignored entirely.
                    break
                }
                tools[index].name = boundedName
                tools[index].context = boundedContext
                tools[index].state = .running
            } else {
                appendToolRow(ToolRow(
                    toolID: boundedID,
                    name: boundedName,
                    context: boundedContext,
                    summary: nil,
                    state: .running
                ))
            }
            generatingStatusText = nil

        case .toolComplete(_, let toolID, let name, let summary):
            let boundedID = String(toolID.prefix(Self.maxToolFieldLength))
            let boundedName = String(name.prefix(Self.maxToolFieldLength))
            let boundedSummary = summary.map { String($0.prefix(Self.maxToolDetailLength)) }
            generatingStatusText = nil
            if let index = tools.firstIndex(where: { $0.toolID == boundedID }) {
                // Preserve the start frame's context through completion.
                tools[index].name = boundedName
                tools[index].summary = boundedSummary
                tools[index].state = .completed
            } else {
                appendToolRow(ToolRow(
                    toolID: boundedID,
                    name: boundedName,
                    context: nil,
                    summary: boundedSummary,
                    state: .completed
                ))
            }

        case .approvalRequest:
            pendingRequest = .approval(event)

        case .clarifyRequest:
            pendingRequest = .clarify(event)

        case .approvalExpire, .clarifyExpire:
            if pendingRequest != nil { pendingRequest = nil }

        case .sessionTitle(let eventSessionID, let newTitle):
            // New-chat flow only: adopt live title renames for our own
            // session. Existing sessions keep their previous behavior
            // (title comes from navigation until the list refreshes).
            if adoptsLiveTitles, isOwnSession(eventSessionID) {
                adoptedTitle = newTitle
            }

        case .statusUpdate(_, _, let statusText):
            latestStatusText = statusText
            statusUpdateCount += 1

        default:
            break
        }
    }

    // MARK: Derived queries

    /// A turn is executing when any assistant row is still streaming.
    var hasStreamingAssistant: Bool {
        rows.contains { !$0.completed && $0.role == "assistant" }
    }

    // MARK: Tool-row helpers

    /// Appends a tool row, keeping only the trailing `maxToolRows` window.
    private mutating func appendToolRow(_ row: ToolRow) {
        tools.append(row)
        if tools.count > Self.maxToolRows {
            tools.removeFirst(tools.count - Self.maxToolRows)
        }
    }

    /// Finalizes every running tool row and clears the generating status.
    /// Invoked by messageComplete and error: a finished/failed turn cannot
    /// leave tools spinning (Android parity).
    private mutating func finishRunningTools() {
        for index in tools.indices where tools[index].state == .running {
            tools[index].state = .completed
        }
        generatingStatusText = nil
    }

    /// Whitespace-only strings carry no content (Foundation-only helper).
    private func isBlank(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: Session identity

    /// Port of ChatView.isOurSession(_:): does this event's session id belong
    /// to the chat we're showing? For a new chat we know the runtime id (and,
    /// once adopted, the durable stored id); for an existing chat, the
    /// durable id this view opened with.
    func isOwnSession(_ eventSessionID: String) -> Bool {
        ownSessionIDs.contains(eventSessionID)
    }

    // MARK: Direct transcript mutations (non-event paths)

    /// Restores REST-loaded history, replacing any live rows. Every restored
    /// message is complete by construction. Order is caller-supplied display
    /// order (ChatView reverses the server's newest-first payload).
    mutating func loadTranscript(_ messages: [(role: String, content: String)]) {
        loadTranscript(messages.map { RestoredMessage(role: $0.role, content: $0.content) })
    }

    mutating func loadTranscript(_ messages: [RestoredMessage]) {
        rows = messages.compactMap { message in
            let role = message.role.lowercased()
            guard ["user", "assistant", "system", "tool"].contains(role) else { return nil }
            // Persisted interrupt sentinels are cancellation metadata written
            // by servers that predate the upstream transcript fix; never
            // render them as assistant prose.
            if role == "assistant", Self.isInterruptSentinel(message.content) { return nil }
            let hasText = !message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let hasReasoning = !message.reasoningText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let hasToolIdentity = role == "tool" && !(message.toolName ?? "").isEmpty
            guard hasText || hasReasoning || hasToolIdentity else { return nil }
            return Row(
                role: role,
                text: message.content,
                completed: true,
                toolName: message.toolName,
                reasoningText: message.reasoningText
            )
        }
    }

    /// Replaces history after foregrounding while preserving a local active-turn
    /// suffix until REST proves that the matching turn completed.
    @discardableResult
    mutating func reconcileForegroundTranscript(
        _ messages: [RestoredMessage],
        turnWasActive: Bool
    ) -> Bool {
        let priorRows = rows
        let latestUserIndex = turnWasActive
            ? priorRows.lastIndex(where: { $0.role == "user" })
            : nil
        let localTurnSuffix: [Row]
        if let latestUserIndex {
            localTurnSuffix = Array(priorRows[latestUserIndex...])
        } else if turnWasActive,
                  let streamingIndex = priorRows.lastIndex(where: {
                      $0.role == "assistant" && !$0.completed
                  }) {
            localTurnSuffix = Array(priorRows[streamingIndex...])
        } else {
            localTurnSuffix = []
        }

        loadTranscript(messages)
        guard turnWasActive, !localTurnSuffix.isEmpty else {
            if turnWasActive { finishRunningTools() }
            return false
        }

        if let localUser = localTurnSuffix.first(where: { $0.role == "user" }),
           let restoredUserIndex = rows.lastIndex(where: {
               $0.role == "user" && $0.text == localUser.text
           }) {
            let replyWasPersisted = rows.indices.contains(restoredUserIndex + 1)
                && rows[(restoredUserIndex + 1)...].contains(where: { $0.role == "assistant" })
            if replyWasPersisted {
                finishRunningTools()
                return false
            }
            rows.append(contentsOf: localTurnSuffix.dropFirst())
        } else {
            rows.append(contentsOf: localTurnSuffix)
        }
        return true
    }

    /// Prepends an older history window for "Load earlier". The server's
    /// offset window is disjoint from the newest page by construction, so no
    /// deduplication is performed.
    mutating func prependHistory(_ messages: [RestoredMessage]) {
        let older = messages.compactMap { message -> Row? in
            let role = message.role.lowercased()
            guard ["user", "assistant", "system", "tool"].contains(role) else { return nil }
            // Keep pagination consistent with initial/history restore: an
            // interrupt sentinel is cancellation metadata, never assistant
            // prose. Without this guard, loading older messages can reinsert
            // the interruption bubble after the main transcript filtered it.
            if role == "assistant", Self.isInterruptSentinel(message.content) { return nil }
            let hasText = !message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let hasReasoning = !message.reasoningText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let hasToolIdentity = role == "tool" && !(message.toolName ?? "").isEmpty
            guard hasText || hasReasoning || hasToolIdentity else { return nil }
            return Row(
                role: role,
                text: message.content,
                completed: true,
                toolName: message.toolName,
                reasoningText: message.reasoningText
            )
        }
        guard !older.isEmpty else { return }
        rows.insert(contentsOf: older, at: 0)
    }

    /// Resume parity: a turn was already executing when we attached; make
    /// sure the current assistant row is open so deltas have somewhere to
    /// land. REST history is loaded as completed rows, so an active turn may
    /// need to reopen the assistant row immediately after the latest user row
    /// rather than appending a second assistant bubble.
    mutating func ensureInflightAssistantRow(text: String, completed: Bool) {
        if let streamingIndex = rows.lastIndex(where: { !$0.completed && $0.role == "assistant" }) {
            let currentText = rows[streamingIndex].text
            if text.count > currentText.count, text.hasPrefix(currentText) {
                rows[streamingIndex].text = text
            }
            return
        }

        if !completed,
           let latestUserIndex = rows.lastIndex(where: { $0.role == "user" }),
           let assistantIndex = rows.indices.reversed().first(where: {
               $0 > latestUserIndex && rows[$0].role == "assistant"
           }) {
            rows[assistantIndex].completed = false
            if !text.isEmpty { rows[assistantIndex].text = text }
            return
        }

        rows.append(Row(role: "assistant", text: text, completed: completed))
    }

    /// Marks an active assistant turn complete when resume proves that the
    /// server is no longer running it and no terminal event reached the view.
    mutating func finishStreamingAssistant() {
        for index in rows.indices where rows[index].role == "assistant" && !rows[index].completed {
            rows[index].completed = true
        }
        finishRunningTools()
    }

    /// Optimistic local echo of a submitted user prompt.
    mutating func appendUserMessage(_ text: String) {
        rows.append(Row(role: "user", text: text, completed: true))
    }
}

/// Renderable timeline units. Historical tool-role rows are intentionally
/// grouped so their raw JSON remains behind one compact activity disclosure,
/// matching the mature Android transcript structure.
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

func coalesceTranscriptEntries(_ rows: [TranscriptState.Row]) -> [TranscriptEntry] {
    var entries: [TranscriptEntry] = []
    var toolRun: [TranscriptState.Row] = []
    var burstReasoning: [TranscriptState.Row] = []

    // A "work burst" is a reasoning-only assistant row followed by one or
    // more tool rows with no visible prose in between — the common agent
    // loop. It collapses into a single compact activity line.
    func isReasoningOnly(_ row: TranscriptState.Row) -> Bool {
        row.role.lowercased() == "assistant"
            && !row.reasoningText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && row.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func flushWork() {
        guard !toolRun.isEmpty || !burstReasoning.isEmpty else { return }
        if !burstReasoning.isEmpty {
            entries.append(.workBurst(reasoning: burstReasoning, tools: toolRun))
        } else {
            entries.append(.toolRun(toolRun))
        }
        burstReasoning.removeAll(keepingCapacity: true)
        toolRun.removeAll(keepingCapacity: true)
    }

    for row in rows {
        if row.role.lowercased() == "tool" {
            toolRun.append(row)
        } else if isReasoningOnly(row) {
            burstReasoning.append(row)
        } else {
            flushWork()
            entries.append(.message(row))
        }
    }
    flushWork()
    return entries
}
