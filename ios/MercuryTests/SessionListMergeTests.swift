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

    func testEmptyPendingDraftsReturnsServerListVerbatim() {
        let server = [FixtureSession.server("a"), FixtureSession.server("b")]
        let current = [
            FixtureSession.draft("draft-1"),
            FixtureSession.server("a"),
        ]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: []
        )

        // Even though a local draft exists in `current`, with no pending
        // drafts the server list wins untouched.
        XCTAssertEqual(merged, server)
    }

    func testEmptyPendingDraftsAndEmptyCurrentReturnsServerListVerbatim() {
        let server = [FixtureSession.server("a")]
        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: [],
            pendingDraftIDs: []
        )
        XCTAssertEqual(merged, server)
    }

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

    func testNonDraftRowMatchingPendingIDIsNotPreserved() {
        // A non-draft row whose id happens to be in pendingDraftIDs must NOT
        // be kept: only rows with isLocalDraft are eligible. This is the
        // regression guard for the Kotlin filter's first conjunct.
        let server = [FixtureSession.server("a")]
        let staleLiveRow = FixtureSession.server("ghost")
        let current = [staleLiveRow]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["ghost"]
        )

        XCTAssertEqual(merged, server)
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

    func testDraftNoLongerPendingIsDropped() {
        // A draft that left the pending set (e.g. promotion failed and the
        // client dropped it) disappears even if the server lacks it.
        let server = [FixtureSession.server("a")]
        let current = [FixtureSession.draft("draft-2")]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1"]
        )

        XCTAssertEqual(merged, server)
    }

    // MARK: Rule 3 — no surviving drafts returns the server list verbatim

    func testDraftsRepresentedOnServerAreFilteredOut() {
        let server = [
            FixtureSession.server("draft-1"),
            FixtureSession.server("draft-2"),
            FixtureSession.server("a"),
        ]
        let current = [
            FixtureSession.draft("draft-1"),
            FixtureSession.draft("draft-2"),
        ]

        // Both drafts were already represented on the server → nothing to
        // prepend, so the exact server array comes back.
        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1", "draft-2"]
        )

        XCTAssertEqual(merged, server)
    }

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

    func testServerListOrderIsNeverReordered() {
        // The server list is authoritative: refresh semantics replace, not
        // reorder or interleave — rows the user is looking at on the server
        // side keep their positions relative to each other.
        let server = [
            FixtureSession.server("z"),
            FixtureSession.server("m"),
            FixtureSession.server("a"),
        ]
        let current = [
            FixtureSession.server("a"),
            FixtureSession.server("m"),
            FixtureSession.server("z"),
        ]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: []
        )

        XCTAssertEqual(merged.map(\.id), ["z", "m", "a"])
    }

    // MARK: Duplicates inside a single list (faithful to the Kotlin filter)

    func testDuplicateDraftOccurrencesAreEachPreserved() {
        // The Kotlin implementation filters rather than dedupes, so two
        // occurrences of the same pending draft both survive. Locked here so
        // any future dedupe change is a deliberate one.
        let server = [FixtureSession.server("a")]
        let current = [
            FixtureSession.draft("draft-1"),
            FixtureSession.draft("draft-1"),
        ]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1"]
        )

        XCTAssertEqual(
            merged.map(\.id),
            ["draft-1", "draft-1", "a"]
        )
    }

    func testDuplicatesInsideServerPagePassThroughUnchanged() {
        // No dedupe of the incoming server page either: whatever the server
        // sent back is rendered as-is.
        let server = [
            FixtureSession.server("a"),
            FixtureSession.server("a"),
            FixtureSession.server("b"),
        ]

        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: [],
            pendingDraftIDs: ["draft-1"]
        )

        XCTAssertEqual(merged, server)
    }

    // MARK: Empty inputs

    func testEverythingEmptyYieldsEmptyList() {
        let empty: [FixtureSession] = []
        let merged = SessionListMerge.merged(
            serverSessions: empty,
            currentSessions: empty,
            pendingDraftIDs: []
        )
        XCTAssertTrue(merged.isEmpty)
    }

    func testEmptyServerListKeepsPendingDraftsOnly() {
        // Server returned nothing (e.g. empty first page): pending drafts are
        // still preserved so an open draft route does not degrade.
        let draftA = FixtureSession.draft("draft-a")
        let draftB = FixtureSession.draft("draft-b")
        let current = [draftA, FixtureSession.server("old"), draftB]

        let merged = SessionListMerge.merged(
            serverSessions: [],
            currentSessions: current,
            pendingDraftIDs: ["draft-a", "draft-b"]
        )

        XCTAssertEqual(merged, [draftA, draftB])
    }

    func testEmptyCurrentWithPendingDraftsYieldsServerListVerbatim() {
        // Fresh install / cleared state: nothing local to preserve.
        let server = [FixtureSession.server("a")]
        let merged = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: [],
            pendingDraftIDs: ["draft-1"]
        )
        XCTAssertEqual(merged, server)
    }

    // MARK: Purity / determinism

    func testMergeIsDeterministicAcrossRepeatedCalls() {
        let server = [FixtureSession.server("a"), FixtureSession.server("b")]
        let current = [FixtureSession.draft("draft-1"), FixtureSession.server("a")]

        let first = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1"]
        )
        let second = SessionListMerge.merged(
            serverSessions: server,
            currentSessions: current,
            pendingDraftIDs: ["draft-1"]
        )

        XCTAssertEqual(first, second)
        // Inputs are never mutated by the merge.
        XCTAssertEqual(server.map(\.id), ["a", "b"])
        XCTAssertEqual(current.map(\.id), ["draft-1", "a"])
    }
}
