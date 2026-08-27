import XCTest
@testable import Mercury

@MainActor
final class M11DictationTests: XCTestCase {
    private final class FakePermissions: DictationPermissionAuthorizing {
        var result: DictationPermissionState
        init(_ result: DictationPermissionState) { self.result = result }
        func requestPermissions() async -> DictationPermissionState { result }
    }

    private final class FakeEngine: DictationRecognizing {
        var isAvailable = true
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        private var transcript: ((String, Bool) -> Void)?
        private var failure: ((DictationFailure) -> Void)?

        func start(
            onTranscript: @escaping (String, Bool) -> Void,
            onLevel: @escaping (Float) -> Void,
            onFailure: @escaping (DictationFailure) -> Void
        ) throws {
            startCalls += 1
            transcript = onTranscript
            failure = onFailure
        }
        func stop() { stopCalls += 1 }
        func cancel() { cancelCalls += 1 }
        func emit(_ text: String, final: Bool = false) { transcript?(text, final) }
        func fail(_ reason: DictationFailure) { failure?(reason) }
    }

    func testTranscriptOnlyUpdatesOwnedDraftAndNeverInvokesSend() async {
        let engine = FakeEngine()
        var drafts: [String] = []
        let coordinator = ComposerDictationCoordinator(
            permissions: FakePermissions(.authorized),
            recognizer: engine,
            getDraft: { "Existing" },
            setDraft: { drafts.append($0) }
        )

        await coordinator.start()
        engine.emit("hello world")
        engine.emit("hello world", final: true)

        XCTAssertEqual(drafts, ["Existing hello world", "Existing hello world"])
        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(engine.stopCalls, 1)
    }

    func testExplicitStopEndsCaptureWithoutSendingOrClearingDraft() async {
        let engine = FakeEngine()
        var draft = "Keep me"
        let coordinator = ComposerDictationCoordinator(
            permissions: FakePermissions(.authorized),
            recognizer: engine,
            getDraft: { draft },
            setDraft: { draft = $0 }
        )

        await coordinator.start()
        coordinator.stop()

        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(draft, "Keep me")
        XCTAssertEqual(engine.stopCalls, 1)
    }

    func testExplicitStopAfterPartialTranscriptMakesDraftSendableWithoutAnotherKeystroke() async {
        let engine = FakeEngine()
        var draft = ""
        let coordinator = ComposerDictationCoordinator(
            permissions: FakePermissions(.authorized),
            recognizer: engine,
            getDraft: { draft },
            setDraft: { draft = $0 }
        )

        await coordinator.start()
        engine.emit("send this draft")
        XCTAssertFalse(ComposerSendPolicy.canSend(
            draft: draft, isSending: false, dictationActive: coordinator.isActive,
            isSteering: false, hasAttachments: false, hasHostReferences: false
        ))

        coordinator.stop()

        XCTAssertEqual(draft, "send this draft")
        XCTAssertTrue(ComposerSendPolicy.canSend(
            draft: draft, isSending: false, dictationActive: coordinator.isActive,
            isSteering: false, hasAttachments: false, hasHostReferences: false
        ))
    }

    func testPermissionAndAvailabilityFailuresAreExplicitAndRetryable() async {
        let denied = ComposerDictationCoordinator(
            permissions: FakePermissions(.denied), recognizer: FakeEngine(),
            getDraft: { "" }, setDraft: { _ in }
        )
        await denied.start()
        XCTAssertEqual(denied.state, .failed(.permissionDenied))
        denied.dismissError()
        XCTAssertEqual(denied.state, .idle)

        let unavailableEngine = FakeEngine()
        unavailableEngine.isAvailable = false
        let unavailable = ComposerDictationCoordinator(
            permissions: FakePermissions(.authorized), recognizer: unavailableEngine,
            getDraft: { "" }, setDraft: { _ in }
        )
        await unavailable.start()
        XCTAssertEqual(unavailable.state, .failed(.unavailable))
    }
}

