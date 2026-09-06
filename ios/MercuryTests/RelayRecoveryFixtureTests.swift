import XCTest
import MercuryCore
@testable import Mercury

final class RelayRecoveryFixtureTests: XCTestCase {
    private func corpus() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "relay-recovery", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }
    private func json(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value, options: .sortedKeys), as: UTF8.self)
    }

    func testCanonicalRecoveryCorpusThroughExportedFramework() throws {
        let cases = try XCTUnwrap(corpus()["cases"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let name = try XCTUnwrap(item["name"] as? String)
            let engine = MercuryCore.RelayLeaseRecoveryEngine(profile: try XCTUnwrap(item["profile"] as? String))
            var delivered = 0
            var failed = false
            do {
                _ = try engine.initialize(raw: json(try XCTUnwrap(item["attached"])))
                for frame in try XCTUnwrap(item["frames"] as? [[String: Any]]) {
                    if let text = try engine.receive(raw: json(frame)) {
                        delivered += 1
                        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
                        let params = object["params"] as? [String: Any]
                        engine.acknowledge(eventId: params?["relay_event_id"] as? String)
                    }
                }
            } catch { failed = true }
            XCTAssertEqual(failed, item["expectedFailure"] as? Bool, name)
            if !failed {
                XCTAssertEqual(delivered, item["expectedDelivered"] as? Int, name)
                XCTAssertEqual(engine.acknowledgedCursor, (item["expectedCursor"] as? NSNumber)?.int64Value, name)
                XCTAssertEqual(engine.replayComplete, item["expectedReplayComplete"] as? Bool, name)
            }
        }
    }

    func testCanonicalTaskCorpusThroughActualSwiftAdapter() throws {
        let cases = try XCTUnwrap(corpus()["taskCases"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        let statuses: [String: Mercury.BackgroundTaskStatus] = [
            "Active": .active, "Finished": .finished, "Failed": .failed, "Stopped": .stopped, "Unknown": .unknown
        ]
        for item in cases {
            let name = try XCTUnwrap(item["name"] as? String)
            let profile = try XCTUnwrap(item["profile"] as? String)
            let engine = MercuryCore.RelayLeaseRecoveryEngine(profile: profile)
            let snapshot = try engine.initialize(raw: json(try XCTUnwrap(item["attached"])))
            var tasks = Mercury.BackgroundTasks()
            tasks.recover(snapshot: snapshot, durableID: try XCTUnwrap(item["durableId"] as? String),
                          profile: profile, runtime: item["runtime"] as? String)
            XCTAssertEqual(tasks.rows.count, item["expectedRows"] as? Int, name)
            XCTAssertEqual(tasks.rows.first?.status, (item["expectedStatus"] as? String).flatMap { statuses[$0] }, name)
            XCTAssertEqual(tasks.activeCount(now: 999_000), item["expectedActiveCount"] as? Int, name)
            if let row = tasks.rows.first {
                XCTAssertEqual(row.observedAtMillis, (item["expectedAge"] as? NSNumber)?.int64Value, name)
            }
            let once = tasks
            tasks.recover(snapshot: snapshot, durableID: try XCTUnwrap(item["durableId"] as? String),
                          profile: profile, runtime: item["runtime"] as? String)
            XCTAssertEqual(tasks, once, "Repeated snapshot must be idempotent: \(name)")
        }
    }

    func testOnlyOwningRuntimeCanAcknowledgeAppliedChildEvidence() {
        var tasks = Mercury.BackgroundTasks()
        let event = ChatEvent.backgroundTask(sessionID: "runtime-A", evidence: BackgroundTaskEvidence(
            kind: .complete, childID: "child", goal: "Synthetic", action: nil,
            terminalStatus: .finished, eventID: "lease:1", historical: true))
        XCTAssertNil(tasks.apply(event, runtime: "runtime-B", now: 1000))
        XCTAssertTrue(tasks.rows.isEmpty)
        XCTAssertEqual(tasks.apply(event, runtime: "runtime-A", now: 1000), "lease:1")
        XCTAssertEqual(tasks.rows.first?.status, .finished)
        XCTAssertEqual(tasks.apply(event, runtime: "runtime-A", now: 1000), "lease:1",
                       "Already-applied same-scope duplicates can be acknowledged")
        XCTAssertEqual(tasks.rows.count, 1)
    }

    func testCheckpointRejectsLateOldAttachmentCallbacksAndLeaseChanges() {
        let checkpoint = RelayRecoveryCheckpoint()
        let first = checkpoint.begin()
        checkpoint.bind(generation: first.generation, leaseID: "first")
        checkpoint.applied(generation: first.generation, leaseID: "first", sequence: 7)
        XCTAssertEqual(checkpoint.cursor, 7)
        let second = checkpoint.begin()
        XCTAssertEqual(second.cursor, 7)
        checkpoint.bind(generation: second.generation, leaseID: "replacement")
        checkpoint.applied(generation: first.generation, leaseID: "first", sequence: 999)
        XCTAssertEqual(checkpoint.cursor, 0)
        checkpoint.applied(generation: second.generation, leaseID: "replacement", sequence: 3)
        XCTAssertEqual(checkpoint.cursor, 3)
    }

    func testAuthoritativeIdleResumeFinishesUnmatchedToolRows() {
        var transcript = TranscriptState()
        transcript.ownSessionIDs = ["runtime"]
        transcript.apply(.toolStart(sessionID: "runtime", toolID: "tool", name: "terminal", context: nil))
        XCTAssertEqual(transcript.tools.first?.state, .running)
        transcript.finishStreamingAssistant()
        XCTAssertEqual(transcript.tools.first?.state, .completed)
    }
}
