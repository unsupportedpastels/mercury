import Foundation
import XCTest
@testable import Mercury

// Hermetic tests for the chat connection state machine, driven through a
// scripted fake socket (the Swift analog of Android's FakeHermesGateway /
// fake HermesChatSocket doubles). No real networking.

/// Scriptable ChatSocketing double: the test queues inbound frames and can
/// capture outbound sends.
final class ConnectionTestSocket: ChatSocketing, @unchecked Sendable {
    enum Inbound {
        case frame(String)
        case peerClose
    }

    private let lock = NSLock()
    private var inbound: [Inbound] = []
    private(set) var sent: [String] = []
    private var closedFlag = false
    /// Signalled when a receiver is waiting for the next frame.
    private var waiters: [CheckedContinuation<String?, Never>] = []
    /// Test hook: when a sent frame matches, the returned frame is delivered
    /// as inbound. Emulates a live server replying to requests.
    nonisolated(unsafe) var autoRespond: ((String) -> String?)?

    init(frames: [Inbound]) {
        self.inbound = frames
    }

    func enqueue(_ item: Inbound) {
        lock.lock()
        if waiters.isEmpty {
            inbound.append(item)
            lock.unlock()
            return
        }
        let waiter = waiters.removeFirst()
        lock.unlock()
        switch item {
        case .frame(let text):
            waiter.resume(returning: text)
        case .peerClose:
            waiter.resume(returning: nil)
        }
    }

    func sendText(_ text: String) async throws {
        let reply = autoRespond?(text)
        lock.lock()
        sent.append(text)
        // Deliver an auto-response directly to a waiting receiver, or queue it
        // exactly like a real socket when the read loop is between receives.
        if let reply {
            if !waiters.isEmpty {
                let waiter = waiters.removeFirst()
                lock.unlock()
                waiter.resume(returning: reply)
                return
            }
            inbound.append(.frame(reply))
        }
        lock.unlock()
    }

    func receiveText() async throws -> String? {
        await withCheckedContinuation { continuation in
            lock.lock()
            if !inbound.isEmpty {
                let item = inbound.removeFirst()
                lock.unlock()
                switch item {
                case .frame(let text):
                    continuation.resume(returning: text)
                case .peerClose:
                    continuation.resume(returning: nil)
                }
                return
            }
            waiters.append(continuation)
            lock.unlock()
        }
    }

    func close() async {
        lock.lock()
        closedFlag = true
        // Wake any pending receivers with a close so loops end promptly.
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        for waiter in pending { waiter.resume(returning: nil) }
    }

    var wasClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closedFlag
    }

    var lastSent: String? {
        lock.lock()
        defer { lock.unlock() }
        return sent.last
    }
}

final class ChatConnectionTests: XCTestCase {

    // MARK: Helpers

    private func eventEnvelope(type: String, sessionID: String = "rt-1", payload: String) -> String {
        #"{"jsonrpc":"2.0","method":"event","params":{"session_id":"\#(sessionID)","type":"\#(type)","payload":\#(payload)}}"#
    }

    private func responseFrame(id: Int64, result: String) -> String {
        #"{"jsonrpc":"2.0","id":\#(id),"result":\#(result)}"#
    }

    private func errorFrame(id: Int64, code: Int64) -> String {
        #"{"jsonrpc":"2.0","id":\#(id),"error":{"code":\#(code),"message":"boom"}}"#
    }

    /// Collects exactly `count` events or throws after `timeout`.
    private func collect(
        _ count: Int,
        from stream: AsyncStream<ChatEvent>,
        timeout: TimeInterval = 2
    ) async throws -> [ChatEvent] {
        try await withTimeout(timeout) {
            var collected: [ChatEvent] = []
            for await event in stream {
                if Task.isCancelled { break }
                collected.append(event)
                if collected.count >= count { break }
            }
            return collected
        }
    }

    /// Races `body` against a timeout. The body is a structured child, so
    /// cancellation reaches AsyncStream iteration and the task group can exit.
    private func withTimeout<T: Sendable>(
        _ seconds: TimeInterval,
        _ body: @escaping @Sendable () async -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { await body() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                try Task.checkCancellation()
                throw ChatError.transport("timeout waiting for events")
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else {
                throw ChatError.transport("timeout waiting for events")
            }
            return first
        }
    }

    // MARK: Event decoding

