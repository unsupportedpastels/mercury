import XCTest
@testable import Mercury

final class TranscriptPresentationPolicyTests: XCTestCase {
    func testCompletedToolNamesCollapseIntoAndroidVerbSummary() {
        XCTAssertEqual(
            TranscriptPresentationPolicy.toolActivitySummary(
                completedNames: ["read_file", "terminal", "terminal"]
            ),
            "Ran 2 commands, read a file"
        )
        XCTAssertEqual(
            TranscriptPresentationPolicy.toolActivitySummary(
                completedNames: ["process", "process"]
            ),
            "Process ×2"
        )
    }

    func testRunningToolsLeadSummaryAndUnknownOverflowIsBounded() {
        XCTAssertEqual(
            TranscriptPresentationPolicy.toolActivitySummary(
                completedNames: ["write_file", "web_search", "mystery", "other"],
                runningNames: ["terminal"]
            ),
            "Running terminal, edited a file, searched the web, +2 more"
        )
    }

    func testUnifiedActivitySummaryIncludesToolsTasksAndProcesses() {
        XCTAssertEqual(
            TranscriptPresentationPolicy.activitySummary(
                toolCount: 3,
                completedTodos: 1,
                todoCount: 2,
                processCount: 1
            ),
            "Activity · 3 tools · 1/2 tasks · 1 process-local process"
        )
    }

    func testReasoningOnlyAssistantRowDoesNotRenderEmptyBubbleOrPlayback() {
        XCTAssertFalse(TranscriptPresentationPolicy.shouldRenderMessageBubble(role: "assistant", text: "  "))
        XCTAssertFalse(TranscriptPresentationPolicy.shouldShowPlaybackControl(
            enabled: true, role: "assistant", text: "", completed: true
        ))
        XCTAssertTrue(TranscriptPresentationPolicy.shouldShowPlaybackControl(
            enabled: true, role: "assistant", text: "Final answer", completed: true
        ))
        XCTAssertFalse(TranscriptPresentationPolicy.shouldShowPlaybackControl(
            enabled: false, role: "assistant", text: "Final answer", completed: true
        ))
    }

    func testReasoningDisplayRemovesMarkdownMarkersAndMediaTransportDirectives() {
        XCTAssertEqual(
            TranscriptPresentationPolicy.reasoningDisplayText(
                "**Planning code fix and UI tests**\nMEDIA:/tmp/private/render.png"
            ),
            "Planning code fix and UI tests"
        )
        XCTAssertEqual(
            TranscriptPresentationPolicy.reasoningPreview(
                "**Planning unified activity stack implementation**\nMore detail"
            ),
            "Planning unified activity stack implementation More detail"
        )
        XCTAssertEqual(
            TranscriptPresentationPolicy.reasoningDisplayText("MEDIA:/tmp/private/render.png"),
            ""
        )
    }
}

final class VoiceDisplayPreferencesTests: XCTestCase {
    func testPlaybackControlsDefaultOffAndPersistOptIn() {
        let suite = "VoiceDisplayPreferencesTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = VoiceDisplayPreferences(defaults: defaults)

        XCTAssertFalse(preferences.showMessagePlaybackControls)
        preferences.showMessagePlaybackControls = true
        XCTAssertTrue(VoiceDisplayPreferences(defaults: defaults).showMessagePlaybackControls)
    }
}
