import XCTest
@testable import Mercury

final class NotificationPolicyTests: XCTestCase {
    private let sessionID = "session-1"
    private let sessionTitle = "A useful session"
    private let background = SessionNotificationVisibility()

    private func complete(
        sessionID: String = "session-1",
        text: String? = "The response is complete.",
        status: String? = nil
    ) -> ChatEvent {
        .messageComplete(
            sessionID: sessionID,
            text: text,
            status: status,
            error: nil,
            reasoning: nil,
            warning: nil,
            failureReason: nil,
            recoverable: false,
            billing: nil
        )
    }

    private func approval(
        description: String? = "Allow this command?",
        command: String? = "do-the-thing"
    ) -> ChatEvent {
        .approvalRequest(
            sessionID: sessionID,
            requestID: "approval-1",
            command: command,
            description: description,
            choices: ["allow", "deny"]
        )
    }

    private func clarify(question: String = "Which option?") -> ChatEvent {
        .clarifyRequest(
            sessionID: sessionID,
            requestID: "clarify-1",
            question: question,
            choices: ["one", "two"],
            multiSelect: false
        )
    }

    private func secure(kind: UnsupportedBlockingKind = .secret, prompt: String? = "Enter the secret") -> ChatEvent {
        .unsupportedBlockingRequest(
            sessionID: sessionID,
            kind: kind,
            requestID: "secure-1",
            prompt: prompt
        )
    }

    // MARK: - Text policy

    func testFinalResponsePreviewStripsHeadingsMarkdownAndBlankLines() {
        let text = """
        # Heading

        **First** line with __emphasis__ and `code`.
        ## Another heading
          Second line
        ```
        Third line
        ```
        Fourth line
        """

        XCTAssertEqual(
            NotificationTextPolicy.finalResponsePreview(text),
            "First line with emphasis and code.\nSecond line\nThird line"
        )
    }

    func testFinalResponsePreviewUsesAtMostRequestedNumberOfLines() {
        let text = "one\ntwo\nthree\nfour"

        XCTAssertEqual(NotificationTextPolicy.finalResponsePreview(text, maxLines: 2), "one\ntwo")
        XCTAssertEqual(NotificationTextPolicy.finalResponsePreview(text, maxLines: 0), "one")
    }

    func testFinalResponsePreviewCapsAt240Characters() {
        let text = String(repeating: "x", count: 300)

        XCTAssertEqual(NotificationTextPolicy.finalResponsePreview(text).count, 240)
    }

    func testFinalResponsePreviewEmptyOrHeadingOnlyUsesFallback() {
        XCTAssertEqual(NotificationTextPolicy.finalResponsePreview(" \n\t"), "Response completed")
        XCTAssertEqual(NotificationTextPolicy.finalResponsePreview("# Only a heading"), "Response completed")
    }

    func testInputPreviewTrimsAndCapsRawText() {
        let text = "  " + String(repeating: "x", count: 300) + "  "

        // The Android policy takes the first 240 raw characters before the
        // surrounding whitespace is trimmed, leaving 238 visible x's here.
        XCTAssertEqual(NotificationTextPolicy.inputPreview(text).count, 238)
        XCTAssertFalse(NotificationTextPolicy.inputPreview(text).hasPrefix(" "))
    }

    func testCompletionHeadings() {
        XCTAssertEqual(NotificationTextPolicy.completionHeading(status: .finished), "Mercury finished")
        XCTAssertEqual(NotificationTextPolicy.completionHeading(status: .failed), "Mercury task failed")
        XCTAssertEqual(NotificationTextPolicy.completionHeading(status: .cancelled), "Mercury task was cancelled")
    }

    /// The interrupt sentinel is cancellation metadata; the preview falls
    /// back to the generic completion text instead of quoting it.
    func testFinalResponsePreviewSuppressesInterruptSentinel() {
        let sentinel = "Operation interrupted: waiting for model response (3.0s elapsed)."
        XCTAssertEqual(NotificationTextPolicy.finalResponsePreview(sentinel), "Response completed")
    }

    func testCompletionStatusMapsWireValues() {
        let failed = ["error", "ERROR", "failed"]
        let cancelled = ["cancelled", "canceled", "interrupted"]

        for value in failed {
            XCTAssertEqual(NotificationTextPolicy.completionStatus(fromWire: value), .failed, value)
        }
        for value in cancelled {
            XCTAssertEqual(NotificationTextPolicy.completionStatus(fromWire: value), .cancelled, value)
        }
        XCTAssertEqual(NotificationTextPolicy.completionStatus(fromWire: nil), .finished)
        XCTAssertEqual(NotificationTextPolicy.completionStatus(fromWire: "unknown"), .finished)
    }

