import Foundation
import MercuryCore

/// Native value adaptation only. Android and iOS share the parser, observation
/// reducer, optimistic-send rollback, and recovery version fence.
enum SessionProgressBridge {
    static func parse(_ rows: [[String: Any]]) -> MercuryCore.DurableProgress {
        guard let data = try? JSONSerialization.data(withJSONObject: rows),
              let json = String(data: data, encoding: .utf8) else {
            return MercuryCore.DurableProgressBridge.shared.initial()
        }
        return MercuryCore.DurableProgressBridge.shared.parseJson(rowsJson: json)
    }

    static func observation(_ event: ChatEvent) -> MercuryCore.ProgressObservation {
        switch event {
        case .toolStart(_, let id, let name, let context, let historical):
            return MercuryCore.ProgressObservationToolStarted(
                toolCallId: id, toolName: name, context: context, historical: historical)
        case .toolComplete(_, let id, let name, let summary, let snapshot, let historical):
            return MercuryCore.ProgressObservationToolCompleted(
                toolCallId: id, toolName: name, summary: summary, snapshot: snapshot, historical: historical)
        case .statusUpdate, .clarifyRequest, .approvalRequest:
            return MercuryCore.ProgressObservationLiveness.shared
        default:
            return MercuryCore.ProgressObservationUnrelated.shared
        }
    }
}

extension ChatSessionState {
    func observeProgress(_ event: ChatEvent, atMillis: Int64) {
        guard event.belongsToPresentation(runtimeID: runtimeSessionID, durableID: durableID) else { return }
        progress = progress.observe(observation: SessionProgressBridge.observation(event), atEpochMillis: atMillis)
        switch event {
        case .statusUpdate(_, let kind, _): latestStatusKind = kind
        case .toolGenerating: latestStatusKind = "tool.generating"
        case .messageComplete, .error: latestStatusKind = nil
        default: break
        }
    }

    var activityTurnActive: Bool { isSending || turnInFlight }

    /// Tool rows are live run state, never recovered history. `restored` is set by
    /// every reopen/foreground history refresh of a running turn and only a todo
    /// snapshot or the next Send clears it, so it must not stale live rows.
    var activityToolRowsLive: Bool { activityTurnActive && !connectionStateIsNotLive }

    /// Milestones may be a recovered snapshot; saved plans never animate.
    var activityMilestonesLive: Bool { activityToolRowsLive && !progress.restored }

    func activityLine(activeChildCount: Int) -> MercuryCore.ActivityLineState {
        let phase: String
        switch connectionState {
        case .connecting: phase = "connecting"
        case .reconnecting: phase = "reconnecting"
        case .live, .offline: phase = "idle"
        }
        let running = transcript.tools.filter { $0.state == .running }
        let last = transcript.rows.last
        return MercuryCore.ActivityLinePolicy.shared.decide(input: MercuryCore.ActivityLineInput(
            isSending: activityTurnActive,
            isStopping: isStopping,
            connectionPhase: phase,
            pendingSubmission: isComposerActionPending && isSending && !transcript.hasStreamingAssistant,
            connectionLost: { if case .offline = connectionState { return true }; return false }(),
            awaitingUser: transcript.pendingRequest != nil || outstandingSecure != nil,
            runningToolNames: running.map(\.name),
            runningToolContext: running.first?.context,
            statusText: transcript.generatingStatusText ?? transcript.latestStatusText,
            statusKind: latestStatusKind,
            streamingAnswer: last?.role == "assistant" && last?.completed == false && !(last?.text.isEmpty ?? true),
            streamingReasoning: last?.role == "assistant" && last?.completed == false && !(last?.reasoningText.isEmpty ?? true),
            inProgressTodo: progress.milestones.first { $0.status == .inprogress }?.content,
            activeChildCount: Int32(clamping: activeChildCount)
        ))
    }
}
