import Foundation
import MercuryCore
import XCTest
@testable import Mercury

/// Cross-platform adapter parity against the canonical shared corpus.
///
/// The JSON is sourced from shared/mercury-core test resources. These tests
/// deliberately use MercuryCore's exported framework decoder and reducer
/// facade, not a Swift-only reimplementation.
final class AdapterParityFixtureTests: XCTestCase {
    private typealias Object = [String: Any]

    private func corpus() throws -> Object {
        let bundle = Bundle(for: Self.self)
        let url = bundle.url(
            forResource: "chat-event-corpus",
            withExtension: "json",
            subdirectory: "adapter-parity"
        ) ?? bundle.url(forResource: "chat-event-corpus", withExtension: "json")
        let fixtureURL = try XCTUnwrap(url, "missing canonical adapter parity corpus")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL))
        return try XCTUnwrap(object as? Object)
    }

    private func object(_ value: Any?, _ label: String) throws -> Object {
        try XCTUnwrap(value as? Object, "expected object for \(label)")
    }

    private func array(_ value: Any?, _ label: String) throws -> [Any] {
        try XCTUnwrap(value as? [Any], "expected array for \(label)")
    }

    private func eventRecords(from corpus: Object) throws -> [Object] {
        try array(corpus["events"], "events").map { try object($0, "event") }
    }

    private func eventRecord(id: String, in records: [Object]) throws -> Object {
        try XCTUnwrap(records.first { $0["id"] as? String == id }, "missing fixture event \(id)")
    }

    private func decodedSharedEvent(_ record: Object, defaultSessionID: String) throws -> MercuryCore.ChatEvent? {
        let type = try XCTUnwrap(record["type"] as? String)
        let sessionID = (record["session_id"] as? String) ?? defaultSessionID
        let payloadJSON: String
        if let encoded = record["payload_json"] as? String {
            payloadJSON = encoded
        } else {
            let payload = try XCTUnwrap(record["payload"])
            payloadJSON = String(
                data: try JSONSerialization.data(withJSONObject: payload),
                encoding: .utf8
            )!
        }
        return MercuryCore.ChatEventDecoder.shared.decode(
            type: type,
            sessionId: sessionID,
            payloadJson: payloadJSON
        )
    }

    func testCanonicalOriginsMatchAtNativeBoundary() throws {
        let fixture = try corpus()
        let records = try array(fixture["origins"], "origins")
        XCTAssertFalse(records.isEmpty)
        for record in records {
            let value = try object(record, "origin")
            let input = try XCTUnwrap(value["input"] as? String)
            XCTAssertEqual(ServerOrigin.normalize(input), value["expected"] as? String, input)
        }
    }

    func testCanonicalEventsDecodeAndCrossNativeBoundary() throws {
        let fixture = try corpus()
        let sessionID = try XCTUnwrap(fixture["session_id"] as? String)
        let records = try eventRecords(from: fixture)
        XCTAssertFalse(records.isEmpty)

        for record in records {
            let shared = try XCTUnwrap(decodedSharedEvent(record, defaultSessionID: sessionID))
            let native = try XCTUnwrap(ChatEvent(shared: shared), "Swift adapter rejected \(record["id"] ?? "unknown")")
            // TranscriptState.apply converts the Swift enum back into the
            // exported shared event type before invoking MercuryCore.reducer.
            var state = TranscriptState()
            state.apply(native)
        }
    }

    func testSessionInfoFastModeMetadataPreservesTrueFalseAndAbsent() throws {
        let fixture = try corpus()
        let sessionID = try XCTUnwrap(fixture["session_id"] as? String)
        let records = try eventRecords(from: fixture).filter { $0["type"] as? String == "session.info" }
        XCTAssertEqual(records.count, 3)

        for record in records {
            let shared = try XCTUnwrap(decodedSharedEvent(record, defaultSessionID: sessionID))
            guard case let .sessionInfo(
                _, storedSessionID, model, provider, reasoningEffort, fastMode, title, running
            ) = try XCTUnwrap(ChatEvent(shared: shared)) else {
                return XCTFail("expected session.info fixture")
            }
            let expected = try object(record["expected"], "session.info expected")
            XCTAssertEqual(expected["fast_mode"] as? Bool, fastMode, record["id"] as? String ?? "session.info")
            if expected.keys.contains("stored_session_id") { XCTAssertEqual(expected["stored_session_id"] as? String, storedSessionID) }
            if expected.keys.contains("model") { XCTAssertEqual(expected["model"] as? String, model) }
            if expected.keys.contains("provider") { XCTAssertEqual(expected["provider"] as? String, provider) }
            if expected.keys.contains("reasoning_effort") { XCTAssertEqual(expected["reasoning_effort"] as? String, reasoningEffort) }
            if expected.keys.contains("title") { XCTAssertEqual(expected["title"] as? String, title) }
            if expected.keys.contains("running") { XCTAssertEqual(expected["running"] as? Bool, running) }
        }
    }

    func testMalformedAndUnknownEventsAreIgnoredByExportedDecoder() throws {
        let fixture = try corpus()
        let sessionID = try XCTUnwrap(fixture["session_id"] as? String)
        for raw in try array(fixture["malformed_events"], "malformed_events") {
            let record = try object(raw, "malformed event")
            XCTAssertNil(
                try decodedSharedEvent(record, defaultSessionID: sessionID),
                record["id"] as? String ?? "malformed event"
            )
        }
    }

    func testRepresentativeReducerUsesSwiftAdapterAndSharedFrameworkReducer() throws {
        let fixture = try corpus()
        let sessionID = try XCTUnwrap(fixture["session_id"] as? String)
        let records = try eventRecords(from: fixture)
        let reducer = try object(fixture["reducer"], "reducer")
        let eventIDs = try array(reducer["event_ids"], "reducer.event_ids")
        var state = TranscriptState()

        for rawID in eventIDs {
            let id = try XCTUnwrap(rawID as? String)
            let record = try eventRecord(id: id, in: records)
            let shared = try XCTUnwrap(decodedSharedEvent(record, defaultSessionID: sessionID))
            state.apply(try XCTUnwrap(ChatEvent(shared: shared)))
        }

        let expected = try object(reducer["expected"], "reducer.expected")
        let expectedRows = try array(expected["rows"], "reducer.expected.rows")
        XCTAssertEqual(state.rows.count, expectedRows.count)
        for (row, rawExpected) in zip(state.rows, expectedRows) {
            let value = try object(rawExpected, "row")
            XCTAssertEqual(row.role, value["role"] as? String)
            XCTAssertEqual(row.text, value["text"] as? String)
            XCTAssertEqual(row.completed, value["completed"] as? Bool)
            XCTAssertEqual(row.reasoningText, (value["reasoning_text"] as? String) ?? "")
        }

        let expectedTools = try array(expected["tools"], "reducer.expected.tools")
        XCTAssertEqual(state.tools.count, expectedTools.count)
        for (tool, rawExpected) in zip(state.tools, expectedTools) {
            let value = try object(rawExpected, "tool")
            XCTAssertEqual(tool.toolID, value["tool_id"] as? String)
            XCTAssertEqual(tool.name, value["name"] as? String)
            XCTAssertEqual(tool.context, value["context"] as? String)
            XCTAssertEqual(tool.summary, value["summary"] as? String)
            XCTAssertEqual(tool.state, .completed)
        }
        XCTAssertEqual(state.latestStatusText, expected["latest_status_text"] as? String)
        XCTAssertEqual(state.statusUpdateCount, expected["status_update_count"] as? Int)
        XCTAssertEqual(state.generatingStatusText, expected["generating_status_text"] as? String)
        XCTAssertEqual(state.lastError, expected["last_error"] as? String)
    }

    func testUsageAndContextCorpusRunsThroughSwiftChatConnection() async throws {
        let fixture = try corpus()
        let sessionID = try XCTUnwrap(fixture["session_id"] as? String)
        let insights = try object(fixture["insights"], "insights")
        let usageFixture = try object(insights["usage"], "usage")
        let contextFixture = try object(insights["context"], "context")
        let usageResult = try object(usageFixture["result"], "usage.result")
        let contextResult = try object(contextFixture["result"], "context.result")

        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { text in
            guard let request = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? Object,
                  let method = request["method"] as? String,
                  let id = request["id"] else { return nil }
            let result = method == "session.usage" ? usageResult : contextResult
            let response: Object = ["jsonrpc": "2.0", "id": id, "result": result]
            return try? String(data: JSONSerialization.data(withJSONObject: response), encoding: .utf8)
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let usage = try await connection.loadSessionUsage(runtimeSessionID: sessionID)
        let context = try await connection.loadContextBreakdown(runtimeSessionID: sessionID)
        let expectedUsage = try object(usageFixture["expected"], "usage.expected")
        let expectedContext = try object(contextFixture["expected"], "context.expected")

        XCTAssertEqual(usage.inputTokens, expectedUsage["input_tokens"] as? Int64)
        XCTAssertEqual(usage.outputTokens, expectedUsage["output_tokens"] as? Int64)
        XCTAssertEqual(usage.totalTokens, expectedUsage["total_tokens"] as? Int64)
        XCTAssertEqual(usage.contextUsedTokens, expectedUsage["context_used_tokens"] as? Int64)
        XCTAssertEqual(usage.contextMaxTokens, expectedUsage["context_max_tokens"] as? Int64)
        XCTAssertEqual(usage.contextPercent, expectedUsage["context_percent"] as? Double)
        XCTAssertEqual(usage.calls, expectedUsage["calls"] as? Int64)
        XCTAssertEqual(usage.creditsLines, expectedUsage["credits_lines"] as? [String])
        XCTAssertEqual(usage.rawInfo, expectedUsage["raw_info"] as? String)
        let expectedCategories = try array(expectedContext["categories"], "context.expected.categories")
        XCTAssertEqual(context.categories.count, expectedCategories.count)
        for (category, rawExpected) in zip(context.categories, expectedCategories) {
            let value = try object(rawExpected, "context category")
            XCTAssertEqual(category.name, value["name"] as? String)
            XCTAssertEqual(category.tokens, value["tokens"] as? Int64)
            XCTAssertEqual(category.percent, value["percent"] as? Double)
        }
        XCTAssertEqual(context.usedTokens, expectedContext["used_tokens"] as? Int64)
        XCTAssertEqual(context.maxTokens, expectedContext["max_tokens"] as? Int64)
        XCTAssertEqual(context.percent, expectedContext["percent"] as? Double)
        await connection.close()
    }
}