    func testInputHeadings() {
        XCTAssertEqual(NotificationTextPolicy.inputHeading(for: .approval), "Hermes needs approval")
        XCTAssertEqual(NotificationTextPolicy.inputHeading(for: .clarification), "Hermes needs your input")
        XCTAssertEqual(NotificationTextPolicy.inputHeading(for: .secureInput), "Hermes needs secure input")
    }

    // MARK: - Visibility policy

    func testShouldPostSuppressesVisibleForegroundSession() {
        let visibility = SessionNotificationVisibility(appForeground: true, visibleSessionID: sessionID)

        XCTAssertFalse(NotificationVisibilityPolicy.shouldPost(sessionID: sessionID, visibility: visibility))
    }

    func testShouldPostAllowsForegroundDifferentSession() {
        let visibility = SessionNotificationVisibility(appForeground: true, visibleSessionID: "other")

        XCTAssertTrue(NotificationVisibilityPolicy.shouldPost(sessionID: sessionID, visibility: visibility))
    }

    func testShouldPostAllowsBackgroundSession() {
        let visibility = SessionNotificationVisibility(appForeground: false, visibleSessionID: sessionID)

        XCTAssertTrue(NotificationVisibilityPolicy.shouldPost(sessionID: sessionID, visibility: visibility))
    }

    func testShouldPostAllowsForegroundWithoutVisibleSession() {
        let visibility = SessionNotificationVisibility(appForeground: true, visibleSessionID: nil)

        XCTAssertTrue(NotificationVisibilityPolicy.shouldPost(sessionID: sessionID, visibility: visibility))
    }

    // MARK: - Live-event reducer

    func testCompletionInBackgroundProducesNotification() {
        var watermark = SessionWatermark(sessionID: sessionID)

        let notification = NotificationDecisionReducer.decide(
            event: complete(),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )

        XCTAssertEqual(notification?.sessionID, sessionID)
        XCTAssertEqual(notification?.sessionTitle, sessionTitle)
        XCTAssertEqual(notification?.kind, .completion(status: .finished))
        XCTAssertEqual(notification?.heading, "Mercury finished")
        XCTAssertEqual(notification?.body, "The response is complete.")
        XCTAssertEqual(watermark.lastMessageCount, 1)
        XCTAssertNotNil(watermark.lastCompletedTurnSignature)
    }

