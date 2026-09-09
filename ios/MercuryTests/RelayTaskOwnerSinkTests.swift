import XCTest
import MercuryCore
@testable import Mercury

/// A pooled relay reader keeps applying child evidence after the chat screen
/// closes. The owner must publish every rewrite so Home never shows a row the
/// host already finished.
final class RelayTaskOwnerSinkTests: XCTestCase {
    private func snapshot(live: Bool = true) -> MercuryCore.RelayLeaseSnapshot {
        MercuryCore.RelayLeaseSnapshot(
            leaseId: "lease", lastSeq: 0, gap: false, reset: false,
            bindings: [MercuryCore.RelayLeaseBinding(runtimeId: "runtime", durableId: "durable",
                                                     profile: "default", live: live)],
            tasks: [], truncated: false, routingToken: nil
        )
    }
    private func evidence(_ type: String, status: String? = nil) -> BackgroundTaskEvidence {
        var payload: [String: Any] = ["subagent_id": "child", "goal": "Write a poem"]
        if let status { payload["status"] = status }
        return BackgroundTaskEvidence.decode(type: type, payload: payload)!
    }

    func testCompletionAppliedWithoutSubscriberIsPublished() {
        let owner = RelayTaskOwner()
        let published = Locked<[(String, Mercury.BackgroundTasks)]>([])
        owner.setSink { durable, state in published.mutate { $0.append((durable, state)) } }
        let generation = owner.attach(snapshot: snapshot(), profile: "default")
        XCTAssertTrue(owner.apply(generation: generation, runtime: "runtime", evidence: evidence("subagent.tool")))
        XCTAssertTrue(owner.apply(generation: generation, runtime: "runtime",
                                  evidence: evidence("subagent.complete", status: "completed")))
        let events = published.value
        XCTAssertEqual(events.map { $0.0 }, ["durable", "durable"])
        XCTAssertEqual(events.first?.1.rows.first?.status, .active)
        XCTAssertEqual(events.last?.1.rows.first?.status, .finished)
        XCTAssertEqual(events.last?.1, owner.retained(generation: generation, durable: "durable", runtime: "runtime"))
    }

    func testRejectedOrUnchangedEvidenceIsNotPublished() {
        let owner = RelayTaskOwner()
        let count = Locked(0)
        owner.setSink { _, _ in count.mutate { $0 += 1 } }
        let generation = owner.attach(snapshot: snapshot(live: false), profile: "default")
        XCTAssertFalse(owner.apply(generation: generation, runtime: "runtime", evidence: evidence("subagent.tool")))
        XCTAssertFalse(owner.apply(generation: UUID(), runtime: "runtime", evidence: evidence("subagent.tool")))
        XCTAssertEqual(count.value, 0)
        owner.bind(generation: generation, runtime: "runtime", durable: "durable", profile: "default")
        XCTAssertTrue(owner.apply(generation: generation, runtime: "runtime", evidence: evidence("subagent.tool")))
        XCTAssertEqual(count.value, 1)
        // A "running" confirmation is fresh liveness and is published; a poll
        // that changes nothing is silent.
        _ = owner.reconcile(generation: generation, durable: "durable", runtime: "runtime", statuses: ["child": "running"])
        XCTAssertEqual(count.value, 2)
        _ = owner.reconcile(generation: generation, durable: "durable", runtime: "runtime", statuses: [:])
        XCTAssertEqual(count.value, 2)
        _ = owner.reconcile(generation: generation, durable: "durable", runtime: "runtime", statuses: ["child": "exited"])
        XCTAssertEqual(count.value, 3)
    }

    @MainActor
    func testBackgroundTaskScopeMatchesChatAndSink() {
        let target = RelayPairedTarget(
            id: UUID(), label: "study", relayOrigin: "https://relay.example.com",
            installationID: Data(), hostPublicKey: Data(), deviceID: "device",
            deviceStaticPrivateKey: Data(), fingerprint: "f", status: .approved,
            createdAtEpochSeconds: 1, lastUsedEpochSeconds: nil
        )
        let relay = AppModel.backgroundTaskScope(relayTarget: target, serverOrigin: "https://direct",
                                                 profile: "default", durable: "d1")
        XCTAssertEqual(relay, "relay:https://relay.example.com|\(target.id)|default|d1")
        let direct = AppModel.backgroundTaskScope(relayTarget: nil, serverOrigin: "https://direct",
                                                  profile: "default", durable: "d1")
        XCTAssertEqual(direct, "direct:https://direct|default|d1")
    }
}

private final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value
    init(_ value: Value) { stored = value }
    var value: Value { lock.lock(); defer { lock.unlock() }; return stored }
    func mutate(_ body: (inout Value) -> Void) { lock.lock(); body(&stored); lock.unlock() }
}
