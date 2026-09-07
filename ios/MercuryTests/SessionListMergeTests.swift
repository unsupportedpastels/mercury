import XCTest
@testable import Mercury

// MARK: - Fixture

/// Plain value fixture conforming to `MergeableSession` so every merge rule is
/// exercised hermetically — no networking models, dates, or decoding involved.
private struct FixtureSession: MergeableSession, Equatable {
    var id: String
    var isLocalDraft: Bool = false

    static func server(_ id: String) -> FixtureSession {
        FixtureSession(id: id, isLocalDraft: false)
    }

    static func draft(_ id: String) -> FixtureSession {
        FixtureSession(id: id, isLocalDraft: true)
    }
}

// MARK: - SessionListMergeTests

/// Hermetic tests for the session-list merge rules ported from Android's
/// `connection/SessionListMerge.kt`
/// (`mergeServerSessionsPreservingDrafts`).
final class SessionListMergeTests: XCTestCase {

    // MARK: Rule 1 — no pending drafts returns the server list verbatim

    // MARK: Draft eligibility — pending + local draft + absent from server

    func testPendingLocalDraftMissingFromServerIsPrepended() {
        let server = [FixtureSession.server("a"), FixtureSession.server("b")]
        let draft = FixtureSession.draft("draft-1")
        let current = [FixtureSession.server("a"), draft]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1"]
        )

        XCTAssertEqual(merged, [draft] + server)
    }

    func testPromotedDraftAlreadyInServerListIsDropped() {
        // Once the server list represents the draft (promotion), it must not
        // be duplicated at the front.
        let promotedDraftOnServer = FixtureSession.server("draft-1")
        let server = [promotedDraftOnServer, FixtureSession.server("a")]
        let localDraftCopy = FixtureSession.draft("draft-1")
        let current = [localDraftCopy, FixtureSession.server("a")]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1"]
        )

        XCTAssertEqual(merged, server)
    }

    // MARK: Rule 3 — no surviving drafts returns the server list verbatim

    // MARK: Ordering — preserved drafts keep relative order, prepended

    func testMultipleSurvivingDraftsKeepTheirRelativeOrderAheadOfServerRows() {
        let server = [
            FixtureSession.server("s1"),
            FixtureSession.server("s2"),
            FixtureSession.server("dropped-on-server"),
        ]
        let current = [
            FixtureSession.server("noise"),
            FixtureSession.draft("draft-b"),
            FixtureSession.draft("dropped-on-server"),
            FixtureSession.draft("draft-a"),
        ]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-a", "draft-b", "dropped-on-server"]
        )

        // draft-b precedes draft-a exactly as in `current`; the draft whose
        // id is already represented on the server is excluded; server rows
        // keep their order verbatim.
        XCTAssertEqual(
            merged,
            [
                FixtureSession.draft("draft-b"),
                FixtureSession.draft("draft-a"),
            ] + server
        )
    }

    // MARK: Duplicates inside a single list (faithful to the Kotlin filter)

    // MARK: Empty inputs

    // MARK: Purity / determinism

}