    func testVisibleForegroundCompletionIsSuppressedButRecordedAndRemainsDeduped() {
        var watermark = SessionWatermark(sessionID: sessionID)
        let visibility = SessionNotificationVisibility(appForeground: true, visibleSessionID: sessionID)

        let first = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: visibility, watermark: &watermark
        )
        let signature = watermark.lastCompletedTurnSignature
        let second = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )

        XCTAssertNil(first)
        XCTAssertNil(second)
        XCTAssertEqual(watermark.lastMessageCount, 1)
        XCTAssertNotNil(signature)
        XCTAssertEqual(watermark.lastCompletedTurnSignature, signature)
    }

    func testDuplicateCompletionIsSilent() {
        var watermark = SessionWatermark(sessionID: sessionID)

        let first = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )
        let second = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )

        XCTAssertNotNil(first)
        XCTAssertNil(second)
        XCTAssertEqual(watermark.lastMessageCount, 1)
    }

    func testSameResponseInNewTurnCanNotifyAfterMessageStart() {
        var watermark = SessionWatermark(sessionID: sessionID)

        _ = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )
        _ = NotificationDecisionReducer.decide(
            event: .messageStart(sessionID: sessionID, text: nil),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        let secondTurn = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )

        XCTAssertNotNil(secondTurn)
        XCTAssertEqual(watermark.lastMessageCount, 2)
    }

    func testFailedAndCancelledCompletionsUseStatusMapping() {
        var failedWatermark = SessionWatermark(sessionID: sessionID)
        var cancelledWatermark = SessionWatermark(sessionID: sessionID)

        let failed = NotificationDecisionReducer.decide(
            event: complete(text: "failed", status: "error"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &failedWatermark
        )
        let cancelled = NotificationDecisionReducer.decide(
            event: complete(text: "cancelled", status: "interrupted"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &cancelledWatermark
        )

        XCTAssertEqual(failed?.kind, .completion(status: .failed))
        XCTAssertEqual(failed?.heading, "Mercury task failed")
        XCTAssertEqual(cancelled?.kind, .completion(status: .cancelled))
        XCTAssertEqual(cancelled?.heading, "Mercury task was cancelled")
    }

    func testApprovalUsesDescriptionAndDedupesUntilExpire() {
        var watermark = SessionWatermark(sessionID: sessionID)

        let first = NotificationDecisionReducer.decide(
            event: approval(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )
        let duplicate = NotificationDecisionReducer.decide(
            event: approval(description: nil, command: "fallback command"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        _ = NotificationDecisionReducer.decide(
            event: .approvalExpire(sessionID: sessionID, requestID: "approval-1"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        let afterExpire = NotificationDecisionReducer.decide(
            event: approval(description: nil, command: "fallback command"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )

        XCTAssertEqual(first?.kind, .approval)
        XCTAssertEqual(first?.body, "Allow this command?")
        XCTAssertNil(duplicate)
        XCTAssertEqual(afterExpire?.body, "fallback command")
        XCTAssertTrue(watermark.hasOpenApproval)
    }

    func testApprovalFallsBackWhenDescriptionAndCommandAreMissing() {
        var watermark = SessionWatermark(sessionID: sessionID)

        let notification = NotificationDecisionReducer.decide(
            event: approval(description: nil, command: nil),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )

        XCTAssertEqual(notification?.body, "Authorization is required to continue")
    }

    func testClarificationDedupesAndReopensAfterExpire() {
        var watermark = SessionWatermark(sessionID: sessionID)

        let first = NotificationDecisionReducer.decide(
            event: clarify(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )
        let duplicate = NotificationDecisionReducer.decide(
            event: clarify(question: "A different question"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        _ = NotificationDecisionReducer.decide(
            event: .clarifyExpire(sessionID: sessionID, requestID: "clarify-1"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        let reopened = NotificationDecisionReducer.decide(
            event: clarify(question: "A different question"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )

        XCTAssertEqual(first?.kind, .clarification)
        XCTAssertEqual(first?.body, "Which option?")
        XCTAssertNil(duplicate)
        XCTAssertEqual(reopened?.body, "A different question")
    }

    func testSecureInputSecretAndSudoShareLifecycleAndFallback() {
        var watermark = SessionWatermark(sessionID: sessionID)

        let secret = NotificationDecisionReducer.decide(
            event: secure(kind: .secret), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )
        let duplicate = NotificationDecisionReducer.decide(
            event: secure(kind: .sudo, prompt: "sudo password"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        _ = NotificationDecisionReducer.decide(
            event: .unsupportedBlockingExpire(sessionID: sessionID, kind: .secret, requestID: "secure-1"),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )
        let sudo = NotificationDecisionReducer.decide(
            event: secure(kind: .sudo, prompt: nil),
            sessionTitle: sessionTitle,
            visibility: background,
            watermark: &watermark
        )

        XCTAssertEqual(secret?.kind, .secureInput)
        XCTAssertEqual(secret?.body, "Enter the secret")
        XCTAssertNil(duplicate)
        XCTAssertEqual(sudo?.body, "Secure input required")
    }

    func testNonNotificationEventsAreSilent() {
        var watermark = SessionWatermark(sessionID: sessionID)
        let events: [ChatEvent] = [
            .messageStart(sessionID: sessionID, text: "start"),
            .messageDelta(sessionID: sessionID, text: "delta"),
            .reasoningDelta(sessionID: sessionID, text: "thinking", replace: false),
            .toolStart(sessionID: sessionID, toolID: "tool-1", name: "shell", context: nil),
            .unsupportedBlockingRequest(sessionID: sessionID, kind: .terminalRead, requestID: "terminal-1", prompt: "read")
        ]

        for event in events {
            XCTAssertNil(
                NotificationDecisionReducer.decide(
                    event: event,
                    sessionTitle: sessionTitle,
                    visibility: background,
                    watermark: &watermark
                )
            )
        }
        XCTAssertEqual(watermark.lastMessageCount, 0)
        XCTAssertFalse(watermark.hasOpenSecure)
    }

    func testCompletionClearsOpenInputFlags() {
        var watermark = SessionWatermark(
            sessionID: sessionID,
            lastCompletedTurnSignature: nil,
            lastMessageCount: 0,
            hasOpenApproval: true,
            hasOpenClarify: true,
            hasOpenSecure: true
        )

        _ = NotificationDecisionReducer.decide(
            event: complete(), sessionTitle: sessionTitle, visibility: background, watermark: &watermark
        )

        XCTAssertFalse(watermark.hasOpenApproval)
        XCTAssertFalse(watermark.hasOpenClarify)
        XCTAssertFalse(watermark.hasOpenSecure)
    }

    // MARK: - Reconciliation engine

    private func completionDelta(
        sessionID: String = "session-1",
        title: String = "A useful session",
        text: String = "done",
        status: CompletionStatus = .finished,
        signature: String = "turn-1"
    ) -> ReconciliationDelta {
        ReconciliationDelta(
            sessionID: sessionID,
            sessionTitle: title,
            serverMessageCount: 2,
            newCompletion: CompletionOutcome(text: text, status: status, turnSignature: signature),
            openedApproval: false,
            openedClarify: false,
            openedSecure: false
        )
    }

    private func openDelta(
        sessionID: String = "session-1",
        title: String = "A useful session",
        approval: Bool = false,
        clarify: Bool = false,
        secure: Bool = false
    ) -> ReconciliationDelta {
        ReconciliationDelta(
            sessionID: sessionID,
            sessionTitle: title,
            serverMessageCount: 1,
            newCompletion: nil,
            openedApproval: approval,
            openedClarify: clarify,
            openedSecure: secure
        )
    }

    func testReconciliationCompletionForNewSessionNotifiesOnce() {
        var watermarks: [String: SessionWatermark] = [:]
        let delta = completionDelta()

        let first = ReconciliationEngine.reconcile(
            deltas: [delta], visibility: background, watermarks: &watermarks
        )
        let second = ReconciliationEngine.reconcile(
            deltas: [delta], visibility: background, watermarks: &watermarks
        )

        XCTAssertEqual(first.count, 1)
        XCTAssertEqual(first[0].kind, .completion(status: .finished))
        XCTAssertTrue(second.isEmpty)
        XCTAssertEqual(watermarks[sessionID]?.lastCompletedTurnSignature, "turn-1")
        XCTAssertEqual(watermarks[sessionID]?.lastMessageCount, 1)
    }

    func testReconciliationVisibleCompletionIsSuppressedButWatermarkAdvances() {
        var watermarks: [String: SessionWatermark] = [:]
        let visibility = SessionNotificationVisibility(appForeground: true, visibleSessionID: sessionID)

        let notifications = ReconciliationEngine.reconcile(
            deltas: [completionDelta()], visibility: visibility, watermarks: &watermarks
        )

        XCTAssertTrue(notifications.isEmpty)
        XCTAssertEqual(watermarks[sessionID]?.lastCompletedTurnSignature, "turn-1")
        XCTAssertEqual(watermarks[sessionID]?.lastMessageCount, 1)
    }

    func testReconciliationApprovalNotifiesOnceAndPreservesCompletionOrdering() {
        var watermarks: [String: SessionWatermark] = [:]
        let deltas = [
            completionDelta(signature: "turn-1"),
            openDelta(approval: true),
            completionDelta(text: "second", signature: "turn-2")
        ]

        let first = ReconciliationEngine.reconcile(
            deltas: deltas, visibility: background, watermarks: &watermarks
        )
        XCTAssertEqual(first.map(\.kind), [
            .completion(status: .finished),
            .approval,
            .completion(status: .finished)
        ])
        XCTAssertEqual(first.map(\.dedupeKey), [
            "session-1|completion|turn-1",
            "session-1|approval",
            "session-1|completion|turn-2"
        ])

        var approvalWatermarks: [String: SessionWatermark] = [:]
        let approvalFirst = ReconciliationEngine.reconcile(
            deltas: [openDelta(approval: true)],
            visibility: background,
            watermarks: &approvalWatermarks
        )
        let approvalSecond = ReconciliationEngine.reconcile(
            deltas: [openDelta(approval: true)],
            visibility: background,
            watermarks: &approvalWatermarks
        )
        XCTAssertEqual(approvalFirst.map(\.kind), [.approval])
        XCTAssertTrue(approvalSecond.isEmpty)
    }

    func testReconciliationClarifyAndSecureFlagsAreIdempotent() {
        var watermarks: [String: SessionWatermark] = [:]
        let delta = openDelta(clarify: true, secure: true)

        let first = ReconciliationEngine.reconcile(
            deltas: [delta], visibility: background, watermarks: &watermarks
        )
        let second = ReconciliationEngine.reconcile(
            deltas: [delta], visibility: background, watermarks: &watermarks
        )

        XCTAssertEqual(first.map(\.kind), [.clarification, .secureInput])
        XCTAssertTrue(second.isEmpty)
        XCTAssertTrue(watermarks[sessionID]?.hasOpenClarify == true)
        XCTAssertTrue(watermarks[sessionID]?.hasOpenSecure == true)
    }

    func testSessionWatermarkCodableRoundTrips() throws {
        let original = SessionWatermark(
            sessionID: sessionID,
            lastCompletedTurnSignature: "turn-1",
            lastMessageCount: 3,
            hasOpenApproval: true,
            hasOpenClarify: false,
            hasOpenSecure: true
        )
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(SessionWatermark.self, from: data)

        XCTAssertEqual(decoded, original)
    }
}
