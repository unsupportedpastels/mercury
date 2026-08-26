import Foundation

// MARK: - Mergeable session surface

/// Minimal read-only session surface required by `SessionListMerge`.
///
/// Mirrors the only two `SessionSummary` fields the Android merge reads
/// (`id` and `isLocalDraft`, see `connection/SessionListMerge.kt`). Any
/// session model — server-backed or local-draft — can conform, which keeps
/// the merge rules independent of decoding shape and lets tests use plain
/// fixtures instead of network models.
protocol MergeableSession: Sendable {
    /// Durable session identifier.
    var id: String { get }

    /// True for "New chat" entries that exist only on this client until they
    /// are promoted to a server session.
    var isLocalDraft: Bool { get }
}

// MARK: - Merge rules

/// Pure session-list merge rules, ported 1:1 from the Android client's
/// `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/SessionListMerge.kt`.
///
/// Replaces the durable session list with a server-fetched list while keeping
/// local draft sessions ("New chat" entries that only exist on this client)
/// visible. Without this, a background session-list refresh that lands while a
/// draft is open removes the draft from the snapshot and the open detail route
/// degrades to "Session is no longer available".
///
/// Drafts are kept only while they are still pending (not yet promoted to a
/// server session) and not already represented in the server list.
///
/// Determinism notes (all inherited from the Kotlin source):
/// - The server list is authoritative: its contents and order win verbatim;
///   nothing is re-sorted and no rows are deduped out of it.
/// - Preserved drafts keep their relative order from `currentSessions` and are
///   prepended ahead of the server rows.
enum SessionListMerge {
    /// Merges a freshly fetched server session list into the current one,
    /// preserving still-pending local drafts.
    ///
    /// Ported from `mergeServerSessionsPreservingDrafts`:
    /// 1. No pending drafts → the server list is returned verbatim.
    /// 2. Otherwise, keep every row from `currentSessions` that is a local
    ///    draft, whose id is still pending, and that the server list does not
    ///    already contain (promotion or representation on the server drops it).
    /// 3. No such drafts survive → the server list is returned verbatim.
    /// 4. Otherwise the surviving drafts are prepended, in their existing
    ///    relative order, to the untouched server list.
    ///
    /// - Parameters:
    ///   - serverSessions: The authoritative server-fetched replacement list.
    ///   - currentSessions: The list currently rendered on this client,
    ///     possibly containing local draft rows.
    ///   - pendingDraftIDs: Ids of local drafts that have not been promoted to
    ///     server sessions yet.
    /// - Returns: The merged list. A pure function: no I/O, no mutation, and
    ///   identical inputs always produce an identical output.
    static func merged<Session: MergeableSession>(
        serverSessions: [Session],
        currentSessions: [Session],
        pendingDraftIDs: Set<String>
    ) -> [Session] {
        if pendingDraftIDs.isEmpty { return serverSessions }
        let serverIds = Set(serverSessions.map(\.id))
        let preservedDrafts = currentSessions.filter { session in
            session.isLocalDraft
                && pendingDraftIDs.contains(session.id)
                && !serverIds.contains(session.id)
        }
        if preservedDrafts.isEmpty { return serverSessions }
        return preservedDrafts + serverSessions
    }
}
