import Foundation

struct ReconciliationEngine {
    /// Applies background/reopen deltas to caller-owned watermarks. The delta
    /// producer (polling/reconnect) is deliberately outside this pure layer.
    static func reconcile(
        deltas: [ReconciliationDelta],
        visibility: SessionNotificationVisibility,
        watermarks: inout [String: SessionWatermark]
    ) -> [PendingNotification] {
        var notifications: [PendingNotification] = []
        notifications.reserveCapacity(deltas.count)

        for delta in deltas {
            var watermark = watermarks[delta.sessionID]
                ?? SessionWatermark(sessionID: delta.sessionID)
            watermark.sessionID = delta.sessionID
            // Always advance the server-count watermark, even for an
            // advance-only delta (mid-turn / non-assistant tail) that posts
            // nothing, so the same count is not re-checked on the next poll.
            watermark.lastServerMessageCount = max(
                watermark.lastServerMessageCount,
                delta.serverMessageCount
            )

            if let completion = delta.newCompletion,
               watermark.lastCompletedTurnSignature != completion.turnSignature {
                watermark.lastCompletedTurnSignature = completion.turnSignature
                watermark.lastMessageCount += 1
                watermark.hasOpenApproval = false
                watermark.hasOpenClarify = false
                watermark.hasOpenSecure = false

                if NotificationVisibilityPolicy.shouldPost(
                    sessionID: delta.sessionID,
                    visibility: visibility
                ) {
                    notifications.append(
                        PendingNotification(
                            sessionID: delta.sessionID,
                            kind: .completion(status: completion.status),
                            sessionTitle: delta.sessionTitle,
                            heading: NotificationTextPolicy.completionHeading(status: completion.status),
                            body: NotificationTextPolicy.finalResponsePreview(completion.text),
                            dedupeKey: "\(delta.sessionID)|completion|\(completion.turnSignature)"
                        )
                    )
                }
            }

            if delta.openedApproval && !watermark.hasOpenApproval {
                watermark.hasOpenApproval = true
                appendInputNotification(
                    kind: .approval,
                    body: "Authorization is required to continue",
                    sessionID: delta.sessionID,
                    sessionTitle: delta.sessionTitle,
                    visibility: visibility,
                    notifications: &notifications
                )
            }

            if delta.openedClarify && !watermark.hasOpenClarify {
                watermark.hasOpenClarify = true
                appendInputNotification(
                    kind: .clarification,
                    body: "Clarification is required to continue",
                    sessionID: delta.sessionID,
                    sessionTitle: delta.sessionTitle,
                    visibility: visibility,
                    notifications: &notifications
                )
            }

            if delta.openedSecure && !watermark.hasOpenSecure {
                watermark.hasOpenSecure = true
                appendInputNotification(
                    kind: .secureInput,
                    body: "Secure input required",
                    sessionID: delta.sessionID,
                    sessionTitle: delta.sessionTitle,
                    visibility: visibility,
                    notifications: &notifications
                )
            }

            watermarks[delta.sessionID] = watermark
        }

        return notifications
    }

    private static func appendInputNotification(
        kind: NotificationKind,
        body: String,
        sessionID: String,
        sessionTitle: String,
        visibility: SessionNotificationVisibility,
        notifications: inout [PendingNotification]
    ) {
        guard NotificationVisibilityPolicy.shouldPost(
            sessionID: sessionID,
            visibility: visibility
        ) else {
            return
        }

        let kindTag: String
        switch kind {
        case .approval:
            kindTag = "approval"
        case .clarification:
            kindTag = "clarification"
        case .secureInput:
            kindTag = "secure"
        case .completion:
            return
        }

        notifications.append(
            PendingNotification(
                sessionID: sessionID,
                kind: kind,
                sessionTitle: sessionTitle,
                heading: NotificationTextPolicy.inputHeading(for: kind),
                body: NotificationTextPolicy.inputPreview(body),
                dedupeKey: "\(sessionID)|\(kindTag)"
            )
        )
    }
}