final class M11ReadAloudRequestTests: XCTestCase {
    func testOfficialRESTRequestIsBoundedAuthenticatedAndProfileScoped() throws {
        let request = try SpeechSynthesisRequestPolicy.makeRequest(
            origin: URL(string: "https://hermes.example")!,
            accessToken: "secret-token",
            profile: String(repeating: "p", count: 80),
            text: " Hello there "
        )

        XCTAssertEqual(request.url?.path, "/api/audio/speak")
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value?.count, 64)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        let body = try JSONDecoder().decode(SpeechSynthesisRequest.self, from: request.httpBody!)
        XCTAssertEqual(body.text, "Hello there")
    }

    func testRequestRejectsBlankAndBoundsOversizedText() throws {
        XCTAssertThrowsError(try SpeechSynthesisRequestPolicy.makeRequest(
            origin: URL(string: "https://hermes.example")!, accessToken: "t", profile: "default", text: "  "
        ))
        let request = try SpeechSynthesisRequestPolicy.makeRequest(
            origin: URL(string: "https://hermes.example")!, accessToken: "t", profile: "default",
            text: String(repeating: "x", count: SpeechSynthesisRequestPolicy.maxTextCharacters + 10)
        )
        let body = try JSONDecoder().decode(SpeechSynthesisRequest.self, from: request.httpBody!)
        XCTAssertEqual(body.text.count, SpeechSynthesisRequestPolicy.maxTextCharacters)
    }

    func testResponseDecodesOnlyBoundedBase64AudioDataURLs() throws {
        let response = SpeechSynthesisResponse(
            ok: true,
            dataURL: "data:audio/wav;base64,\(Data([9, 8, 7]).base64EncodedString())",
            mimeType: "audio/wav"
        )
        let data = try JSONEncoder().encode(response)
        let audio = try SpeechSynthesisRequestPolicy.decodeResponse(data: data)
        XCTAssertEqual(audio.data, Data([9, 8, 7]))
        XCTAssertEqual(audio.mimeType, "audio/wav")

        let minimal = Data(#"{"data_url":"data:audio/mpeg;base64,CQgH"}"#.utf8)
        XCTAssertEqual(try SpeechSynthesisRequestPolicy.decodeResponse(data: minimal).data, Data([9, 8, 7]))

        XCTAssertThrowsError(try SpeechSynthesisRequestPolicy.decodeResponse(data: Data(count: SpeechSynthesisRequestPolicy.maxResponseBytes + 1)))
        XCTAssertThrowsError(try SpeechSynthesisRequestPolicy.decodeResponse(data: Data(#"{"ok":true,"data_url":"https://evil.test/a.mp3"}"#.utf8)))
    }

    func testHTTPClassificationIsClosedAndStable() {
        XCTAssertEqual(SpeechSynthesisError.classify(statusCode: 401), .authenticationRejected)
        XCTAssertEqual(SpeechSynthesisError.classify(statusCode: 404), .unsupported)
        XCTAssertEqual(SpeechSynthesisError.classify(statusCode: 422), .requestRejected(statusCode: 422))
        XCTAssertEqual(SpeechSynthesisError.classify(statusCode: 429), .transient(statusCode: 429))
        XCTAssertEqual(SpeechSynthesisError.classify(statusCode: 503), .transient(statusCode: 503))
    }
}

@MainActor
final class M11ReadAloudLifecycleTests: XCTestCase {
    private final class FakePlayer: SpeechAudioPlaying {
        var playCalls: [Data] = []
        var stopCalls = 0
        var onFinished: (() -> Void)?
        func play(data: Data, onFinished: @escaping () -> Void) throws {
            playCalls.append(data)
            self.onFinished = onFinished
        }
        func stop() { stopCalls += 1; onFinished = nil }
    }

    func testReplacementStopsCurrentOwnerAndStaleSynthesisCannotPlay() async {
        let player = FakePlayer()
        var continuations: [CheckedContinuation<SpeechAudio, Error>] = []
        let controller = ReadAloudController(player: player) { _ in
            try await withCheckedThrowingContinuation { continuations.append($0) }
        }

        controller.toggle(messageID: "one", text: "First")
        await Task.yield()
        controller.toggle(messageID: "two", text: "Second")
        await Task.yield()
        XCTAssertEqual(controller.state, .preparing(messageID: "two"))
        XCTAssertGreaterThanOrEqual(player.stopCalls, 2)

        continuations[1].resume(returning: SpeechAudio(data: Data([2]), mimeType: "audio/mpeg"))
        await Task.yield()
        continuations[0].resume(returning: SpeechAudio(data: Data([1]), mimeType: "audio/mpeg"))
        await Task.yield()

        XCTAssertEqual(player.playCalls, [Data([2])])
        XCTAssertEqual(controller.state, .playing(messageID: "two"))
    }

    func testTogglingActiveOwnerStopsAndNaturalCompletionReturnsIdle() async throws {
        let player = FakePlayer()
        let controller = ReadAloudController(player: player) { _ in
            SpeechAudio(data: Data([1]), mimeType: "audio/mpeg")
        }
        controller.toggle(messageID: "one", text: "First")
        await Task.yield()
        XCTAssertEqual(controller.state, .playing(messageID: "one"))
        player.onFinished?()
        XCTAssertEqual(controller.state, .idle)

        controller.toggle(messageID: "one", text: "First")
        await Task.yield()
        controller.toggle(messageID: "one", text: "First")
        XCTAssertEqual(controller.state, .idle)
    }
}
