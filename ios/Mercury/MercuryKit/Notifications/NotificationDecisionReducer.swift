import Foundation

enum NotificationDecisionReducer {
    /// Reduces one live chat event against caller-owned session state.
    static func decide(
        event: ChatEvent,
        sessionTitle: String,
        visibility: SessionNotificationVisibility,
        watermark: inout SessionWatermark
    ) -> PendingNotification? {
        let sessionID = event.sessionID
        watermark.sessionID = sessionID

        switch event {
        case .messageStart:
            // A new live turn permits the same response text to be notified for
            // a later turn. The count is incremented only on completion.
            watermark.lastCompletedTurnSignature = nil
            return nil

        case .messageComplete(_, let text, let wireStatus, _, _, _, _, _, _):
            let status = NotificationTextPolicy.completionStatus(fromWire: wireStatus)
            let responseText = text ?? ""
            let signature = turnSignature(
                messageCount: watermark.lastMessageCount,
                status: status,
                text: responseText
            )
            let priorCountSignature: String? = watermark.lastMessageCount > 0
                ? turnSignature(
                    messageCount: watermark.lastMessageCount - 1,
                    status: status,
                    text: responseText
                )
                : nil
            let isDuplicate = watermark.lastCompletedTurnSignature == signature
                || (priorCountSignature != nil && watermark.lastCompletedTurnSignature == priorCountSignature)
            guard !isDuplicate else {
                return nil
            }

            watermark.lastCompletedTurnSignature = signature
            watermark.lastMessageCount += 1
            watermark.hasOpenApproval = false
            watermark.hasOpenClarify = false
            watermark.hasOpenSecure = false

            guard NotificationVisibilityPolicy.shouldPost(
                sessionID: sessionID,
                visibility: visibility
            ) else {
                return nil
            }

            return PendingNotification(
                sessionID: sessionID,
                kind: .completion(status: status),
                sessionTitle: sessionTitle,
                heading: NotificationTextPolicy.completionHeading(status: status),
                body: NotificationTextPolicy.finalResponsePreview(responseText),
                dedupeKey: "\(sessionID)|completion|\(signature)"
            )

        case .approvalRequest(_, _, let command, let description, _):
            guard !watermark.hasOpenApproval else { return nil }
            watermark.hasOpenApproval = true
            guard NotificationVisibilityPolicy.shouldPost(
                sessionID: sessionID,
                visibility: visibility
            ) else {
                return nil
            }

            let preview = description ?? command ?? "Authorization is required to continue"
            return inputNotification(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                kind: .approval,
                body: preview
            )

        case .clarifyRequest(_, _, let question, _, _):
            guard !watermark.hasOpenClarify else { return nil }
            watermark.hasOpenClarify = true
            guard NotificationVisibilityPolicy.shouldPost(
                sessionID: sessionID,
                visibility: visibility
            ) else {
                return nil
            }

            return inputNotification(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                kind: .clarification,
                body: question
            )

        case .unsupportedBlockingRequest(_, let kind, _, let prompt):
            guard kind == .secret || kind == .sudo else { return nil }
            guard !watermark.hasOpenSecure else { return nil }
            watermark.hasOpenSecure = true
            guard NotificationVisibilityPolicy.shouldPost(
                sessionID: sessionID,
                visibility: visibility
            ) else {
                return nil
            }

            return inputNotification(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                kind: .secureInput,
                body: prompt ?? "Secure input required"
            )

        case .approvalExpire:
            watermark.hasOpenApproval = false
            return nil

        case .clarifyExpire:
            watermark.hasOpenClarify = false
            return nil

        case .unsupportedBlockingExpire(_, let kind, _):
            if kind == .secret || kind == .sudo {
                watermark.hasOpenSecure = false
            }
            return nil

        default:
            // Streaming, reasoning, tool, metadata, and terminal/preview/window
            // read events are intentionally non-notifying.
            return nil
        }
    }

    private static func inputNotification(
        sessionID: String,
        sessionTitle: String,
        kind: NotificationKind,
        body: String
    ) -> PendingNotification {
        PendingNotification(
            sessionID: sessionID,
            kind: kind,
            sessionTitle: sessionTitle,
            heading: NotificationTextPolicy.inputHeading(for: kind),
            body: NotificationTextPolicy.inputPreview(body),
            dedupeKey: "\(sessionID)|\(inputKindTag(kind))"
        )
    }

    private static func inputKindTag(_ kind: NotificationKind) -> String {
        switch kind {
        case .approval:
            return "approval"
        case .clarification:
            return "clarification"
        case .secureInput:
            return "secure"
        case .completion:
            return "completion"
        }
    }

    /// Stable across process launches: the completion count, normalized status,
    /// and FNV-1a over the complete response text. Unlike Swift's `hashValue`,
    /// this is suitable for a persisted watermark. A duplicate completion is
    /// also compared against the immediately preceding count because the first
    /// delivery increments the watermark before a retransmitted event arrives.
    private static func turnSignature(
        messageCount: Int,
        status: CompletionStatus,
        text: String
    ) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return "\(messageCount)#\(statusTag(status))#\(String(hash, radix: 16))"
    }

    private static func statusTag(_ status: CompletionStatus) -> String {
        switch status {
        case .finished:
            return "finished"
        case .failed:
            return "failed"
        case .cancelled:
            return "cancelled"
        }
    }
}
