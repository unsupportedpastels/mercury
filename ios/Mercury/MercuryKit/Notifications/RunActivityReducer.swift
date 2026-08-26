import Foundation
import MercuryRunActivityKit

/// The identity and preferences supplied by the app for one durable session.
struct RunActivityReducerContext: Equatable {
    let serverID: UUID
    let profile: String
    let durableSessionID: String
    let sessionTitle: String
    let baselineMessageCount: Int
    let excerptsEnabled: Bool
    let now: Date

    init(
        serverID: UUID,
        profile: String,
        durableSessionID: String,
        sessionTitle: String,
        baselineMessageCount: Int,
        excerptsEnabled: Bool = false,
        now: Date = Date()
    ) {
        self.serverID = serverID
        self.profile = profile
        self.durableSessionID = durableSessionID
        self.sessionTitle = sessionTitle
        self.baselineMessageCount = baselineMessageCount
        self.excerptsEnabled = excerptsEnabled
        self.now = now
    }
}

/// Mutable, deterministic state kept by the app while reducing one session's
/// live event stream. Raw response text never leaves this reducer state unless
/// the caller explicitly enables sanitized response excerpts.
struct RunActivityReductionState: Equatable {
    var startedAt: Date?
    var lastStatus: MercuryRunActivityStatus?
    var rawResponseText: String
    var activeToolLabels: [String]
    var awaitingInput: MercuryRunActivityStatus?
    var finalized: Bool

    // These are reducer bookkeeping details, not ActivityKit payload fields.
    // Internal (not private): RunActivityReducer is a separate type and must
    // mutate them; they still never enter the ActivityKit payload.
    var activeToolKeys: [String]
    var statusBeforeWaiting: MercuryRunActivityStatus?

    init(
        startedAt: Date? = nil,
        lastStatus: MercuryRunActivityStatus? = nil,
        rawResponseText: String = "",
        activeToolLabels: [String] = [],
        awaitingInput: MercuryRunActivityStatus? = nil,
        finalized: Bool = false
    ) {
        self.startedAt = startedAt
        self.lastStatus = lastStatus
        self.rawResponseText = rawResponseText
        self.activeToolLabels = activeToolLabels
        self.awaitingInput = awaitingInput
        self.finalized = finalized
        self.activeToolKeys = []
        self.statusBeforeWaiting = nil
    }
}

enum RunActivityCommand: Equatable {
    case start(RunActivitySeed)
    case update(MercuryRunActivityContentState)
    case end(MercuryRunActivityContentState)
    case none
}

enum RunActivityReducer {
    /// Short alias for callers and tests that prefer a nested context name.
    typealias Context = RunActivityReducerContext

