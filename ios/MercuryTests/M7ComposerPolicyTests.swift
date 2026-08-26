import XCTest
@testable import Mercury

final class M7ComposerPolicyTests: XCTestCase {
    func testNormalDraftSubmitsPrompt() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "  Ship it  ", turnActive: false, hasAttachments: false),
            .submit(text: "Ship it")
        )
    }

    func testActiveTurnRoutesPlainGuidanceToSteer() {
        XCTAssertEqual(
            M7ComposerPolicy.route(draft: "  Focus on the failing test  ", turnActive: true, hasAttachments: false),
            .steer(text: "Focus on the failing test")
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
