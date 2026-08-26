import XCTest
@testable import Mercury

final class HomeInboxPolicyTests: XCTestCase {
    private func project(
        _ id: String,
        noProject: Bool = false,
        lastActive: TimeInterval? = nil
    ) -> ProjectSummary {
        ProjectSummary(
            id: ProjectID(id),
            label: id,
            path: noProject ? nil : "/workspaces/\(id)",
            isNoProject: noProject,
            lastActive: lastActive.map { Date(timeIntervalSince1970: $0) }
        )
    }

    func testSortedProjectsExcludesHomeBucketAndOrdersByRecency() {
        let projects = [
            project("stale", lastActive: 100),
            project("home", noProject: true),
            project("fresh", lastActive: 300),
            project("middle", lastActive: 200),
            project("never"),
        ]

        let sorted = HomeInboxPolicy.sortedProjects(projects, pinnedIDs: [])

        XCTAssertEqual(sorted.map(\.id.rawValue), ["fresh", "middle", "stale", "never"])
    }

    func testPinnedProjectsRankAboveMoreRecentUnpinnedOnes() {
        let projects = [
            project("fresh", lastActive: 300),
            project("pinnedOld", lastActive: 10),
            project("pinnedNew", lastActive: 20),
        ]

        let sorted = HomeInboxPolicy.sortedProjects(
            projects,
            pinnedIDs: [ProjectID("pinnedOld"), ProjectID("pinnedNew")]
        )

        XCTAssertEqual(sorted.map(\.id.rawValue), ["pinnedNew", "pinnedOld", "fresh"])
    }

    func testProjectPreviewBoundsToFourRows() {
        let projects = (0..<6).map { project("p\($0)", lastActive: TimeInterval(100 - $0)) }

        let preview = HomeInboxPolicy.projectPreview(projects, pinnedIDs: [])

        XCTAssertEqual(preview.map(\.id.rawValue), ["p0", "p1", "p2", "p3"])
    }

    func testRecentPreviewIsBoundedWithoutChangingServerOrder() {
        let sessions = (0..<14).map { SessionRow(id: "s\($0)", title: "Session \($0)") }

        let preview = HomeInboxPolicy.recentSessionPreview(sessions)

        XCTAssertEqual(preview.map(\.id), (0..<10).map { "s\($0)" })
    }

    func testPinStoreScopesByOriginAndProfileAndRoundTrips() {
        let defaults = UserDefaults(suiteName: "HomeInboxPolicyTests-\(UUID().uuidString)")!
        let store = ProjectPinStore(defaults: defaults)

        store.setPinned(true, id: ProjectID("p1"), origin: "https://a.example", profile: "default")
        store.setPinned(true, id: ProjectID("p2"), origin: "https://a.example", profile: "default")
        store.setPinned(false, id: ProjectID("p2"), origin: "https://a.example", profile: "default")

        XCTAssertEqual(
            store.pinnedIDs(origin: "https://a.example", profile: "default"),
            [ProjectID("p1")]
        )
        // Other origin/profile scopes stay empty.
        XCTAssertTrue(store.pinnedIDs(origin: "https://b.example", profile: "default").isEmpty)
        XCTAssertTrue(store.pinnedIDs(origin: "https://a.example", profile: "work").isEmpty)
    }
}
