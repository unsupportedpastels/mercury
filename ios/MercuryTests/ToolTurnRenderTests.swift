import XCTest
@testable import Mercury

/// Reproduces the exact event sequence of a vision/tool turn captured from
/// the live gateway (message.start → title/thinking/reasoning → tool.start/
/// complete → message.delta stream → message.complete) and asserts the
/// answer text lands in transcript rows. Mirrors the field shapes the
/// gateway emits (`server.py _stream`: payload {"text": delta}).
final class ToolTurnRenderTests: XCTestCase {

    /// Frame-level socket fake: hands scripted frames to ChatConnection.
    private final class ScriptedSocket: ChatSocketing, @unchecked Sendable {
        private var frames: [String]
        private let lock = NSLock()

        init(frames: [String]) {
            self.frames = frames
        }

        func sendText(_ text: String) async throws {}

        func receiveText() async throws -> String? {
            lock.lock()
            defer { lock.unlock() }
            guard !frames.isEmpty else { return nil }
            return frames.removeFirst()
        }

        func close() async {}
    }

    private func event(_ type: String, sid: String = "runtime-1", payload: [String: Any] = [:]) -> String {
        let object: [String: Any] = [
            "jsonrpc": "2.0",
            "method": "event",
            "params": ["type": type, "session_id": sid, "payload": payload],
        ]
        let data = try! JSONSerialization.data(withJSONObject: object)
        return String(data: data, encoding: .utf8)!
    }

    private func toolTurnFrames() -> [String] {
        var frames: [String] = []
        frames.append(event("message.start", payload: [:]))
        frames.append(event("session.title", payload: ["title": "Photo question"]))
        frames.append(event("thinking.delta", payload: ["text": "let me look"]))
        frames.append(event("reasoning.delta", payload: ["text": "examining the image"]))
        frames.append(event("tool.start", payload: ["id": "t1", "name": "view_image"]))
        frames.append(event("tool.complete", payload: ["id": "t1", "name": "view_image", "summary": "ok"]))
        frames.append(event("thinking.delta", payload: ["text": "done looking"]))
        for word in ["The ", "photo ", "shows ", "a ", "sunset."] {
            frames.append(event("message.delta", payload: ["text": word]))
        }
        frames.append(event("message.complete", payload: ["text": "The photo shows a sunset.", "status": "ok"]))
        return frames
    }

    func testReducerRendersToolTurnAnswer() {
        // Layer 1: TranscriptState.apply with already-decoded events.
        var transcript = TranscriptState()
        transcript.ownSessionIDs.insert("runtime-1")
        transcript.apply(.messageStart(sessionID: "runtime-1", text: nil))
        transcript.apply(.reasoningDelta(sessionID: "runtime-1", text: "examining", replace: false))
        transcript.apply(.toolStart(sessionID: "runtime-1", toolID: "t1", name: "view_image", context: nil))
        transcript.apply(.toolComplete(sessionID: "runtime-1", toolID: "t1", name: "view_image", summary: "ok"))
        for word in ["The ", "photo ", "shows ", "a ", "sunset."] {
            transcript.apply(.messageDelta(sessionID: "runtime-1", text: word))
        }
        transcript.apply(.messageComplete(
            sessionID: "runtime-1", text: "The photo shows a sunset.", status: "ok",
            error: nil, reasoning: nil, warning: nil, failureReason: nil,
            recoverable: false, billing: nil
        ))
        let assistantText = transcript.rows.filter { $0.role == "assistant" }.map(\.text).joined()
        XCTAssertTrue(
            assistantText.contains("The photo shows a sunset."),
            "reducer lost the answer; rows=\(transcript.rows.map { ($0.role, $0.text, $0.completed) })"
        )
    }

    func testChatConnectionDecodesToolTurnFrames() async throws {
        // Layer 2: raw gateway-shaped frames through the real ChatConnection.
        let socket = ScriptedSocket(frames: toolTurnFrames())
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        var received: [ChatEvent] = []
        for await chatEvent in stream {
            received.append(chatEvent)
        }
        await connection.close()

        let deltaText = received.compactMap { event -> String? in
            if case let .messageDelta(_, text) = event { return text }
            return nil
        }.joined()
        XCTAssertEqual(
            deltaText,
            "The photo shows a sunset.",
            "delta events dropped; received=\(received)"
        )
        let sawComplete = received.contains {
            if case .messageComplete = $0 { return true }
            return false
        }
        XCTAssertTrue(sawComplete, "message.complete dropped; received=\(received)")

        // Layer 3: the decoded stream through the reducer, end to end.
        var transcript = TranscriptState()
        transcript.ownSessionIDs.insert("runtime-1")
        for chatEvent in received { transcript.apply(chatEvent) }
        let assistantText = transcript.rows.filter { $0.role == "assistant" }.map(\.text).joined()
        XCTAssertTrue(
            assistantText.contains("The photo shows a sunset."),
            "end-to-end lost the answer; rows=\(transcript.rows.map { ($0.role, $0.text, $0.completed) })"
        )
    }
}
