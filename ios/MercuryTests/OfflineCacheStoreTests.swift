import XCTest
@testable import Mercury

final class OfflineCacheStoreTests: XCTestCase {
    func testOriginProfileAndDurableIDIsolation() async throws {
        let backend = MemoryOfflineCacheBackend()
        let store = OfflineCacheStore(backend: backend, cipher: TestCacheCipher(), clock: { 11 })
        let first = try OfflineCacheScope(origin: "https://one.example", profile: "default")
        let otherOrigin = try OfflineCacheScope(origin: "https://two.example", profile: "default")
        let otherProfile = try OfflineCacheScope(origin: "https://one.example", profile: "work")

        try await store.writeMetadata(scope: first, sessions: [row("same", title: "first")], now: 10)
        try await store.writeMetadata(scope: otherOrigin, sessions: [row("same", title: "other")], now: 10)
        try await store.writeMetadata(scope: otherProfile, sessions: [row("same", title: "profile")], now: 10)

        let firstSnapshot = try await store.read(scope: first, now: 11)
        let otherOriginSnapshot = try await store.read(scope: otherOrigin, now: 11)
        let otherProfileSnapshot = try await store.read(scope: otherProfile, now: 11)
        XCTAssertEqual(firstSnapshot.sessions.onlyCachedElement?.summary.title, "first")
        XCTAssertEqual(otherOriginSnapshot.sessions.onlyCachedElement?.summary.title, "other")
        XCTAssertEqual(otherProfileSnapshot.sessions.onlyCachedElement?.summary.title, "profile")
        XCTAssertEqual(backend.keys.count, 3)
    }

    func testTranscriptTailsAreOptInAndBoundedToNewestMessages() async throws {
        let store = OfflineCacheStore(backend: MemoryOfflineCacheBackend(), cipher: TestCacheCipher(), clock: { 11 })
        let scope = try OfflineCacheScope(origin: "one.example", profile: "default")
        let messages = (0..<(OfflineCachePolicy.maxMessagesPerSession + 5)).map {
            OfflineCachedMessage(role: .assistant, text: "message-\($0)-" + String(repeating: "x", count: OfflineCachePolicy.maxBodyBytes))
        }

        try await store.writeTranscript(scope: scope, summary: row("session"), messages: messages, now: 10)
        let disabledSnapshot = try await store.read(scope: scope, now: 11)
        XCTAssertTrue(disabledSnapshot.sessions.isEmpty)

        try await store.setTranscriptCachingEnabled(true)
        try await store.writeTranscript(scope: scope, summary: row("session"), messages: messages, now: 12)
        let enabledSnapshot = try await store.read(scope: scope, now: 13)
        let retained = try XCTUnwrap(enabledSnapshot.sessions.onlyCachedElement).messages
        XCTAssertLessThanOrEqual(retained.count, OfflineCachePolicy.maxMessagesPerSession)
        XCTAssertTrue(retained.allSatisfy { $0.text.utf8.count <= OfflineCachePolicy.maxBodyBytes })
        XCTAssertTrue(retained.last?.text.hasPrefix("message-\(messages.count - 1)-") == true)
    }

    func testCorruptAndExpiredRowsAreDeletedWithoutFailingRead() async throws {
        let backend = MemoryOfflineCacheBackend()
        let store = OfflineCacheStore(backend: backend, cipher: TestCacheCipher(), clock: { 11 })
        let scope = try OfflineCacheScope(origin: "one.example", profile: "default")
        try await store.writeMetadata(scope: scope, sessions: [row("expired")], now: 1)
        backend.storage["row-corrupt"] = Data("not-an-envelope".utf8)

        let snapshot = try await store.read(scope: scope, now: OfflineCachePolicy.retentionSeconds + 2)
        XCTAssertTrue(snapshot.sessions.isEmpty)
        XCTAssertTrue(backend.storage.isEmpty)
    }

    func testRowsAndStoredBytesAreGloballyBounded() async throws {
        let backend = MemoryOfflineCacheBackend()
        let store = OfflineCacheStore(backend: backend, cipher: TestCacheCipher(), clock: { 11 })
        let scope = try OfflineCacheScope(origin: "one.example", profile: "default")
        let rows = (0..<(OfflineCachePolicy.maxSessionCount + 20)).map {
            row("session-\($0)", title: String(repeating: "t", count: OfflineCachePolicy.maxTextBytes * 2))
        }

        try await store.writeMetadata(scope: scope, sessions: rows, now: 100)
        XCTAssertLessThanOrEqual(backend.keys.count, OfflineCachePolicy.maxSessionCount)
        XCTAssertLessThanOrEqual(backend.totalBytes, OfflineCachePolicy.maxTotalBytes)
    }

