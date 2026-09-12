import XCTest
@testable import Mercury

final class M7ComposerPolicyTests: XCTestCase {
    @MainActor
    func testRejectedQueueRestoresAfterNewerDraftBecomesEmptyOnlyOnce() throws {
        let state = ChatSessionState(sessionID: "session", title: "", isNewSession: false, incomingShare: nil)
        let queued = state.queuedPromptState
        let attempt = try XCTUnwrap(queued.lifecycle.begin(draft: "Rejected original"))
        state.draft = "Newer draft"
        guard case .restoreDraft(let original) = queued.lifecycle.resolve(attempt: attempt, accepted: false) else {
            return XCTFail("Expected explicit rejection")
        }
        queued.draftToRestore = original
        state.restoreDeferredQueuedDraft() // Production receipt observer.
        XCTAssertEqual(state.draft, "Newer draft")
        XCTAssertEqual(queued.draftToRestore, original)
        state.draft = "" // Delete or normal send clears the newer composer.
        XCTAssertEqual(state.draft, original)
        XCTAssertNil(queued.draftToRestore)
        state.draft = ""
        XCTAssertEqual(state.draft, "", "Recovered text must never replay")
    }

    @MainActor
    func testComposerBindingClearsUnsupportedQueueErrorBeforeNormalSend() throws {
        let state = ChatSessionState(sessionID: "session", title: "", isNewSession: false, incomingShare: nil)
        state.queuedPromptState.error = "This connection does not support queued prompts."
        state.composerError = "Previous error"
        let binding = state.composerErrorBinding
        XCTAssertEqual(binding.wrappedValue, state.queuedPromptState.error)
        binding.wrappedValue = nil // ComposerBar.send's production binding contract.
        XCTAssertNil(state.composerErrorBinding.wrappedValue)
        XCTAssertNil(state.queuedPromptState.error)
        XCTAssertNil(state.composerError)
    }

    @MainActor
    func testStaleComposerBindingCannotClearNewerAttemptOrScope() throws {
        let state = ChatSessionState(sessionID: "session", title: "", isNewSession: false, incomingShare: nil)
        state.queuedPromptState.error = "Old rejection"
        let oldBinding = state.composerErrorBinding
        _ = try XCTUnwrap(state.queuedPromptState.lifecycle.begin(draft: "New attempt"))
        state.queuedPromptState.error = "New uncertainty"
        state.composerError = "New composer error"
        oldBinding.wrappedValue = nil
        XCTAssertEqual(state.composerError, "New composer error")
        XCTAssertEqual(state.queuedPromptState.error, "New uncertainty")
        let otherBinding = state.composerErrorBinding
        state.queuedPromptState = QueuedPromptState()
        state.queuedPromptState.error = "Other scope"
        state.composerError = "Other composer error"
        otherBinding.wrappedValue = nil
        XCTAssertEqual(state.composerError, "Other composer error")
        XCTAssertEqual(state.queuedPromptState.error, "Other scope")
    }

    @MainActor
    func testProductionDiscardClearsErrorWithoutRestorationAndFencesNewAttempt() throws {
        let state = ChatSessionState(sessionID: "session", title: "", isNewSession: false, incomingShare: nil)
        let queued = state.queuedPromptState
        let old = try XCTUnwrap(queued.lifecycle.begin(draft: "Unconfirmed"))
        queued.uncertain = true
        queued.error = "Unconfirmed queue"
        let discard = try XCTUnwrap(state.discardUncertainQueue)
        discard()
        XCTAssertNil(state.composerErrorBinding.wrappedValue)
        XCTAssertFalse(queued.lifecycle.hasPendingAttempt)
        state.restoreDeferredQueuedDraft()
        XCTAssertEqual(state.draft, "")
        _ = try XCTUnwrap(queued.lifecycle.begin(draft: "Newer"))
        queued.uncertain = true
        queued.error = "New uncertainty"
        discard()
        XCTAssertTrue(queued.lifecycle.hasPendingAttempt)
        XCTAssertEqual(queued.error, "New uncertainty")
        XCTAssertEqual(queued.lifecycle.resolve(attempt: old, accepted: true), .stale)
    }

