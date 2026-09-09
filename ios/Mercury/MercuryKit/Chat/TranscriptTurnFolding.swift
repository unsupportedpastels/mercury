import Foundation
import MercuryCore

/// Stable SwiftUI identity around the shared completed-turn folding decision.
enum FoldedTranscriptEntry: Identifiable {
    case message(TranscriptState.Row)
    case activity(id: String, steps: [TranscriptState.Row], answerReasoning: String?, stepCount: Int)

    var id: String {
        switch self {
        case .message(let row): return "message:\(row.coreID)"
        case .activity(let id, _, _, _): return "turn-activity:\(id)"
        }
    }
}

func foldTranscriptTurns(_ rows: [TranscriptState.Row], turnActive: Bool) -> [FoldedTranscriptEntry] {
    var anchor: Int64 = 0
    return MercuryCore.TurnFoldingKt.foldTranscriptTurns(rows: rows.map(\.core), turnActive: turnActive)
        .compactMap { entry in
            switch entry {
            case let message as MercuryCore.FoldedTranscriptEntryMessage:
                anchor = message.row.id
                return .message(TranscriptState.Row(message.row))
            case let activity as MercuryCore.FoldedTranscriptEntryTurnActivity:
                return .activity(
                    id: activity.steps.first.map { "step:\($0.id)" } ?? "answer:\(anchor)",
                    steps: activity.steps.map(TranscriptState.Row.init),
                    answerReasoning: activity.answerReasoning,
                    stepCount: Int(activity.stepCount))
            default: return nil
            }
        }
}