    func testDeltaEventsArriveInOrderAndPreserveLeadingSpaces() async throws {
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(type: "message.delta", payload: #"{"text":"HE"}"#)),
            .frame(eventEnvelope(type: "message.delta", payload: #"{"text":" WORLD"}"#)),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let events = try await collect(2, from: stream)

        XCTAssertEqual(events[0], .messageDelta(sessionID: "rt-1", text: "HE"))
        // The leading space on the second delta MUST survive: streaming
        // tokenizers attach inter-word spaces to the front of the next token.
        XCTAssertEqual(events[1], .messageDelta(sessionID: "rt-1", text: " WORLD"))
    }

    func testUnknownEventTypeIsIgnored() async throws {
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(type: "pet.changed", payload: "{}")),
            .frame(eventEnvelope(type: "message.delta", payload: #"{"text":"hi"}"#)),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let events = try await collect(1, from: stream)

        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0], .messageDelta(sessionID: "rt-1", text: "hi"))
    }

    func testNonTwoPointOhEnvelopeIsDropped() async throws {
        let socket = ConnectionTestSocket(frames: [
            .frame(#"{"jsonrpc":"1.0","method":"event","params":{}}"#),
            .frame(eventEnvelope(type: "message.delta", payload: #"{"text":"ok"}"#)),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let events = try await collect(1, from: stream)
        XCTAssertEqual(events.count, 1)
    }

    func testMessageCompleteDecodesBillingAndRecoverable() async throws {
        let payload = """
        {"text":"done","status":"completed","recoverable":true,"billing":{"provider":"nous","is_nous":true,"billing_url":"https://x/y"}}
        """
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(type: "message.complete", payload: payload)),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let events = try await collect(1, from: stream)

        guard case .messageComplete(_, let text, let status, _, _, _, _, let recoverable, let billing) = events[0] else {
            return XCTFail("expected messageComplete, got \(events[0])")
        }
        XCTAssertEqual(text, "done")
        XCTAssertEqual(status, "completed")
        XCTAssertTrue(recoverable)
        XCTAssertEqual(billing?.provider, "nous")
        XCTAssertTrue(billing?.isNous ?? false)
    }

    func testApprovalRequestWithEmptyChoicesIsDroppedButRealOneQueues() async throws {
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(type: "approval.request", payload: #"{"choices":[]}"#)),
            .frame(eventEnvelope(
                type: "approval.request",
                payload: #"{"request_id":"a-1","command":"rm -rf /tmp/x","choices":["Allow","Deny"]}"#
            )),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let events = try await collect(1, from: stream)

        guard case .approvalRequest(_, let requestID, let command, _, let choices) = events[0] else {
            return XCTFail("expected approvalRequest")
        }
        XCTAssertEqual(requestID, "a-1")
        XCTAssertEqual(command, "rm -rf /tmp/x")
        XCTAssertEqual(choices, ["Allow", "Deny"])
    }

    func testMetadataFieldsTrimButTextFieldsDoNot() async throws {
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(
                type: "session.title",
                payload: #"{"title":"  Padded Title  "}"#
            )),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let events = try await collect(1, from: stream)
        guard case .sessionTitle(_, let title) = events[0] else {
            return XCTFail("expected sessionTitle")
        }
        XCTAssertEqual(title, "Padded Title", "metadata fields trim")
    }

    // MARK: Request/response correlation

    func testSubmitPromptCorrelatesByID() async throws {
        // Auto-reply on send: pre-queued responses can be consumed by the read
        // loop before request id 1 registers (timing-dependent hang).
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"prompt.submit""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"status":"queued"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let submission = try await connection.submitPrompt(runtimeSessionID: "rt-9", text: "hello")

        XCTAssertEqual(submission.status, "queued")
        // Outbound frame carries jsonrpc/id/method/params with prompt.submit.
        let sent = socket.lastSent ?? ""
        XCTAssertTrue(sent.contains(#""method":"prompt.submit""#), sent)
        XCTAssertTrue(sent.contains(#""text":"hello""#))
        XCTAssertTrue(sent.contains(#""jsonrpc":"2.0""#))
    }

    func testMethodNotFoundSurfacesMethodName() async throws {
        // Same auto-reply pattern: respond to prompt.submit with -32601.
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"prompt.submit""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"nope"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.submitPrompt(runtimeSessionID: "rt-1", text: "hi")
            XCTFail("expected ChatMethodNotFoundError")
        } catch let error as ChatMethodNotFoundError {
            XCTAssertEqual(error.method, "prompt.submit")
        }
    }

    func testOtherErrorCodesBecomeProtocolErrorsWithCode() async throws {
        // Auto-reply on send (same pattern as the correlation test): a
        // pre-queued response frame can be consumed by the read loop before
        // request id 1 is registered, in which case the frame is dropped as
        // unmatched and submitPrompt would wait forever. Replying on send
        // guarantees the response arrives only after the request was sent.
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"prompt.submit""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.submitPrompt(runtimeSessionID: "rt-1", text: "hi")
            XCTFail("expected protocolError")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("Hermes RPC request failed (-32000)"))
        }
    }

    func testMalformedFrameFailsPendingRequest() async throws {
        let socket = ConnectionTestSocket(frames: [
            .frame("this is not json"),
        ])
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.submitPrompt(runtimeSessionID: "rt-1", text: "hi")
            XCTFail("expected failure for malformed frame")
        } catch let error as ChatError {
            XCTAssertTrue(
                String(describing: error).contains("closed") ||
                    String(describing: error).contains("invalid"),
                "\(error)"
            )
        }
    }

    // MARK: Resume

    func testResumeParsesHappyPath() async throws {
        let result = """
        {"session_id":"rt-42","session_key":"dur-42","resumed":true,"running":false,
         "messages":[{"role":"user","content":"hey"}],
         "inflight":{"user":"q","assistant":"partial","streaming":true},
         "info":{"model":"hermes-default","provider":"nous","reasoning_effort":"high","fast":true}}
        """
        // autoRespond, not a pre-queued frame: a queued response can be consumed
        // by the read loop BEFORE the request id registers and dropped, hanging
        // the await forever (hit for real Aug 2026 — see skill test-harness note).
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"session.resume""#) else { return nil }
            return self.responseFrame(id: 1, result: result)
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let resumed = try await connection.resume(durableSessionID: "dur-42", profile: nil)

        XCTAssertEqual(resumed.runtimeSessionID, "rt-42")
        XCTAssertEqual(resumed.durableSessionID, "dur-42")
        XCTAssertTrue(resumed.resumed)
        XCTAssertFalse(resumed.running)
        XCTAssertEqual(resumed.messages.count, 1)
        XCTAssertEqual(resumed.inflight?.streaming, true)
        XCTAssertEqual(resumed.model, "hermes-default")
        XCTAssertEqual(resumed.fastMode, true)
        // The request must carry close_on_disconnect:false per contract.
        XCTAssertTrue((socket.lastSent ?? "").contains(#""close_on_disconnect":false"#))
    }

    func testResumeRejectsDurableSessionMismatch() async throws {
        let result = #"{"session_id":"rt-42","session_key":"OTHER"}"#
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"session.resume""#) else { return nil }
            return self.responseFrame(id: 1, result: result)
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.resume(durableSessionID: "dur-42", profile: nil)
            XCTFail("expected mismatch rejection")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("Resume response referenced a different durable session"))
        }
    }

    // MARK: Approvals

    func testApprovalRespondRejectsUnadvertisedChoice() async throws {
        // First queue a real approval event so the advertised choices exist…
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(
                type: "approval.request",
                payload: #"{"request_id":"a-1","choices":["Allow","Deny"]}"#
            )),
        ])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()
        _ = try await collect(1, from: stream)

        do {
            _ = try await connection.respondToApproval(
                runtimeSessionID: "rt-1",
                choice: "Execute everything",
                requestID: "a-1"
            )
            XCTFail("expected rejection of unadvertised choice")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("Approval choice was not advertised"))
        }
    }

    func testApprovalRespondSendsValidatedChoiceAndReturnsNextApproval() async throws {
        // Queue TWO approvals; responding to the first should surface the second.
        // The RPC response is NOT pre-queued: the read loop would consume it
        // before request id 1 is registered. Instead the socket auto-replies
        // when it observes the approval.respond request go out.
        let socket = ConnectionTestSocket(frames: [
            .frame(eventEnvelope(
                type: "approval.request",
                payload: #"{"request_id":"a-1","command":"first","choices":["Allow","Deny"]}"#
            )),
            .frame(eventEnvelope(
                type: "approval.request",
                payload: #"{"request_id":"a-2","command":"second","choices":["Allow","Deny"]}"#
            )),
        ])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"approval.respond""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()
        _ = try await collect(2, from: stream)

        let response = try await connection.respondToApproval(
            runtimeSessionID: "rt-1",
            choice: "Allow",
            all: false,
            requestID: "a-1"
        )

        XCTAssertEqual(response.status, .ok)
        guard case .approvalRequest(_, let nextID, _, _, _) = response.nextApproval else {
            return XCTFail("expected nextApproval to be surfaced")
        }
        XCTAssertEqual(nextID, "a-2")
        XCTAssertTrue((socket.lastSent ?? "").contains(#""choice":"Allow""#))
    }

    // MARK: Lifecycle

    func testCloseFailsPendingRequestsAndEndsStream() async throws {
        // No queued response: submitPrompt would hang until close().
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        let stream = connection.start()

        let submissionTask = Task {
            try? await connection.submitPrompt(runtimeSessionID: "rt-1", text: "hi")
        }
        // Give the send a moment to register, then close.
        try await Task.sleep(nanoseconds: 100_000_000)
        await connection.close()

        _ = await submissionTask.value

        XCTAssertTrue(socket.wasClosed)
        // The stream must terminate after close; keep the assertion bounded
        // so a lifecycle regression fails instead of hanging the whole suite.
        let sawFinish = try await withTimeout(2) {
            for await _ in stream {}
            return true
        }
        XCTAssertTrue(sawFinish)
    }

    func testOversizedOutboundInputRejectedLocally() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let huge = String(repeating: "x", count: maxMessageTextChars + 1)
        do {
            _ = try await connection.submitPrompt(runtimeSessionID: "rt-1", text: huge)
            XCTFail("expected local bound rejection")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("Hermes prompt text is too long"))
        }
    }

    func testPeerCloseFailsPendingRequests() async throws {
        let socket = ConnectionTestSocket(frames: [.peerClose])
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.submitPrompt(runtimeSessionID: "rt-1", text: "hi")
            XCTFail("expected transport failure after peer close")
        } catch let error as ChatError {
            guard case .transport = error else {
                return XCTFail("expected transport error, got \(error)")
            }
        }
    }

    // MARK: Attachments (file.attach / image.attach_bytes)

    func testAttachFileEncodesParamsAndReturnsRefText() async throws {
        // Auto-reply on send: pre-queued response frames get consumed by the
        // read loop before request id 1 registers and hang the suite.
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"file.attach""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"ref_text":"attached: report.pdf"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let refText = try await connection.attachFile(
            runtimeSessionID: "rt-9",
            filename: "report.pdf",
            mimeType: "application/pdf",
            base64Content: "aGVsbG8="
        )

        XCTAssertEqual(refText, "attached: report.pdf")
        // Wire contract: session_id + path + name + data_url with the
        // data:<mime>;base64, prefix.
        let sent = socket.lastSent ?? ""
        XCTAssertTrue(sent.contains(#""method":"file.attach""#), sent)
        XCTAssertTrue(sent.contains(#""session_id":"rt-9""#), sent)
        XCTAssertTrue(sent.contains(#""path":"report.pdf""#), sent)
        XCTAssertTrue(sent.contains(#""name":"report.pdf""#), sent)
        // JSONSerialization escapes '/' as '\/' in string values, so assert on
        // the decoded params rather than the raw frame bytes.
        let frame = try XCTUnwrap(sent.data(using: .utf8))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: frame) as? [String: Any]
        )
        let params = try XCTUnwrap(object["params"] as? [String: Any])
        XCTAssertEqual(
            params["data_url"] as? String,
            "data:application/pdf;base64,aGVsbG8="
        )
        XCTAssertTrue(sent.contains(#""jsonrpc":"2.0""#))
    }

    func testAttachFileMissingRefTextThrowsProtocolError() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"file.attach""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"other":"x"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.attachFile(
                runtimeSessionID: "rt-1",
                filename: "f.txt",
                mimeType: "text/plain",
                base64Content: "aGk="
            )
            XCTFail("expected protocolError for missing ref_text")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("File attach response was incomplete"))
        }
    }

    func testAttachFileBlankRefTextThrowsProtocolError() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"file.attach""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"ref_text":"   "}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.attachFile(
                runtimeSessionID: "rt-1",
                filename: "f.txt",
                mimeType: "text/plain",
                base64Content: "aGk="
            )
            XCTFail("expected protocolError for blank ref_text")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("File attach response was incomplete"))
        }
    }

    func testAttachFileServerErrorBecomesClassifiedError() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"file.attach""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        do {
            _ = try await connection.attachFile(
                runtimeSessionID: "rt-1",
                filename: "f.txt",
                mimeType: "text/plain",
                base64Content: "aGk="
            )
            XCTFail("expected classified server error")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("Hermes RPC request failed (-32000)"))
        }
    }

    func testAttachImageBytesEncodesParamsAndIgnoresBody() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"image.attach_bytes""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        // Must not throw even though the result body carries unexpected fields.
        try await connection.attachImageBytes(
            runtimeSessionID: "rt-7",
            filename: "photo.png",
            base64Content: "iVBORw0KGgo="
        )

        let sent = socket.lastSent ?? ""
        XCTAssertTrue(sent.contains(#""method":"image.attach_bytes""#), sent)
        XCTAssertTrue(sent.contains(#""session_id":"rt-7""#), sent)
        XCTAssertTrue(sent.contains(#""filename":"photo.png""#), sent)
        XCTAssertTrue(sent.contains(#""content_base64":"iVBORw0KGgo=""#), sent)
    }

    // MARK: Blocking secure prompts (secret/sudo/*read respond)

    /// Parses the last outbound frame with JSONSerialization and returns its
    /// params. JSONSerialization escapes '/' inside string values, so wire
    /// assertions on values that can contain '/' must go through this helper
    /// rather than raw substring matching.
    private func decodedSentParams(of socket: ConnectionTestSocket) throws -> [String: Any] {
        let sent = try XCTUnwrap(socket.lastSent, "expected a sent frame")
        let data = try XCTUnwrap(sent.data(using: .utf8))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        return try XCTUnwrap(object["params"] as? [String: Any])
    }

    private func makeBlockingAutoRespondSocket(expectMethod: String) -> ConnectionTestSocket {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains("\"\(expectMethod)\"") else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"#
        }
        return socket
    }

    func testRespondToSecretPromptSendsContractedMethodAndValueKey() async throws {
        let socket = makeBlockingAutoRespondSocket(expectMethod: "secret.respond")
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let response = try await connection.respondToBlockingPrompt(
            kind: .secret,
            requestID: "req-secret-1",
            value: "s3cret/value"
        )

        XCTAssertEqual(response.status, .ok)
        let sent = socket.lastSent ?? ""
        XCTAssertTrue(sent.contains(#""method":"secret.respond""#), sent)
        // '/' is escaped on the wire; compare decoded values instead.
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["request_id"] as? String, "req-secret-1")
        XCTAssertEqual(params["value"] as? String, "s3cret/value")
        XCTAssertEqual(params.count, 2, "params must carry exactly request_id + value")
    }

    func testRespondToSudoPromptSendsContractedMethodAndPasswordKey() async throws {
        let socket = makeBlockingAutoRespondSocket(expectMethod: "sudo.respond")
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let response = try await connection.respondToBlockingPrompt(
            kind: .sudo,
            requestID: "req-sudo-1",
            value: "hunter/2"
        )

        XCTAssertEqual(response.status, .ok)
        XCTAssertTrue((socket.lastSent ?? "").contains(#""method":"sudo.respond""#))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["request_id"] as? String, "req-sudo-1")
        XCTAssertEqual(params["password"] as? String, "hunter/2")
        XCTAssertEqual(params.count, 2)
    }

    func testRespondToTerminalReadPromptSendsContractedMethodAndTextKey() async throws {
        let socket = makeBlockingAutoRespondSocket(expectMethod: "terminal.read.respond")
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let response = try await connection.respondToBlockingPrompt(
            kind: .terminalRead,
            requestID: "req-term-1",
            value: "line one/line two"
        )

        XCTAssertEqual(response.status, .ok)
        XCTAssertTrue((socket.lastSent ?? "").contains(#""method":"terminal.read.respond""#))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["request_id"] as? String, "req-term-1")
        XCTAssertEqual(params["text"] as? String, "line one/line two")
        XCTAssertEqual(params.count, 2)
    }

    func testRespondToPreviewReadPromptSendsContractedMethodAndTextKey() async throws {
        let socket = makeBlockingAutoRespondSocket(expectMethod: "preview.read.respond")
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let response = try await connection.respondToBlockingPrompt(
            kind: .previewRead,
            requestID: "req-prev-1",
            value: "preview text"
        )

        XCTAssertEqual(response.status, .ok)
        XCTAssertTrue((socket.lastSent ?? "").contains(#""method":"preview.read.respond""#))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["request_id"] as? String, "req-prev-1")
        XCTAssertEqual(params["text"] as? String, "preview text")
        XCTAssertEqual(params.count, 2)
    }

    func testRespondToWindowReadPromptSendsContractedMethodAndTextKey() async throws {
        let socket = makeBlockingAutoRespondSocket(expectMethod: "window.read.respond")
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let response = try await connection.respondToBlockingPrompt(
            kind: .windowRead,
            requestID: "req-win-1",
            value: "window text"
        )

        XCTAssertEqual(response.status, .ok)
        XCTAssertTrue((socket.lastSent ?? "").contains(#""method":"window.read.respond""#))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["request_id"] as? String, "req-win-1")
        XCTAssertEqual(params["text"] as? String, "window text")
        XCTAssertEqual(params.count, 2)
    }

    func testTerminalReadRespondAllowsEmptyValueAsSurfaceUnavailableAnswer() async throws {
        // Empty string is the official "surface unavailable" auto-answer, so
        // the blank bound must be allowed for terminal.read.respond.
        let socket = makeBlockingAutoRespondSocket(expectMethod: "terminal.read.respond")
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let response = try await connection.respondToBlockingPrompt(
            kind: .terminalRead,
            requestID: "req-term-2",
            value: ""
        )

        XCTAssertEqual(response.status, .ok)
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["text"] as? String, "")
        XCTAssertEqual(params["request_id"] as? String, "req-term-2")
    }

    func testBlockingPromptResponseParsesResolvedOkAndExpiredStatuses() async throws {
        func runExpecting(_ resultJSON: String, expected: ChatResponse.Status) async throws {
            let socket = ConnectionTestSocket(frames: [])
            socket.autoRespond = { sent in
                guard sent.contains("\"sudo.respond\"") else { return nil }
                return """
                {"jsonrpc":"2.0","id":1,"result":\(resultJSON)}
                """
            }
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            let response = try await connection.respondToBlockingPrompt(
                kind: .sudo,
                requestID: "req-sudo-9",
                value: "pw"
            )
            XCTAssertEqual(response.status, expected)
        }

        // Explicit status string.
        try await runExpecting(#"{"status":"ok"}"#, expected: .ok)
        // Boolean resolved:true parses to ok (parseInteractionResponse path).
        try await runExpecting(#"{"resolved":true}"#, expected: .ok)
        // Expired requests must surface as expired, not throw.
        try await runExpecting(#"{"status":"expired"}"#, expected: .expired)
        try await runExpecting(#"{"resolved":false}"#, expected: .expired)
    }

    // MARK: M7 typed JSON-RPC and model layer

    private func m7Socket(method: String, result: String) -> ConnectionTestSocket {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains("\"method\":\"\(method)\"") else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":\#(result)}"#
        }
        return socket
    }

    func testModelOptionsRequestFiltersAndDeduplicatesTolerantly() async throws {
        let result = """
        {"provider":"nous","model":"m1","providers":[
          {"slug":"nous","name":"Nous","authenticated":true,"models":["m1","m1","  m2  ","bad model"],
           "capabilities":{"m1":{"fast":true,"reasoning":false,"future":1},"m2":{"reasoning":true}}},
          {"slug":"hidden","authenticated":false,"models":["secret"]},
          {"slug":"nous","models":["duplicate-provider"]},
          {"slug":"empty","models":[]},"junk"],"future":"ignored"}
        """
        let socket = m7Socket(method: "model.options", result: result)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let options = try await connection.loadModelOptions(runtimeSessionID: "runtime-7")

        XCTAssertEqual(options.current, ModelSelection(provider: "nous", model: "m1"))
        XCTAssertEqual(options.providers.count, 1)
        XCTAssertEqual(options.providers[0].models, ["m1", "m2"])
        XCTAssertEqual(options.providers[0].capabilities["m1"], ModelCapabilities(fast: true, reasoning: false))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["session_id"] as? String, "runtime-7")
        XCTAssertEqual(params["explicit_only"] as? Bool, true)
        XCTAssertEqual(params["include_unconfigured"] as? Bool, false)
    }

    func testSetModelUsesExactSessionValueAndParsesConfirmation() async throws {
        let socket = m7Socket(
            method: "config.set",
            result: #"{"key":"model","value":"m1 --provider nous --session","scope":"session","confirm_required":true,"confirm_message":" Confirm cost? ","deferred":false,"future":1}"#
        )
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let result = try await connection.setModel(
            runtimeSessionID: "runtime-7",
            provider: "nous",
            model: "m1",
            confirmExpensiveModel: false
        )

        XCTAssertFalse(result.accepted)
        XCTAssertTrue(result.confirmationRequired)
        XCTAssertEqual(result.confirmationMessage, "Confirm cost?")
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["value"] as? String, "m1 --provider nous --session")
        XCTAssertEqual(params["confirm_expensive_model"] as? Bool, false)

        let deferredSocket = m7Socket(
            method: "config.set",
            result: #"{"key":"model","scope":"session","confirm_required":false,"deferred":true}"#
        )
        let deferredConnection = try ChatConnection(socket: deferredSocket)
        _ = deferredConnection.start()
        let deferred = try await deferredConnection.setModel(
            runtimeSessionID: "runtime-7",
            provider: "nous",
            model: "m2",
            confirmExpensiveModel: true
        )
        XCTAssertTrue(deferred.accepted)
        XCTAssertTrue(deferred.deferred)
    }

    func testSetModelRejectsUnsafeScopeAndWrongTypedFlags() async throws {
        for payload in [
            #"{"scope":"profile"}"#,
            #"{"scope":"session","confirm_required":"yes"}"#,
            #"{"scope":"session","deferred":1}"#,
            #"{"scope":"session","key":"reasoning"}"#,
        ] {
            let socket = m7Socket(method: "config.set", result: payload)
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            do {
                _ = try await connection.setModel(
                    runtimeSessionID: "rt", provider: "nous", model: "m1", confirmExpensiveModel: true
                )
                XCTFail("expected malformed model switch rejection for \(payload)")
            } catch is ChatError {}
            await connection.close()
        }
    }

    func testSetReasoningCanonicalizesAndRejectsContradictoryResponse() async throws {
        let socket = m7Socket(method: "config.set", result: #"{"key":"reasoning","value":"xhigh","scope":"session"}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        try await connection.setReasoning(runtimeSessionID: "runtime-7", effort: " XHIGH ")
        XCTAssertEqual(try decodedSentParams(of: socket)["value"] as? String, "xhigh")

        for payload in [
            #"{"key":"model","scope":"session"}"#,
            #"{"key":"reasoning","scope":"profile"}"#,
            #"{"key":7,"scope":"session"}"#,
        ] {
            let badSocket = m7Socket(method: "config.set", result: payload)
            let badConnection = try ChatConnection(socket: badSocket)
            _ = badConnection.start()
            do {
                try await badConnection.setReasoning(runtimeSessionID: "runtime-7", effort: "high")
                XCTFail("expected contradictory reasoning response rejection")
            } catch is ChatError {}
            await badConnection.close()
        }
    }

    func testSetReasoningRejectsInvalidEffortBeforeSending() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        do {
            try await connection.setReasoning(runtimeSessionID: "runtime-7", effort: "extreme")
            XCTFail("expected invalid effort rejection")
        } catch let error as ChatError {
            XCTAssertEqual(error, .protocolError("Reasoning effort is invalid"))
        }
        XCTAssertNil(socket.lastSent)
    }

    func testSetFastUsesSessionScopedConfigAndRejectsContradictoryResponse() async throws {
        let socket = m7Socket(
            method: "config.set",
            result: #"{"key":"fast","value":"fast","scope":"session"}"#
        )
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        try await connection.setFast(runtimeSessionID: "runtime-7", enabled: true)

        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["session_id"] as? String, "runtime-7")
        XCTAssertEqual(params["key"] as? String, "fast")
        XCTAssertEqual(params["value"] as? String, "fast")

        for payload in [
            #"{"key":"reasoning","scope":"session"}"#,
            #"{"key":"fast","scope":"profile"}"#,
        ] {
            let badSocket = m7Socket(method: "config.set", result: payload)
            let badConnection = try ChatConnection(socket: badSocket)
            _ = badConnection.start()
            do {
                try await badConnection.setFast(runtimeSessionID: "runtime-7", enabled: false)
                XCTFail("expected contradictory fast response rejection")
            } catch is ChatError {}
            await badConnection.close()
        }
    }

    func testSteerTrimsTextAndRequiresTypedStatus() async throws {
        let socket = m7Socket(method: "session.steer", result: #"{"status":"queued","text":"bounded","extra":true}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let response = try await connection.steerSession(runtimeSessionID: "runtime-7", text: "  guidance  ")
        XCTAssertEqual(response.status, .queued)
        XCTAssertEqual(response.text, "bounded")
        XCTAssertEqual(try decodedSentParams(of: socket)["text"] as? String, "guidance")

        let blankSocket = ConnectionTestSocket(frames: [])
        let blankConnection = try ChatConnection(socket: blankSocket)
        _ = blankConnection.start()
        do {
            _ = try await blankConnection.steerSession(runtimeSessionID: "runtime-7", text: "  \n ")
            XCTFail("expected blank steer rejection")
        } catch is ChatError {}
        XCTAssertNil(blankSocket.lastSent)

        let malformedSocket = m7Socket(method: "session.steer", result: #"{"status":"accepted"}"#)
        let malformedConnection = try ChatConnection(socket: malformedSocket)
        _ = malformedConnection.start()
        do {
            _ = try await malformedConnection.steerSession(runtimeSessionID: "runtime-7", text: "guidance")
            XCTFail("expected malformed status rejection")
        } catch is ChatError {}
    }

    func testUsageAliasesClampBoundsAndContextBreakdownAliases() async throws {
        let usageSocket = m7Socket(method: "session.usage", result: #"{"prompt_tokens":12,"completion_tokens":8,"total":20,"used_tokens":200,"max_tokens":1000,"context_percentage":120,"requests":-2,"credits_lines":["a",4,"b"]}"#)
        let usageConnection = try ChatConnection(socket: usageSocket)
        _ = usageConnection.start()
        let usage = try await usageConnection.loadSessionUsage(runtimeSessionID: "runtime-7")
        XCTAssertEqual(usage.inputTokens, 12)
        XCTAssertEqual(usage.outputTokens, 8)
        XCTAssertEqual(usage.contextPercent, 100)
        XCTAssertEqual(usage.calls, 0)
        XCTAssertEqual(usage.creditsLines, ["a", "b"])

        let contextSocket = m7Socket(method: "session.context_breakdown", result: #"{"breakdown":[{"category":"system","token_count":7},{"label":"tools","count":3},{"name":"system","tokens":99},4],"context_used":10,"context_max":100,"context_percent":10}"#)
        let contextConnection = try ChatConnection(socket: contextSocket)
        _ = contextConnection.start()
        let breakdown = try await contextConnection.loadContextBreakdown(runtimeSessionID: "runtime-8")
        XCTAssertEqual(breakdown.categories.map(\.name), ["system", "tools"])
        XCTAssertEqual(breakdown.categories.map(\.tokens), [7, 3])
    }

    func testCompressOmitsBlankFocusAndParsesMessagesAndUsage() async throws {
        let socket = m7Socket(method: "session.compress", result: #"{"status":"aborted","messages":[{"role":"user"},"bad"],"usage":{"input":5},"info":{"future":1},"extra":true}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let result = try await connection.compressSession(runtimeSessionID: "runtime-7", focusTopic: "   ")
        XCTAssertTrue(result.aborted)
        XCTAssertEqual(result.messages.count, 1)
        XCTAssertEqual(result.usage?.inputTokens, 5)
        XCTAssertNil(try decodedSentParams(of: socket)["focus_topic"])
    }

    func testUndoRequiresNonnegativeRemovedCount() async throws {
        let socket = m7Socket(method: "session.undo", result: #"{"removed":0,"extra":true}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let undoResult = try await connection.undoSession(runtimeSessionID: "runtime-7")
        XCTAssertEqual(undoResult.removed, 0)

        let malformedSocket = m7Socket(method: "session.undo", result: #"{"removed":-1}"#)
        let malformedConnection = try ChatConnection(socket: malformedSocket)
        _ = malformedConnection.start()
        do {
            _ = try await malformedConnection.undoSession(runtimeSessionID: "runtime-7")
            XCTFail("expected negative removed rejection")
        } catch is ChatError {}
    }

    func testBranchRequestBoundsOptionalsAndRequiresDurableAlias() async throws {
        let socket = m7Socket(method: "session.branch", result: #"{"session_id":"new-runtime","durable_session_id":"new-durable","title":"Branch","messages":[{"role":"assistant"}],"extra":true}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let result = try await connection.branchSession(
            runtimeSessionID: "runtime-7", count: 900, name: "  Named branch  "
        )
        XCTAssertEqual(result.runtimeSessionID, "new-runtime")
        XCTAssertEqual(result.durableSessionID, "new-durable")
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["count"] as? Int, 500)
        XCTAssertEqual(params["name"] as? String, "Named branch")

        let malformedSocket = m7Socket(method: "session.branch", result: #"{"session_id":"runtime-only"}"#)
        let malformedConnection = try ChatConnection(socket: malformedSocket)
        _ = malformedConnection.start()
        do {
            _ = try await malformedConnection.branchSession(runtimeSessionID: "runtime-7")
            XCTFail("expected missing durable ID rejection")
        } catch is ChatError {}
    }

    func testSlashCompletionSendsFullTextWithoutSessionIDAndDecodesRows() async throws {
        let socket = m7Socket(method: "complete.slash", result: #"{"items":[{"text":"help","display":"/help","meta":"docs","kind":"command"},{"text":"model"},{"display":"bad"},7],"replace_from":2,"future":true}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let result = try await connection.completeSlash(text: "/h remainder")
        XCTAssertEqual(result.items, [
            SlashCompletionItem(text: "help", display: "/help", meta: "docs"),
            SlashCompletionItem(text: "model", display: "/model", meta: nil),
        ])
        XCTAssertEqual(result.replaceFrom, 2)
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["text"] as? String, "/h remainder")
        XCTAssertNil(params["session_id"])
        XCTAssertEqual(params.count, 1)
    }

    func testM7MethodNotFoundPreservesExactMethod() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"session.usage""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"secret server detail"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        do {
            _ = try await connection.loadSessionUsage(runtimeSessionID: "runtime-7")
            XCTFail("expected method not found")
        } catch let error as ChatMethodNotFoundError {
            XCTAssertEqual(error.method, "session.usage")
            XCTAssertFalse(error.localizedDescription.contains("secret server detail"))
        }
    }

    // MARK: M8 project JSON-RPC wrappers

    func testLoadActiveSessionsUsesObserverOnlyListAndFiltersMalformedRows() async throws {
        let socket = m7Socket(
            method: "session.active_list",
            result: #"{"sessions":[{"id":"runtime-1","session_key":"stored-1","title":"Build","status":"working","message_count":12,"model":"sol"},{"id":"runtime-2","session_key":"stored-2","status":"waiting"},{"id":"missing-durable","status":"working"},{"id":"bad-status","session_key":"stored-3","status":"future"}],"future":true}"#
        )
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let sessions = try await connection.loadActiveSessions()

        XCTAssertEqual(sessions.map(\.durableSessionID), ["stored-1", "stored-2"])
        XCTAssertEqual(sessions.map(\.status), [.working, .waiting])
        XCTAssertEqual(sessions.first?.messageCount, 12)
        XCTAssertEqual(sessions.first?.model, "sol")
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["current_session_id"] as? String, "")
        XCTAssertEqual(params.count, 1)
    }

    func testLoadProjectTreeSendsProfileAndClampedLimitsAndUsesParser() async throws {
        let socket = m7Socket(
            method: "projects.tree",
            result: #"{"projects":[{"id":"p1","label":"App"},{"label":"bad"}],"active_id":"p1","scoped_session_ids":["stored-1"],"future":true}"#
        )
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let tree = try await connection.loadProjectTree(
            profile: "default", previewLimit: 99, sessionLimit: -4
        )

        XCTAssertEqual(tree.projects.map(\.id), [ProjectID("p1")])
        XCTAssertEqual(tree.activeProjectID, ProjectID("p1"))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["profile"] as? String, "default")
        XCTAssertEqual(params["preview_limit"] as? Int, 3)
        XCTAssertEqual(params["session_limit"] as? Int, 0)
        XCTAssertEqual(params.count, 3)
    }

    func testLoadProjectSessionsUsesOpaqueProjectIDNestedParserAndDefaultLimit() async throws {
        let socket = m7Socket(
            method: "projects.project_sessions",
            result: #"{"project":{"id":"/srv/opaque id","label":"App","repos":[{"id":"repo","groups":[{"id":"main","sessions":[{"id":"durable-1"},{"session_key":"runtime-only"}]}]}]},"extra":1}"#
        )
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let result = try await connection.loadProjectSessions(
            projectID: ProjectID("/srv/opaque id"), profile: "work"
        )

        XCTAssertEqual(result.sessions.map(\.id), ["durable-1"])
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["project_id"] as? String, "/srv/opaque id")
        XCTAssertEqual(params["profile"] as? String, "work")
        // Android DEFAULT_PROJECT_SESSION_LIMIT parity: this is the server's
        // global session scan budget, not the per-project cap. 100 starved
        // older projects to zero sessions against a real server (Foundry bug).
        XCTAssertEqual(params["session_limit"] as? Int, 500)
    }

    func testProjectSessionLimitClampsToZeroThroughFiveHundred() async throws {
        for (requested, expected) in [(-1, 0), (501, 500)] {
            let socket = m7Socket(
                method: "projects.project_sessions",
                result: #"{"project":{"id":"p1"}}"#
            )
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            _ = try await connection.loadProjectSessions(
                projectID: ProjectID("p1"), profile: "default", sessionLimit: requested
            )
            XCTAssertEqual(try decodedSentParams(of: socket)["session_limit"] as? Int, expected)
            await connection.close()
        }
    }

    func testCreateProjectValidatesLocallySendsCanonicalFoldersAndReturnsServerID() async throws {
        let socket = m7Socket(
            method: "projects.create",
            result: #"{"project":{"id":"server-authoritative","name":"Created","primary_path":"/srv/App"},"future":true}"#
        )
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let created = try await connection.createProject(
            name: "  Created  ",
            folders: ["/srv/App", "/srv/App", "/srv/App/Sub"],
            primaryPath: "/srv/App",
            use: false,
            profile: "default"
        )

        XCTAssertEqual(created.project.id, ProjectID("server-authoritative"))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["name"] as? String, "Created")
        XCTAssertEqual(params["folders"] as? [String], ["/srv/App", "/srv/App/Sub"])
        XCTAssertEqual(params["primary_path"] as? String, "/srv/App")
        XCTAssertEqual(params["use"] as? Bool, false)
        XCTAssertEqual(params["profile"] as? String, "default")
    }

    func testProjectWrappersRejectInvalidInputsBeforeSending() async throws {
        let invalidCalls: [(ChatConnection) async throws -> Void] = [
            { _ = try await $0.loadProjectSessions(projectID: ProjectID(""), profile: "default") },
            { _ = try await $0.createProject(name: "  ", folders: ["/srv/App"], primaryPath: "/srv/App", use: true, profile: "default") },
            { _ = try await $0.createProject(name: "App", folders: [], primaryPath: "/srv/App", use: true, profile: "default") },
            { _ = try await $0.createProject(name: "App", folders: ["relative"], primaryPath: "relative", use: true, profile: "default") },
            { _ = try await $0.createProject(name: "App", folders: ["/srv/App"], primaryPath: "/srv/Other", use: true, profile: "default") },
            { _ = try await $0.setActiveProject(id: ProjectID(" "), profile: "default") },
        ]

        for call in invalidCalls {
            let socket = ConnectionTestSocket(frames: [])
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            do {
                try await call(connection)
                XCTFail("expected local project input rejection")
            } catch is ChatError {}
            XCTAssertNil(socket.lastSent)
            await connection.close()
        }
    }

    func testSetActiveProjectRequiresMatchingResponseAndIncludesProfile() async throws {
        let socket = m7Socket(method: "projects.set_active", result: #"{"active_id":"opaque:id","extra":true}"#)
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let active = try await connection.setActiveProject(id: ProjectID("opaque:id"), profile: "default")
        XCTAssertEqual(active, ProjectID("opaque:id"))
        let params = try decodedSentParams(of: socket)
        XCTAssertEqual(params["id"] as? String, "opaque:id")
        XCTAssertEqual(params["profile"] as? String, "default")

        for payload in [#"{"active_id":"other"}"#, #"{"active_id":null}"#, #"{}"#] {
            let badSocket = m7Socket(method: "projects.set_active", result: payload)
            let badConnection = try ChatConnection(socket: badSocket)
            _ = badConnection.start()
            do {
                _ = try await badConnection.setActiveProject(id: ProjectID("opaque:id"), profile: "default")
                XCTFail("expected active project mismatch rejection")
            } catch is ChatError {}
            await badConnection.close()
        }
    }

    func testProjectCreateRejectsMalformedTopLevelResult() async throws {
        for payload in [#"{}"#, #"{"project":null}"#, #"{"project":{"name":"missing id"}}"#] {
            let socket = m7Socket(method: "projects.create", result: payload)
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            do {
                _ = try await connection.createProject(
                    name: "App", folders: ["/srv/App"], primaryPath: "/srv/App", use: true, profile: "default"
                )
                XCTFail("expected malformed create result rejection")
            } catch is ChatError {}
            await connection.close()
        }
    }

    func testM8MethodNotFoundPreservesExactProjectMethod() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"projects.tree""#) else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"not installed"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        do {
            _ = try await connection.loadProjectTree(profile: "default")
            XCTFail("expected method not found")
        } catch let error as ChatMethodNotFoundError {
            XCTAssertEqual(error.method, "projects.tree")
        }
    }

    // MARK: Project deletion

    func testDeleteProjectSendsIDAndProfileAndSurfacesServerError() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"projects.delete""#) else { return nil }
            return self.responseFrame(id: 1, result: #"{"projects":[]}"#)
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        try await connection.deleteProject(id: ProjectID("p_dead"), profile: "default")

        let sent = socket.lastSent ?? ""
        XCTAssertTrue(sent.contains(#""id":"p_dead""#))
        XCTAssertTrue(sent.contains(#""profile":"default""#))

        // Unknown project: server answers 5062 — must surface as an error.
        let badSocket = ConnectionTestSocket(frames: [])
        badSocket.autoRespond = { sent in
            guard sent.contains(#""method":"projects.delete""#) else { return nil }
            return self.errorFrame(id: 1, code: 5062)
        }
        let badConnection = try ChatConnection(socket: badSocket)
        _ = badConnection.start()
        do {
            try await badConnection.deleteProject(id: ProjectID("p_missing"), profile: "default")
            XCTFail("expected server error to propagate")
        } catch is ChatError {}
        await badConnection.close()
    }

    // MARK: Project session creation (Android createProjectSession parity)

    func testCreateSessionSendsCanonicalWorkspaceAsCwd() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains(#""method":"session.create""#) else { return nil }
            return self.responseFrame(
                id: 1,
                result: #"{"session_id":"rt-9","stored_session_id":"dur-9"}"#
            )
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()

        let created = try await connection.createSession(
            profile: "default",
            workspacePath: "/workspace/example-project"
        )

        XCTAssertEqual(created.runtimeSessionID, "rt-9")
        XCTAssertEqual(created.durableSessionID, "dur-9")
        let sent = socket.lastSent ?? ""
        XCTAssertTrue(sent.contains(#""cwd":"\/workspace\/example-project""#)
            || sent.contains(#""cwd":"/workspace/example-project""#),
            "expected cwd param in session.create, got: \(sent)")
        XCTAssertTrue(sent.contains(#""close_on_disconnect":false"#))
    }

    func testCreateSessionOmitsCwdForNilAndNonCanonicalPaths() async throws {
        for workspace in [nil, "relative/path", "/has/../traversal", " "] as [String?] {
            let socket = ConnectionTestSocket(frames: [])
            socket.autoRespond = { sent in
                guard sent.contains(#""method":"session.create""#) else { return nil }
                return self.responseFrame(id: 1, result: #"{"session_id":"rt-1"}"#)
            }
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()

            _ = try await connection.createSession(profile: nil, workspacePath: workspace)

            XCTAssertFalse(
                (socket.lastSent ?? "").contains(#""cwd""#),
                "cwd must be omitted for workspace \(String(describing: workspace))"
            )
            await connection.close()
        }
    }
}