    @MainActor
    func testAcceptedQueueNeverRestoresWhenNewerComposerEmpties() throws {
        let state = ChatSessionState(sessionID: "session", title: "", isNewSession: false, incomingShare: nil)
        let attempt = try XCTUnwrap(state.queuedPromptState.lifecycle.begin(draft: "Accepted"))
        state.draft = "Newer"
        XCTAssertEqual(state.queuedPromptState.lifecycle.resolve(attempt: attempt, accepted: true), .accepted)
        state.restoreDeferredQueuedDraft()
        state.draft = ""
        state.restoreDeferredQueuedDraft()
        XCTAssertEqual(state.draft, "")
        XCTAssertNil(state.queuedPromptState.draftToRestore)
    }

    @MainActor
    func testQueuedReceiptSurvivesPresentationReleaseAndRemainsScopeIsolated() throws {
        let store = QueuedPromptStateStore()
        let scope = QueuedPromptScope(relayTargetID: nil, origin: "https://hermes.example", profile: "default", durableID: "session")
        var departing: QueuedPromptState? = QueuedPromptState()
        let attempt = try XCTUnwrap(departing!.lifecycle.begin(draft: "Unconfirmed next task"))
        XCTAssertTrue(store.retain(departing!, for: scope))
        weak var original = departing
        departing = nil
        let reopened = try XCTUnwrap(store.state(for: scope))
        XCTAssertTrue(reopened === original)
        reopened.uncertain = true
        reopened.error = "Queue acknowledgement unavailable"
        XCTAssertTrue(reopened.lifecycle.hasPendingAttempt)
        XCTAssertNil(reopened.lifecycle.begin(draft: "Duplicate"))
        XCTAssertNil(store.state(for: .init(relayTargetID: nil, origin: scope.origin, profile: "other", durableID: "session")))
        XCTAssertNil(store.state(for: .init(relayTargetID: UUID(), origin: scope.origin, profile: "default", durableID: "session")))
        XCTAssertEqual(reopened.lifecycle.resolve(attempt: attempt, accepted: true), .accepted)
        XCTAssertFalse(reopened.lifecycle.hasPendingAttempt)
    }

    @MainActor
    func testQueueStoreBoundsNeverEvictUnconfirmedWork() throws {
        let store = QueuedPromptStateStore()
        for index in 0..<QueuedPromptStateStore.maxEntries {
            let state = QueuedPromptState()
            _ = state.lifecycle.begin(draft: "Held")
            XCTAssertTrue(store.retain(state, for: .init(relayTargetID: nil, origin: "https://hermes.example", profile: "default", durableID: "session-\(index)")))
        }
        XCTAssertFalse(store.retain(QueuedPromptState(), for: .init(relayTargetID: nil, origin: "https://hermes.example", profile: "default", durableID: "overflow")))
        let first = try XCTUnwrap(store.state(for: .init(relayTargetID: nil, origin: "https://hermes.example", profile: "default", durableID: "session-0")))
        XCTAssertTrue(first.lifecycle.hasPendingAttempt)
    }

    @MainActor
    func testQueueStoreAuthenticationCleanupIsScoped() {
        let store = QueuedPromptStateStore()
        let direct = QueuedPromptScope(relayTargetID: nil, origin: "https://hermes.example", profile: "default", durableID: "session")
        let relay = QueuedPromptScope(relayTargetID: UUID(), origin: direct.origin, profile: "default", durableID: "session")
        store.retain(QueuedPromptState(), for: direct)
        store.retain(QueuedPromptState(), for: relay)
        store.remove(origin: direct.origin)
        XCTAssertNil(store.state(for: direct))
        XCTAssertNotNil(store.state(for: relay))
    }

