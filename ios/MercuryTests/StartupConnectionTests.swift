import Foundation
import XCTest
@testable import Mercury

final class StartupConnectionTests: XCTestCase {
    func testChoiceStoreRoundTripsTypedDirectAndRelayIdentity() async throws {
        let persistence = MemoryStartupChoicePersistence()
        let store = StartupConnectionChoiceStore(persistence: persistence)
        let direct = StartupConnectionIdentity(kind: .direct, id: UUID(uuidString: "00000000-0000-0000-0000-000000000101")!)
        let relay = StartupConnectionIdentity(kind: .relay, id: UUID(uuidString: "00000000-0000-0000-0000-000000000202")!)

        try await store.save(direct)
        let restoredDirect = try await store.load()
        XCTAssertEqual(restoredDirect, direct)

        try await store.save(relay)
        let restoredRelay = try await store.load()
        XCTAssertEqual(restoredRelay, relay)
    }

    func testChoiceStoreContainsNoOriginOrRelayKeyMaterial() async throws {
        let persistence = MemoryStartupChoicePersistence()
        let store = StartupConnectionChoiceStore(persistence: persistence)
        let choice = StartupConnectionIdentity(
            kind: .direct,
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000303")!
        )

        try await store.save(choice)

        let raw = try XCTUnwrap(persistence.data)
        XCTAssertFalse(raw.contains(Data("https://hermes.example".utf8)))
        XCTAssertFalse(raw.contains(Data("private-key".utf8)))
        XCTAssertFalse(raw.contains(Data("relay-token".utf8)))
        XCTAssertTrue(raw.contains(Data("direct".utf8)))
    }

    func testValidSavedChoiceRestoresOnlyThatConfiguredTarget() {
        let direct = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .direct,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000401")!
            ),
            isUsable: true
        )
        let relay = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .relay,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000402")!
            ),
            isUsable: true
        )

        let decision = StartupConnectionDecisionPolicy.decide(
            candidates: [direct, relay],
            lastSuccessful: relay.identity
        )

        XCTAssertEqual(decision.action, .autoConnect)
        XCTAssertEqual(decision.selected, relay.identity)
        XCTAssertFalse(decision.savedChoiceUnavailable)
    }

    func testUnavailableSavedChoiceShowsPickerInsteadOfSilentlyConnectingAnotherHost() {
        let available = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .direct,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000501")!
            ),
            isUsable: true
        )
        let removed = StartupConnectionIdentity(
            kind: .relay,
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000599")!
        )

        let decision = StartupConnectionDecisionPolicy.decide(
            candidates: [available],
            lastSuccessful: removed
        )

        XCTAssertEqual(decision.action, .chooseTarget)
        XCTAssertNil(decision.selected)
        XCTAssertTrue(decision.savedChoiceUnavailable)
    }

    func testPendingRelayIsVisibleButNeverSelectedForAutostart() {
        let pending = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .relay,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000601")!
            ),
            isUsable: false
        )

        let decision = StartupConnectionDecisionPolicy.decide(
            candidates: [pending],
            lastSuccessful: nil
        )

        XCTAssertEqual(decision.action, .chooseTarget)
        XCTAssertNil(decision.selected)
    }

    func testMultipleConfiguredTargetsIncludingPendingRelayShowPicker() {
        let direct = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .direct,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000701")!
            ),
            isUsable: true
        )
        let pending = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .relay,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000702")!
            ),
            isUsable: false
        )

        let decision = StartupConnectionDecisionPolicy.decide(
            candidates: [direct, pending],
            lastSuccessful: nil
        )

        XCTAssertEqual(decision.action, .chooseTarget)
        XCTAssertNil(decision.selected)
        XCTAssertFalse(decision.savedChoiceUnavailable)
    }

    func testNoSavedChoiceWithOneUsableTargetAutoConnects() {
        let direct = StartupConnectionCandidate(
            identity: StartupConnectionIdentity(
                kind: .direct,
                id: UUID(uuidString: "00000000-0000-0000-0000-000000000703")!
            ),
            isUsable: true
        )

        let decision = StartupConnectionDecisionPolicy.decide(
            candidates: [direct],
            lastSuccessful: nil
        )

        XCTAssertEqual(decision.action, .autoConnect)
        XCTAssertEqual(decision.selected, direct.identity)
    }

    func testNoTargetsUsesFirstRunOnboarding() {
        let decision = StartupConnectionDecisionPolicy.decide(candidates: [], lastSuccessful: nil)

        XCTAssertEqual(decision.action, .onboarding)
        XCTAssertNil(decision.selected)
    }
}

final class MemoryStartupChoicePersistence: StartupConnectionChoicePersisting, @unchecked Sendable {
    var data: Data?

    func readStartupChoiceData() throws -> Data? { data }
    func writeStartupChoiceData(_ data: Data) throws { self.data = data }
    func clearStartupChoiceData() { data = nil }
}
