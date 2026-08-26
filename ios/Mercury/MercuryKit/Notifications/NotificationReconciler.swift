import Foundation

/// Maps a freshly fetched session list into reconciliation deltas for the
/// best-effort background/reopen notification path.
///
/// ## Android parity contract
///
/// Android only ever notifies for sessions its live event collector is
/// subscribed to — i.e. sessions the app opened — and only on a genuine
/// `message.complete`, quoting the assistant's response text. iOS has no live
/// socket in the background, so this REST reconciler reproduces the same
/// *visual outcome* under three rules:
///
/// 1. **Scope to engaged sessions.** Only sessions this install has opened are
///    eligible. Without this the reconciler would notify for every session on
///    the shared server (the reported bug).
/// 2. **Only on advance.** A session notifies only when its server
///    `message_count` has grown past the last count we reconciled it at, so an
///    unchanged list is silent and re-polls don't re-fire.
/// 3. **Excerpt the assistant response, and only if the turn ended on one.**
///    The REST list `preview` is the *first user prompt*, never the response, so
///    it is not usable as a body. Instead we fetch the session's latest message;
///    if the tail is an assistant message we quote it (Android-style excerpt);
///    if the tail is a tool/user message the turn is still mid-flight and we
///    post nothing (advance-only), matching Android never notifying on a tool
///    call.
///
/// The `turnSignature` is namespaced with `rest#` so it never collides with a
/// live-path signature; the engine stays idempotent across repeat polls.
enum NotificationReconciler {

    /// The tail of a session transcript, reduced to what the reconciler needs.
    struct SessionTail: Sendable, Equatable {
        /// True when the newest message is an assistant message (turn finished
        /// on a response). False for a tool/user tail (still working).
        let endedOnAssistant: Bool
        /// The assistant response text to excerpt. Empty when `endedOnAssistant`
        /// is false.
        let assistantText: String
    }

    /// Builds reconciliation deltas from the session list.
    ///
    /// - Parameters:
    ///   - sessions: the freshly loaded REST session list.
    ///   - engagedIDs: durable IDs this install has opened (notification scope).
    ///   - watermarks: current per-session watermarks (for advance detection).
    ///   - fetchTail: async closure returning the transcript tail for a session,
    ///     or nil when it can't be fetched. Only called for engaged sessions
    ///     whose count advanced, so at most one transcript fetch per changed
    ///     session per poll.
    static func deltas(
        from sessions: [SessionRow],
        engagedIDs: Set<String>,
        watermarks: [String: SessionWatermark],
        fetchTail: (String) async -> SessionTail?
    ) async -> [ReconciliationDelta] {
        var result: [ReconciliationDelta] = []
        for row in sessions {
            guard row.messageCount > 0 else { continue }
            // Rule 1: notification scope is engaged sessions only.
            guard engagedIDs.contains(row.id) else { continue }
            // Rule 2: only when the server count advanced past our watermark.
            let lastSeen = watermarks[row.id]?.lastServerMessageCount ?? 0
            guard row.messageCount > lastSeen else { continue }

            // Rule 3: quote the assistant response, only if the turn ended on
            // one. A nil/tool/user tail posts nothing but still advances the
            // server-count watermark so we don't re-check it every poll.
            let tail = await fetchTail(row.id)
            let completion: CompletionOutcome?
            if let tail, tail.endedOnAssistant {
                let signature = "rest#\(row.messageCount)#\(fnv1a(tail.assistantText))"
                completion = CompletionOutcome(
                    text: tail.assistantText,
                    status: .finished,
                    turnSignature: signature
                )
            } else {
                completion = nil
            }

            result.append(
                ReconciliationDelta(
                    sessionID: row.id,
                    sessionTitle: row.title,
                    serverMessageCount: row.messageCount,
                    newCompletion: completion,
                    openedApproval: false,
                    openedClarify: false,
                    openedSecure: false
                )
            )
        }
        return result
    }

    /// Reduces a fetched transcript tail (newest-last) to a `SessionTail`.
    /// A trailing interrupt sentinel is treated as a non-assistant tail so it
    /// is never quoted as a response.
    static func tail(fromMessages messages: [TranscriptMessage]) -> SessionTail {
        guard let last = messages.last else {
            return SessionTail(endedOnAssistant: false, assistantText: "")
        }
        let isAssistant = last.role.lowercased() == "assistant"
        let text = last.content
        let usable = isAssistant
            && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !TranscriptState.isInterruptSentinel(text)
        return SessionTail(
            endedOnAssistant: usable,
            assistantText: usable ? text : ""
        )
    }

    /// Stable, launch-independent hash (same construction as the live reducer's
    /// signature) so a persisted watermark stays valid across app restarts.
    private static func fnv1a(_ text: String) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return String(hash, radix: 16)
    }
}
