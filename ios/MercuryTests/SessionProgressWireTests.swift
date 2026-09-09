import Foundation
import XCTest
import MercuryCore
@testable import Mercury

final class SessionProgressWireTests: XCTestCase {
    func testToolCompletionCarriesAuthoritativeSnapshotAndRekeyPreservesIt() async throws {
        let frame = #"{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime","type":"tool.complete","payload":{"tool_id":"plan","name":"todo_list","result":"{\"revision\":7,\"todos\":[{\"id\":\"one\",\"content\":\"Read saved progress\",\"status\":\"completed\"}]}"}}}"#
        let socket = ConnectionTestSocket(frames: [.frame(frame)])
        let connection = try ChatConnection(socket: socket)
        let event = try await firstEvent(connection.start())
        guard case .toolComplete(_, _, _, _, let snapshot, let historical) = event else {
            await connection.close()
            return XCTFail("Expected tool.complete")
        }
        XCTAssertEqual(snapshot?.milestones.first?.content, "Read saved progress")
        XCTAssertEqual(snapshot?.revision?.int64Value, 7)
        XCTAssertFalse(historical)
        let rekeyed = event.withSessionID("durable")
        guard case .toolComplete(let id, _, _, _, let rekeyedSnapshot, _) = rekeyed else {
            await connection.close()
            return XCTFail("Expected rekeyed tool.complete")
        }
        XCTAssertEqual(id, "durable")
        XCTAssertEqual(rekeyedSnapshot, snapshot)
        await connection.close()
    }

    func testTodoStartArgumentsNeverBecomeMilestones() async throws {
        let frame = #"{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime","type":"tool.start","payload":{"tool_id":"plan","name":"todo_list","args":{"todos":[{"id":"one","content":"Not accepted yet","status":"completed"}]}}}}"#
        let socket = ConnectionTestSocket(frames: [.frame(frame)])
        let connection = try ChatConnection(socket: socket)
        let event = try await firstEvent(connection.start())
        let progress = MercuryCore.DurableProgressBridge.shared.initial().observe(
            observation: SessionProgressBridge.observation(event), atEpochMillis: 100)
        XCTAssertFalse(progress.hasMilestoneSnapshot)
        XCTAssertTrue(progress.milestones.isEmpty)
        XCTAssertEqual(progress.evidence.count, 1)
        await connection.close()
    }

    private func firstEvent(_ stream: AsyncStream<Mercury.ChatEvent>) async throws -> Mercury.ChatEvent {
        try await withThrowingTaskGroup(of: Mercury.ChatEvent?.self) { group in
            group.addTask { var iterator = stream.makeAsyncIterator(); return await iterator.next() }
            group.addTask { try await Task.sleep(for: .seconds(3)); throw URLError(.timedOut) }
            defer { group.cancelAll() }
            guard let event = try await group.next(), let event else { throw URLError(.badServerResponse) }
            return event
        }
    }
}