    func testNormalDraftSubmitsPrompt() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "  Ship it  ", turnActive: false, hasAttachments: false),
            .submit(text: "Ship it")
        )
    }

    func testActiveTurnRoutesPlainMessageToQueueWithoutSteering() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "  Focus on the failing test  ", turnActive: true, hasAttachments: false),
            .queue(text: "Focus on the failing test")
        )
    }

    func testSteerCommandStripsOnlyTheLocalCommandToken() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "/steer   preserve the public API", turnActive: true, hasAttachments: false),
            .steer(text: "preserve the public API")
        )
    }

    func testBlankSteerIsRejectedLocally() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: " /steer   ", turnActive: true, hasAttachments: false),
            .reject(.blankSteer)
        )
    }

    func testAttachmentsAreRejectedDuringSteering() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "Use this", turnActive: true, hasAttachments: true),
            .reject(.attachmentsUnavailableWhileSteering)
        )
    }

    func testLocalCommandsRemainLocalDuringActiveTurn() {
        XCTAssertEqual(M7ComposerPolicy.route(draft: "/model", turnActive: true, hasAttachments: true), .openModelPicker)
        XCTAssertEqual(M7ComposerPolicy.route(draft: "/reasoning HIGH", turnActive: true, hasAttachments: true), .setReasoning(effort: "high"))
    }

    func testExplicitDiscardDoesNotRestoreOrLetOldAckSettleNewAttempt() throws {
        var lifecycle = QueuedPromptLifecycle()
        let old = try XCTUnwrap(lifecycle.begin(draft: "Unconfirmed"))
        XCTAssertTrue(lifecycle.hasPendingAttempt)
        lifecycle.discard()
        XCTAssertFalse(lifecycle.hasPendingAttempt)
        _ = try XCTUnwrap(lifecycle.begin(draft: "Different prompt"))
        XCTAssertEqual(lifecycle.resolve(attempt: old, accepted: true), .stale)
        XCTAssertTrue(lifecycle.hasPendingAttempt)
    }

    func testQueuedPromptLifecycleRestoresOnlyTheFailedOwnedDraft() throws {
        var lifecycle = QueuedPromptLifecycle()
        let first = try XCTUnwrap(lifecycle.begin(draft: "queued follow-up"))
        XCTAssertNil(lifecycle.begin(draft: "duplicate"))
        XCTAssertEqual(lifecycle.resolve(attempt: first, accepted: false), .restoreDraft("queued follow-up"))

        let second = try XCTUnwrap(lifecycle.begin(draft: "accepted follow-up"))
        XCTAssertEqual(lifecycle.resolve(attempt: second, accepted: true), .accepted)
        XCTAssertEqual(lifecycle.resolve(attempt: first, accepted: false), .stale)
    }

    func testExactModelCommandOpensPickerLocally() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "  /model\n", turnActive: false, hasAttachments: false),
            .openModelPicker
        )
    }

    func testReasoningCommandAppliesLocally() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "/reasoning HIGH", turnActive: false, hasAttachments: false),
            .setReasoning(effort: "high")
        )
    }

    func testSlashCompletionEligibilityRequiresLiveConnectionAndCommandContext() {
        XCTAssertTrue(M7ComposerPolicy.shouldRequestSlashCompletion(text: "/help", connectionIsLive: true))
        XCTAssertFalse(M7ComposerPolicy.shouldRequestSlashCompletion(text: " /help", connectionIsLive: true))
        XCTAssertFalse(M7ComposerPolicy.shouldRequestSlashCompletion(text: "/help", connectionIsLive: false))
    }

    func testOnlyLatestSlashGenerationMayPublish() {
        XCTAssertTrue(M7ComposerPolicy.mayPublishSlashCompletion(responseGeneration: 7, currentGeneration: 7))
        XCTAssertFalse(M7ComposerPolicy.mayPublishSlashCompletion(responseGeneration: 6, currentGeneration: 7))
    }

    func testActiveEmptyComposerUsesStopButtonLikeAndroid() {
        XCTAssertTrue(M7ComposerPolicy.shouldShowStopButton(isSending: true, turnActive: false, draft: ""))
        XCTAssertTrue(M7ComposerPolicy.shouldShowStopButton(isSending: true, turnActive: false, draft: "  \n"))
        XCTAssertTrue(
            M7ComposerPolicy.shouldShowStopButton(isSending: false, turnActive: true, draft: ""),
            "an observed active turn must keep Stop visible while the local submission flag catches up"
        )
        XCTAssertFalse(M7ComposerPolicy.shouldShowStopButton(isSending: false, turnActive: false, draft: ""))
        XCTAssertFalse(M7ComposerPolicy.shouldShowStopButton(isSending: true, turnActive: true, draft: "guide this turn"))
    }

    func testAcceptedSubmissionDoesNotRestoreClearedDraft() {
        XCTAssertFalse(M7ComposerPolicy.shouldRestoreDraftAfterSubmissionFailure(submissionAccepted: true))
    }

    func testRejectedSubmissionStillRestoresDraft() {
        XCTAssertTrue(M7ComposerPolicy.shouldRestoreDraftAfterSubmissionFailure(submissionAccepted: false))
    }
}
