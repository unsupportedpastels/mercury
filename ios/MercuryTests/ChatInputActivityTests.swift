import Foundation
import XCTest
import MercuryCore
@testable import Mercury

@MainActor
final class ChatInputActivityTests: XCTestCase {
    func testLateClarificationAckCannotDismissNewerRequest() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connection = connection
        state.runtimeSessionID = "runtime"
        state.connectionState = .live
        let first = Mercury.ChatEvent.clarifyRequest(sessionID: "runtime", requestID: "first", question: "First?", choices: [], multiSelect: false)
        let newer = Mercury.ChatEvent.clarifyRequest(sessionID: "runtime", requestID: "newer", question: "Newer?", choices: [], multiSelect: false)
        state.transcript.apply(first)
        state.pendingRequest = .clarify(first)
        socket.autoRespond = { [socket] _ in
            Task { @MainActor in
                state.transcript.apply(newer)
                state.pendingRequest = .clarify(newer)
                socket.enqueue(.frame(#"{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"#))
            }
            return nil
        }
        await ChatView(fixture: state).answerClarify("First answer")
        XCTAssertEqual(state.requestID, "newer")
        XCTAssertEqual(state.transcript.pendingRequest, .clarify(newer))
        XCTAssertNil(state.inputResponseID)
        await connection.close()
    }

    func testDismissedSecureSheetRetainsActionableAttentionUntilResolved() {
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connectionState = .live
        state.outstandingSecure = ChatView.SecureRequest(kind: .secret, requestID: "secure", prompt: "Synthetic input")
        state.pendingSecure = nil
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .needsyou)
        state.presentPendingInput()
        XCTAssertEqual(state.pendingSecure?.requestID, "secure")
        state.finishInputRequests()
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .hidden)
    }

    func testBatchClarificationRemainsPendingUntilLastQuestion() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard let data = sent.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let id = json["id"] as? Int else { return nil }
            return "{\"jsonrpc\":\"2.0\",\"id\":\(id),\"result\":{\"status\":\"ok\"}}"
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connection = connection
        state.connectionState = .live
        state.runtimeSessionID = "runtime"
        let request = Mercury.ChatEvent.clarifyRequest(sessionID: "runtime", requestID: "batch", question: "First?", choices: [], multiSelect: false, questions: [
            Mercury.ClarifyQuestion(qid: "one", question: "First?", choices: [], multiSelect: false),
            Mercury.ClarifyQuestion(qid: "two", question: "Second?", choices: [], multiSelect: false)
        ])
        state.transcript.apply(request)
        state.pendingRequest = .clarify(request)
        let view = ChatView(fixture: state)
        await view.answerClarify("One")
        XCTAssertEqual(state.currentClarifyQuestion?.qid, "two")
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .needsyou)
        await view.answerClarify("Two")
        XCTAssertNil(state.transcript.pendingRequest)
        XCTAssertNil(state.pendingRequest)
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .hidden)
        await connection.close()
    }

    func testClarificationAcknowledgementReleasesNeedsYou() async throws {
        let socket = ConnectionTestSocket(frames: [])
        socket.autoRespond = { sent in
            guard sent.contains("clarify.respond") else { return nil }
            return #"{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"#
        }
        let connection = try ChatConnection(socket: socket)
        _ = connection.start()
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connection = connection
        state.connectionState = .live
        state.runtimeSessionID = "runtime"
        state.durableID = "stored"
        state.isSending = true
        state.transcript.ownSessionIDs = ["runtime", "stored"]
        state.transcript.apply(.messageStart(sessionID: "runtime", text: nil))
        let question = Mercury.ChatEvent.clarifyRequest(sessionID: "runtime", requestID: "question", question: "Environment?", choices: ["Staging"], multiSelect: false)
        state.transcript.apply(question)
        state.pendingRequest = .clarify(question)
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .needsyou)
        let view = ChatView(fixture: state)
        await view.answerClarify("Staging")
        XCTAssertNil(state.transcript.pendingRequest, "Acknowledged questions must not pin Needs you forever")
        XCTAssertEqual(state.activityLine(activeChildCount: 0).kind, .working)
        await connection.close()
    }
}
