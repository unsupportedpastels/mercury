import XCTest
@testable import Mercury

final class ChatPresentationIsolationTests: XCTestCase {
    private func info(_ runtime: String, _ durable: String?) -> ChatEvent {
        .sessionInfo(sessionID: runtime, storedSessionID: durable, model: nil, provider: nil,
                     reasoningEffort: nil, fastMode: nil, title: "A title", running: nil)
    }

    func testMultiplexedAEventsCannotPublishIntoBTranscriptOrTitle() {
        let aEvents: [ChatEvent] = [
            info("runtime-A", "durable-A"),
            .messageStart(sessionID: "runtime-A", text: "A waiting"),
            .messageDelta(sessionID: "runtime-A", text: "A child completion"),
            .sessionTitle(sessionID: "runtime-A", title: "A title"),
            .error(sessionID: "runtime-A", message: "A error"),
            .approvalRequest(sessionID: "runtime-A", requestID: "A approval", command: nil, description: nil, choices: [])
        ]
        var transcript = TranscriptState(isNewSession: true)
        transcript.ownSessionIDs = ["runtime-B"]
        for event in aEvents {
            let accepted = event.belongsToPresentation(runtimeID: "runtime-B", durableID: "durable-B")
            XCTAssertFalse(accepted, "Foreign event escaped the native publication boundary: \(event)")
            if accepted { transcript.apply(event) }
        }
        XCTAssertTrue(transcript.rows.isEmpty)
        XCTAssertNil(transcript.pendingRequest)
        XCTAssertNil(transcript.lastError)
        let own = ChatEvent.messageStart(sessionID: "runtime-B", text: "B only")
        XCTAssertTrue(own.belongsToPresentation(runtimeID: "runtime-B", durableID: "durable-B"))
        transcript.apply(own)
        XCTAssertEqual(transcript.rows.map(\.text), ["B only"])
    }

    func testMetadataRemappingRequiresExactDurableBinding() {
        XCTAssertTrue(info("new-B", "durable-B").belongsToPresentation(runtimeID: "old-B", durableID: "durable-B"))
        XCTAssertFalse(info("runtime-A", "durable-A").belongsToPresentation(runtimeID: "runtime-B", durableID: "durable-B"))
        XCTAssertFalse(info("runtime-B", "durable-A").belongsToPresentation(runtimeID: "runtime-B", durableID: "durable-B"))
        XCTAssertTrue(info("runtime-B", nil).belongsToPresentation(runtimeID: "runtime-B", durableID: "durable-B"))
        XCTAssertFalse(info("", "durable-B").belongsToPresentation(runtimeID: "runtime-B", durableID: "durable-B"))
        XCTAssertFalse(info("runtime-A", nil).belongsToPresentation(runtimeID: nil, durableID: nil))
    }
}