    static func reduce(
        event: ChatEvent,
        state: inout RunActivityReductionState,
        context: RunActivityReducerContext
    ) -> RunActivityCommand {
        guard event.sessionID == context.durableSessionID else { return .none }
        guard !state.finalized else { return .none }

        switch event {
        case .messageStart(_, let text):
            guard state.startedAt == nil else {
                let status: MercuryRunActivityStatus = state.rawResponseText.isEmpty ? .thinking : .responding
                if let text, state.rawResponseText.isEmpty {
                    state.rawResponseText = text
                }
                return update(status: status, state: &state, context: context)
            }

            state.startedAt = context.now
            state.lastStatus = .starting
            state.rawResponseText = text ?? ""
            state.activeToolLabels.removeAll(keepingCapacity: false)
            state.activeToolKeys.removeAll(keepingCapacity: false)
            state.awaitingInput = nil
            state.statusBeforeWaiting = nil

            let initialState = contentState(
                status: .starting,
                activityLine: RunActivityPolicy.displayName(for: .starting),
                responseExcerpt: "",
                updatedAt: context.now
            )
            let seed = RunActivitySeed(
                serverID: context.serverID,
                profile: context.profile,
                durableSessionID: context.durableSessionID,
                sessionTitle: RunActivitySanitizer.sanitizeTitle(context.sessionTitle),
                startedAt: context.now,
                baselineMessageCount: context.baselineMessageCount,
                initialState: initialState
            )
            return .start(seed)

        case .messageDelta(_, let text):
            state.rawResponseText += text
            return update(status: .responding, state: &state, context: context)

        case .reasoningDelta:
            return update(status: .thinking, activityLine: "Thinking", state: &state, context: context)

        case .messageInterim:
            return update(status: .responding, activityLine: "Responding", state: &state, context: context)

        case .statusUpdate(_, let kind, let text):
            let sanitized = RunActivitySanitizer.sanitizeActivityLine(text)
            let status: MercuryRunActivityStatus = kind.lowercased().contains("reconnect")
                ? .reconnecting
                : workingStatus(for: state)
            return update(
                status: status,
                activityLine: sanitized.isEmpty ? RunActivityPolicy.displayName(for: status) : sanitized,
                state: &state,
                context: context
            )

        case .toolGenerating(_, let name):
            addTool(key: generationKey(for: name), label: RunActivitySanitizer.toolLabel(forToolName: name), state: &state)
            return update(status: .usingTool, state: &state, context: context)

        case .toolStart(_, let toolID, let name, _):
            // Replace a matching argument-generation placeholder with the
            // stable tool ID as soon as the server sends the start frame.
            removeTool(key: generationKey(for: name), state: &state)
            addTool(key: toolKey(for: toolID), label: RunActivitySanitizer.toolLabel(forToolName: name), state: &state)
            return update(status: .usingTool, state: &state, context: context)

        case .toolComplete(_, let toolID, let name, _):
            removeTool(key: toolKey(for: toolID), state: &state)
            removeTool(key: generationKey(for: name), state: &state)
            if state.activeToolLabels.isEmpty {
                return update(status: state.rawResponseText.isEmpty ? .thinking : .responding, state: &state, context: context)
            }
            return update(status: .usingTool, state: &state, context: context)

        case .approvalRequest:
            return enterWaiting(.waitingForApproval, state: &state, context: context)

        case .clarifyRequest:
            return enterWaiting(.waitingForClarification, state: &state, context: context)

        case .unsupportedBlockingRequest(_, let kind, _, _):
            switch kind {
            case .secret, .sudo:
                return enterWaiting(.waitingForSecureInput, state: &state, context: context)
            case .terminalRead, .previewRead, .windowRead:
                // Mercury auto-answers these unavailable Desktop surfaces with
                // an empty value. They must not expose a false waiting state.
                return .none
            }

        case .approvalExpire, .clarifyExpire, .unsupportedBlockingExpire:
            guard state.awaitingInput != nil else { return .none }
            let resumedStatus = state.statusBeforeWaiting ?? workingStatus(for: state)
            state.awaitingInput = nil
            state.statusBeforeWaiting = nil
            return update(status: resumedStatus, state: &state, context: context)

        case .messageComplete(_, let text, let wireStatus, _, _, _, _, _, _):
            let completionStatus = NotificationTextPolicy.completionStatus(fromWire: wireStatus)
            let status: MercuryRunActivityStatus
            switch completionStatus {
            case .finished:
                status = .complete
            case .failed:
                status = .failed
            case .cancelled:
                status = .cancelled
            }

            if let text { state.rawResponseText = text }
            state.lastStatus = status
            state.awaitingInput = nil
            state.statusBeforeWaiting = nil
            state.finalized = true
            state.activeToolLabels.removeAll(keepingCapacity: false)
            state.activeToolKeys.removeAll(keepingCapacity: false)

            let excerpt: String
            if context.excerptsEnabled, completionStatus == .finished {
                excerpt = RunActivitySanitizer.responseExcerpt(from: text ?? state.rawResponseText)
            } else {
                excerpt = ""
            }
            let finalState = contentState(
                status: status,
                activityLine: RunActivityPolicy.displayName(for: status),
                responseExcerpt: excerpt,
                updatedAt: context.now
            )
            return .end(finalState)

        case .error:
            state.lastStatus = .failed
            state.awaitingInput = nil
            state.statusBeforeWaiting = nil
            state.finalized = true
            state.activeToolLabels.removeAll(keepingCapacity: false)
            state.activeToolKeys.removeAll(keepingCapacity: false)
            return .end(
                contentState(
                    status: .failed,
                    activityLine: "The run failed.",
                    responseExcerpt: "",
                    updatedAt: context.now
                )
            )

        case .sessionTitle, .sessionInfo:
            return .none
        }
    }

