import Foundation
import MercuryCore

/// Swift-native facade for events decoded by the shared KMP wire policy.
extension ChatEvent {
    init?(shared event: MercuryCore.ChatEvent) {
        switch event {
        case let value as MercuryCore.ChatEventMessageStart:
            self = .messageStart(sessionID: value.sessionId, text: value.text)
        case let value as MercuryCore.ChatEventMessageDelta:
            self = .messageDelta(sessionID: value.sessionId, text: value.text)
        case let value as MercuryCore.ChatEventMessageComplete:
            self = .messageComplete(
                sessionID: value.sessionId,
                text: value.text,
                status: value.status,
                error: value.error,
                reasoning: value.reasoning,
                warning: value.warning,
                failureReason: value.failureReason,
                recoverable: value.recoverable,
                billing: value.billing.map {
                    BillingInfo(
                        provider: $0.provider,
                        billingURL: $0.billingUrl,
                        isNous: $0.isNous,
                        message: $0.message
                    )
                }
            )
        case let value as MercuryCore.ChatEventReasoningDelta:
            self = .reasoningDelta(sessionID: value.sessionId, text: value.text, replace: value.replace)
        case let value as MercuryCore.ChatEventMessageInterim:
            self = .messageInterim(
                sessionID: value.sessionId,
                text: value.text,
                alreadyStreamed: value.alreadyStreamed
            )
        case let value as MercuryCore.ChatEventToolGenerating:
            self = .toolGenerating(sessionID: value.sessionId, name: value.name)
        case let value as MercuryCore.ChatEventSessionTitle:
            self = .sessionTitle(sessionID: value.sessionId, title: value.title)
        case let value as MercuryCore.ChatEventSessionInfo:
            self = .sessionInfo(
                sessionID: value.sessionId,
                storedSessionID: value.storedSessionId,
                model: value.model,
                provider: value.provider,
                reasoningEffort: value.reasoningEffort,
                fastMode: value.fastMode?.boolValue,
                title: value.title,
                running: value.running?.boolValue
            )
        case let value as MercuryCore.ChatEventError:
            self = .error(sessionID: value.sessionId, message: value.message)
        case let value as MercuryCore.ChatEventToolStart:
            self = .toolStart(
                sessionID: value.sessionId,
                toolID: value.toolId,
                name: value.name,
                context: value.context
            )
        case let value as MercuryCore.ChatEventToolComplete:
            self = .toolComplete(
                sessionID: value.sessionId,
                toolID: value.toolId,
                name: value.name,
                summary: value.summary
            )
        case let value as MercuryCore.ChatEventStatusUpdate:
            self = .statusUpdate(sessionID: value.sessionId, kind: value.kind, text: value.text)
        case let value as MercuryCore.ChatEventClarifyRequest:
            self = .clarifyRequest(
                sessionID: value.sessionId,
                requestID: value.requestId,
                question: value.question,
                choices: value.choices,
                multiSelect: value.multiSelect
            )
        case let value as MercuryCore.ChatEventClarifyExpire:
            self = .clarifyExpire(sessionID: value.sessionId, requestID: value.requestId)
        case let value as MercuryCore.ChatEventApprovalRequest:
            self = .approvalRequest(
                sessionID: value.sessionId,
                requestID: value.requestId,
                command: value.command,
                description: value.description_,
                choices: value.choices
            )
        case let value as MercuryCore.ChatEventApprovalExpire:
            self = .approvalExpire(sessionID: value.sessionId, requestID: value.requestId)
        case let value as MercuryCore.ChatEventUnsupportedBlockingRequest:
            guard let kind = UnsupportedBlockingKind(shared: value.kind) else { return nil }
            self = .unsupportedBlockingRequest(
                sessionID: value.sessionId,
                kind: kind,
                requestID: value.requestId,
                prompt: value.prompt
            )
        case let value as MercuryCore.ChatEventUnsupportedBlockingExpire:
            guard let kind = UnsupportedBlockingKind(shared: value.kind) else { return nil }
            self = .unsupportedBlockingExpire(
                sessionID: value.sessionId,
                kind: kind,
                requestID: value.requestId
            )
        default:
            return nil
        }
    }
}

private extension UnsupportedBlockingKind {
    init?(shared kind: MercuryCore.UnsupportedBlockingKind) {
        if kind == .secret { self = .secret }
        else if kind == .sudo { self = .sudo }
        else if kind == .terminalread { self = .terminalRead }
        else if kind == .previewread { self = .previewRead }
        else if kind == .windowread { self = .windowRead }
        else { return nil }
    }
}
