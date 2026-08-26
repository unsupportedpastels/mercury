import XCTest
@testable import Mercury

final class SessionInboxModelsTests: XCTestCase {
    func testActivityTrackerMarksFinishedOffscreenSessionUnread() {
        var tracker = SessionInboxActivityTracker()
        tracker.apply([
            ActiveSessionRuntime(
                runtimeSessionID: "runtime-1",
                durableSessionID: "stored-1",
                title: "Work",
                status: .working
            )
        ])
        XCTAssertEqual(tracker.indicator(for: "stored-1"), .running)

        tracker.apply([])
        XCTAssertEqual(tracker.indicator(for: "stored-1"), .completedUnread)

        tracker.markRead("stored-1")
        XCTAssertEqual(tracker.indicator(for: "stored-1"), .idle)
    }

    func testVisibleSessionDoesNotBecomeUnreadWhenItFinishes() {
        var tracker = SessionInboxActivityTracker()
        tracker.setVisibleSession("stored-1")
        tracker.apply([
            ActiveSessionRuntime(
                runtimeSessionID: "runtime-1",
                durableSessionID: "stored-1",
                title: "Work",
                status: .waiting
            )
        ])
        tracker.apply([])
        XCTAssertEqual(tracker.indicator(for: "stored-1"), .idle)
    }

    func testStartingWaitingAndWorkingAreAllRunning() {
        for status in [ActiveSessionStatus.starting, .waiting, .working] {
            var tracker = SessionInboxActivityTracker()
            tracker.apply([
                ActiveSessionRuntime(
                    runtimeSessionID: "runtime",
                    durableSessionID: "stored",
                    title: "Work",
                    status: status
                )
            ])
            XCTAssertEqual(tracker.indicator(for: "stored"), .running)
        }
    }

    func testProjectLabelUsesAuthoritativePreviewThenLongestWorkspaceAncestor() {
        let direct = ProjectSummary(
            id: ProjectID("direct"),
            label: "Direct",
            path: "/work",
            previewSessions: [ProjectSession(id: "s1", projectID: ProjectID("direct"))]
        )
        let parent = ProjectSummary(id: ProjectID("parent"), label: "Parent", path: "/work")
        let nested = ProjectSummary(id: ProjectID("nested"), label: "Nested", path: "/work/app")

        XCTAssertEqual(
            SessionInboxPolicy.projectLabel(
                for: SessionRow(id: "s1", workspacePath: "/elsewhere"),
                projects: [parent, nested, direct]
            ),
            "Direct"
        )
        XCTAssertEqual(
            SessionInboxPolicy.projectLabel(
                for: SessionRow(id: "s2", workspacePath: "/work/app/src"),
                projects: [parent, nested]
            ),
            "Nested"
        )
    }

    func testMetadataUsesModelAndPluralizedMessageCount() {
        XCTAssertEqual(SessionInboxPolicy.metadata(model: "sol", messageCount: 1), "sol · 1 message")
        XCTAssertEqual(SessionInboxPolicy.metadata(model: "sol", messageCount: 12), "sol · 12 messages")
        XCTAssertEqual(SessionInboxPolicy.metadata(model: nil, messageCount: 0), "0 messages")
    }
}
