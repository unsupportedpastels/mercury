import XCTest
@testable import Mercury

final class ServerCatalogTests: XCTestCase {
    func testMigratesNormalizedLegacyOriginOnceWithStableLocalID() async throws {
        let persistence = MemoryServerCatalogPersistence()
        let legacy = MemoryLegacyOrigin(value: " HTTPS://FIRST.example/ ")
        let fixedID = UUID(uuidString: "00000000-0000-0000-0000-000000000042")!
        let store = ServerCatalogStore(
            persistence: persistence,
            legacyOrigin: legacy,
            idGenerator: { fixedID },
            now: { Date(timeIntervalSince1970: 42) }
        )

        let migrated = try await store.load()
        XCTAssertEqual(migrated.activeEntry?.id, fixedID)
        XCTAssertEqual(migrated.activeEntry?.origin, "https://first.example")
        XCTAssertNil(legacy.value)

        let reloaded = try await ServerCatalogStore(
            persistence: persistence,
            legacyOrigin: legacy,
            idGenerator: { UUID() }
        ).load()
        XCTAssertEqual(reloaded.activeEntry?.id, fixedID)
    }

    func testDuplicateNormalizedOriginIsRejectedAndDoesNotReplaceID() async throws {
        let store = ServerCatalogStore(
            persistence: MemoryServerCatalogPersistence(),
            idGenerator: { UUID(uuidString: "00000000-0000-0000-0000-000000000001")! }
        )
        let first = try await store.add(origin: "https://one.example", label: "One")

        do {
            _ = try await store.add(origin: "HTTPS://ONE.example/", label: "Duplicate")
            XCTFail("Expected duplicate origin rejection")
        } catch let error as ServerCatalogError {
            XCTAssertEqual(error, .duplicateOrigin)
        }
        let catalog = try await store.load()
        XCTAssertEqual(catalog.entries.onlyElement?.id, first.id)
    }

    func testPersistedCatalogIsTolerantDeduplicatedAndBounded() async throws {
        let rows = (0..<12).map { index in
            PersistedServerCatalogFixture.Entry(
                id: UUID().uuidString,
                origin: "https://server-\(index).example/",
                label: " Server \(index) ",
                lastUsedEpochSeconds: index
            )
        } + [
            .init(id: UUID().uuidString, origin: "HTTPS://SERVER-1.example/", label: "Updated", lastUsedEpochSeconds: 100),
            .init(id: "not-a-uuid", origin: "not a url", label: "bad", lastUsedEpochSeconds: -1)
        ]
        let fixture = PersistedServerCatalogFixture(entries: rows, activeID: rows[1].id)
        let persistence = MemoryServerCatalogPersistence(data: try JSONEncoder().encode(fixture))

        let catalog = try await ServerCatalogStore(persistence: persistence).load()
        XCTAssertEqual(catalog.entries.count, ServerCatalogPolicy.maxEntries)
        XCTAssertEqual(Set(catalog.entries.map(\.origin)).count, catalog.entries.count)
        XCTAssertEqual(catalog.activeEntry?.origin, "https://server-1.example")
        XCTAssertEqual(catalog.activeEntry?.label, "Updated")
    }

    func testSelectionAndRemovalAreLocalIDScoped() async throws {
        var ids = [
            UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
        ].makeIterator()
        let store = ServerCatalogStore(
            persistence: MemoryServerCatalogPersistence(),
            idGenerator: { ids.next()! },
            now: { Date(timeIntervalSince1970: 99) }
        )
        let first = try await store.add(origin: "one.example", label: "One")
        let second = try await store.add(origin: "two.example", label: "Two")

        let removedFirst = try await store.remove(id: first.id)
        let removedSecond = try await store.remove(id: second.id)
        let catalog = try await store.load()
        XCTAssertTrue(removedFirst)
        XCTAssertFalse(removedSecond)
        XCTAssertEqual(catalog.activeEntry?.id, second.id)
    }
}

private final class MemoryServerCatalogPersistence: ServerCatalogPersisting, @unchecked Sendable {
    var data: Data?
    init(data: Data? = nil) { self.data = data }
    func readCatalogData() throws -> Data? { data }
    func writeCatalogData(_ data: Data) throws { self.data = data }
}

private final class MemoryLegacyOrigin: LegacyServerOriginPersisting, @unchecked Sendable {
    var value: String?
    init(value: String?) { self.value = value }
    func readLegacyOrigin() -> String? { value }
    func clearLegacyOrigin() { value = nil }
}

private struct PersistedServerCatalogFixture: Codable {
    struct Entry: Codable {
        let id: String
        let origin: String
        let label: String
        let lastUsedEpochSeconds: Int
    }
    let entries: [Entry]
    let activeID: String?
}

private extension Array {
    var onlyElement: Element? { count == 1 ? first : nil }
}