    func testDisablingAndLogoutClearTranscriptBodiesWithoutCrossOriginLeak() async throws {
        let backend = MemoryOfflineCacheBackend()
        let store = OfflineCacheStore(backend: backend, cipher: TestCacheCipher(), clock: { 11 })
        let first = try OfflineCacheScope(origin: "one.example", profile: "default")
        let work = try OfflineCacheScope(origin: "one.example", profile: "work")
        let other = try OfflineCacheScope(origin: "two.example", profile: "default")
        try await store.setTranscriptCachingEnabled(true)
        for (scope, id) in [(first, "one"), (work, "work"), (other, "other")] {
            try await store.writeTranscript(
                scope: scope,
                summary: row(id),
                messages: [.init(role: .user, text: "private")],
                now: 10
            )
        }

        try await store.clearForLogout(origin: "https://one.example")
        let firstAfterLogout = try await store.read(scope: first, now: 11)
        let workAfterLogout = try await store.read(scope: work, now: 11)
        let otherAfterLogout = try await store.read(scope: other, now: 11)
        XCTAssertTrue(firstAfterLogout.sessions.isEmpty)
        XCTAssertTrue(workAfterLogout.sessions.isEmpty)
        XCTAssertEqual(otherAfterLogout.sessions.onlyCachedElement?.messages.count, 1)

        try await store.setTranscriptCachingEnabled(false)
        let otherAfterDisable = try await store.read(scope: other, now: 11)
        XCTAssertEqual(otherAfterDisable.sessions.onlyCachedElement?.messages.count, 0)
    }

    func testCachedFirstStateRejectsStaleGenerationAfterLiveReconcile() throws {
        let first = try OfflineCacheScope(origin: "one.example", profile: "default")
        let second = try OfflineCacheScope(origin: "two.example", profile: "default")
        var state = CachedFirstSessionState()
        let stale = state.begin(scope: first)
        let current = state.begin(scope: second)

        XCTAssertFalse(state.applyCached([row("stale")], for: stale))
        XCTAssertTrue(state.applyCached([row("cached")], for: current))
        XCTAssertTrue(state.applyLive([row("live")], for: current))
        XCTAssertFalse(state.applyCached([row("late-cache")], for: current))
        XCTAssertEqual(state.sessions.map(\.id), ["live"])
        XCTAssertEqual(state.source, .live)
    }

    private func row(_ id: String, title: String? = nil) -> SessionRow {
        SessionRow(id: id, title: title ?? id, preview: "preview", profile: "default")
    }
}

private final class MemoryOfflineCacheBackend: OfflineCacheBacking, @unchecked Sendable {
    var storage: [String: Data] = [:]
    var enabled = false
    var keys: [String] { Array(storage.keys) }
    var totalBytes: Int { storage.values.reduce(0) { $0 + $1.count } }

    func listRowKeys(limit: Int) throws -> [String] { Array(storage.keys.sorted().prefix(limit)) }
    func readRow(key: String) throws -> Data? { storage[key] }
    func writeRow(_ data: Data, key: String) throws { storage[key] = data }
    func deleteRow(key: String) throws { storage.removeValue(forKey: key) }
    func readTranscriptCachingEnabled() -> Bool { enabled }
    func writeTranscriptCachingEnabled(_ enabled: Bool) throws { self.enabled = enabled }
}

private struct TestCacheCipher: OfflineCacheCrypting {
    func seal(_ plaintext: Data, authenticating associatedData: Data) throws -> Data {
        Data([UInt8(associatedData.count)]) + associatedData + plaintext
    }

    func open(_ ciphertext: Data, authenticating associatedData: Data) throws -> Data {
        guard let count = ciphertext.first.map(Int.init),
              ciphertext.count >= count + 1,
              Data(ciphertext.dropFirst().prefix(count)) == associatedData
        else { throw OfflineCacheError.corruptRow }
        return Data(ciphertext.dropFirst(count + 1))
    }
}

private extension Array {
    var onlyCachedElement: Element? { count == 1 ? first : nil }
}
