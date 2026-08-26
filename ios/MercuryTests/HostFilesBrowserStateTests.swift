import XCTest
@testable import Mercury

final class HostFilesBrowserStateTests: XCTestCase {
    func testNewerLoadWinsWhenResponsesCompleteOutOfOrder() {
        var state = HostFilesBrowserState()
        let first = state.beginLoad(scope: "https://one.example|default", path: "/old")
        let second = state.beginLoad(scope: "https://one.example|default", path: "/new")

        XCTAssertTrue(state.apply(listing(path: "/new"), for: second))
        XCTAssertFalse(state.apply(listing(path: "/old"), for: first))
        XCTAssertEqual(state.listing?.path, "/new")
        XCTAssertFalse(state.isLoading)
    }

    func testScopeChangeClearsRowsAndRejectsPriorScopeResponse() {
        var state = HostFilesBrowserState()
        let old = state.beginLoad(scope: "https://one.example|default", path: "/one")
        XCTAssertTrue(state.apply(listing(path: "/one"), for: old))

        let current = state.beginLoad(scope: "https://two.example|work", path: nil)
        XCTAssertNil(state.listing)
        XCTAssertTrue(state.isLoading)
        XCTAssertFalse(state.apply(listing(path: "/stale"), for: old))
        XCTAssertTrue(state.apply(listing(path: "/two"), for: current))
        XCTAssertEqual(state.listing?.path, "/two")
    }

    func testStaleFailureCannotReplaceCurrentSuccess() {
        var state = HostFilesBrowserState()
        let stale = state.beginLoad(scope: "scope", path: "/one")
        let current = state.beginLoad(scope: "scope", path: "/two")

        XCTAssertTrue(state.apply(listing(path: "/two"), for: current))
        XCTAssertFalse(state.fail("stale failure", for: stale))
        XCTAssertNil(state.errorMessage)
        XCTAssertEqual(state.listing?.path, "/two")
    }

    func testFilterIsCaseInsensitiveAndPreservesServerOrder() {
        var state = HostFilesBrowserState()
        let request = state.beginLoad(scope: "scope", path: "/root")
        let rows = [
            HostFileEntry(name: "Sources", path: "/root/Sources", isDirectory: true),
            HostFileEntry(name: "README.md", path: "/root/README.md", isDirectory: false),
            HostFileEntry(name: "notes.txt", path: "/root/notes.txt", isDirectory: false),
        ]
        XCTAssertTrue(state.apply(HostFileListing(path: "/root", entries: rows), for: request))

        state.filter = "reAD"
        XCTAssertEqual(state.visibleEntries.map(\.name), ["README.md"])
        state.filter = ""
        XCTAssertEqual(state.visibleEntries.map(\.name), rows.map(\.name))
    }

    private func listing(path: String) -> HostFileListing {
        HostFileListing(path: path, entries: [])
    }
}