    private static func update(
        status: MercuryRunActivityStatus,
        state: inout RunActivityReductionState,
        context: RunActivityReducerContext
    ) -> RunActivityCommand {
        update(
            status: status,
            activityLine: RunActivityPolicy.displayName(for: status),
            state: &state,
            context: context
        )
    }

    private static func update(
        status: MercuryRunActivityStatus,
        activityLine: String,
        state: inout RunActivityReductionState,
        context: RunActivityReducerContext
    ) -> RunActivityCommand {
        state.lastStatus = status
        if !status.isWaitingForInput {
            state.awaitingInput = nil
            state.statusBeforeWaiting = nil
        }
        let excerpt = context.excerptsEnabled
            ? RunActivitySanitizer.responseExcerpt(from: state.rawResponseText)
            : ""
        return .update(
            contentState(
                status: status,
                activityLine: activityLine,
                responseExcerpt: excerpt,
                updatedAt: context.now
            )
        )
    }

    private static func enterWaiting(
        _ status: MercuryRunActivityStatus,
        state: inout RunActivityReductionState,
        context: RunActivityReducerContext
    ) -> RunActivityCommand {
        if let current = state.lastStatus,
           !current.isFinal,
           !current.isWaitingForInput,
           current != .starting {
            state.statusBeforeWaiting = current
        } else if state.statusBeforeWaiting == nil {
            state.statusBeforeWaiting = workingStatus(for: state)
        }
        state.awaitingInput = status
        return update(
            status: status,
            activityLine: RunActivityPolicy.displayName(for: status),
            state: &state,
            context: context
        )
    }

    private static func workingStatus(for state: RunActivityReductionState) -> MercuryRunActivityStatus {
        if !state.activeToolLabels.isEmpty { return .usingTool }
        if !state.rawResponseText.isEmpty { return .responding }
        return .thinking
    }

    private static func contentState(
        status: MercuryRunActivityStatus,
        activityLine: String,
        responseExcerpt: String,
        updatedAt: Date
    ) -> MercuryRunActivityContentState {
        let safeLine = RunActivitySanitizer.sanitizeActivityLine(activityLine)
        return MercuryRunActivityContentState(
            status: status,
            activityLine: safeLine,
            responseExcerpt: responseExcerpt,
            updatedAt: updatedAt,
            isStale: false,
            isFinal: status.isFinal
        )
    }

    private static func generationKey(for name: String) -> String {
        "generation:\(name)"
    }

    private static func toolKey(for id: String) -> String {
        "tool:\(id)"
    }

    private static func addTool(
        key: String,
        label: String,
        state: inout RunActivityReductionState
    ) {
        if let index = state.activeToolKeys.firstIndex(of: key) {
            state.activeToolLabels[index] = label
        } else {
            state.activeToolKeys.append(key)
            state.activeToolLabels.append(label)
        }
    }

    private static func removeTool(
        key: String,
        state: inout RunActivityReductionState
    ) {
        guard let index = state.activeToolKeys.firstIndex(of: key) else { return }
        state.activeToolKeys.remove(at: index)
        state.activeToolLabels.remove(at: index)
    }
}
