import Foundation

extension ChatEvent {
    /// Returns a copy of the event with its `sessionID` replaced.
    ///
    /// Used by the notification path so live events (which carry the transient
    /// *runtime* session id) are keyed on the durable session id instead. That
    /// makes the notification dedupe watermark consistent with the background
    /// reconcile path (which keys on `SessionRow.id`), and makes a
    /// notification's deep-link target a durable id the app can actually open.
    func withSessionID(_ newID: String) -> ChatEvent {
        switch self {
        case .messageStart(_, let text):
            return .messageStart(sessionID: newID, text: text)
        case .messageDelta(_, let text):
            return .messageDelta(sessionID: newID, text: text)
        case .messageComplete(_, let text, let status, let error, let reasoning, let warning, let failureReason, let recoverable, let billing):
            return .messageComplete(sessionID: newID, text: text, status: status, error: error, reasoning: reasoning, warning: warning, failureReason: failureReason, recoverable: recoverable, billing: billing)
        case .reasoningDelta(_, let text, let replace):
            return .reasoningDelta(sessionID: newID, text: text, replace: replace)
        case .messageInterim(_, let text, let alreadyStreamed):
            return .messageInterim(sessionID: newID, text: text, alreadyStreamed: alreadyStreamed)
        case .toolGenerating(_, let name):
            return .toolGenerating(sessionID: newID, name: name)
        case .sessionTitle(_, let title):
            return .sessionTitle(sessionID: newID, title: title)
        case .sessionInfo(_, let storedSessionID, let model, let provider, let reasoningEffort, let fastMode, let title, let running):
            return .sessionInfo(sessionID: newID, storedSessionID: storedSessionID, model: model, provider: provider, reasoningEffort: reasoningEffort, fastMode: fastMode, title: title, running: running)
        case .error(_, let message):
            return .error(sessionID: newID, message: message)
        case .toolStart(_, let toolID, let name, let context):
            return .toolStart(sessionID: newID, toolID: toolID, name: name, context: context)
        case .toolComplete(_, let toolID, let name, let summary):
            return .toolComplete(sessionID: newID, toolID: toolID, name: name, summary: summary)
        case .statusUpdate(_, let kind, let text):
            return .statusUpdate(sessionID: newID, kind: kind, text: text)
        case .clarifyRequest(_, let requestID, let question, let choices, let multiSelect):
            return .clarifyRequest(sessionID: newID, requestID: requestID, question: question, choices: choices, multiSelect: multiSelect)
        case .clarifyExpire(_, let requestID):
            return .clarifyExpire(sessionID: newID, requestID: requestID)
        case .approvalRequest(_, let requestID, let command, let description, let choices):
            return .approvalRequest(sessionID: newID, requestID: requestID, command: command, description: description, choices: choices)
        case .approvalExpire(_, let requestID):
            return .approvalExpire(sessionID: newID, requestID: requestID)
        case .unsupportedBlockingRequest(_, let kind, let requestID, let prompt):
            return .unsupportedBlockingRequest(sessionID: newID, kind: kind, requestID: requestID, prompt: prompt)
        case .unsupportedBlockingExpire(_, let kind, let requestID):
            return .unsupportedBlockingExpire(sessionID: newID, kind: kind, requestID: requestID)
        }
    }
}
