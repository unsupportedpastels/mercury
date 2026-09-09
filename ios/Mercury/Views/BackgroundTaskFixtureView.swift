import SwiftUI
import MercuryCore

#if DEBUG
import UIKit

/// Synthetic simulator-only states through the production ChatView hierarchy.
/// No connections, credentials, prompts, or claims of live task evidence.
struct BackgroundTaskFixtureView: View {
    @Environment(AppModel.self) private var appModel
    @State private var state = ChatSessionState(sessionID: "fixture", title: "Activity fixture", isNewSession: false, incomingShare: nil)
    @State private var initialized = false
    private let scope = "direct:unconfigured|default|fixture"

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Synthetic fixture").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Menu("States") {
                    ForEach(["Working", "Long", "Thinking", "Writing", "Complete", "Background", "Needs you", "Offline", "Idle"], id: \.self) { name in
                        Button(name) { configure(name) }
                    }
                    Button("Recover synthetic tasks") { recoverTasks() }
                }.accessibilityLabel("Fixture controls")
            }.font(.caption).padding(8)
            if initialized {
                NavigationStack { ChatView(fixture: state) }
            }
        }
        .task {
            guard !initialized else { return }
            #if targetEnvironment(simulator)
            // XCTest can expose an offscreen keyboard even with the per-device
            // hardware-keyboard preference disabled. This changes only this
            // synthetic simulator app instance, never the physical build.
            for mode in UITextInputMode.activeInputModes {
                let getter = NSSelectorFromString("hardwareLayout")
                let setter = NSSelectorFromString("setHardwareLayout:")
                if mode.responds(to: getter), mode.responds(to: setter) {
                    _ = mode.perform(setter, with: nil)
                }
            }
            #endif
            let args = ProcessInfo.processInfo.arguments
            let scenario = args.firstIndex(of: "-uitest-activity").flatMap { args.indices.contains($0 + 1) ? args[$0 + 1] : nil }
            configure(scenario ?? (args.contains("-uitest-unavailable-history") ? "Unavailable" : "Background"))
            initialized = true
        }
        .preferredColorScheme(.dark)
        .dynamicTypeSize(ProcessInfo.processInfo.arguments.contains("-uitest-large-text") ? .accessibility1 : .large)
    }

    private func configure(_ scenario: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        state.didOpen = true
        // No controller exists in this synthetic screen. Foreground callbacks
        // must not attempt real reconnects and overwrite the selected fixture.
        state.closedByUs = true
        state.durableID = "fixture"
        state.runtimeSessionID = "fixture-runtime"
        state.connectionState = scenario == "Offline" ? .offline : .live
        state.isSending = ["Working", "Long", "Thinking", "Writing", "Needs you", "Offline"].contains(scenario)
        state.isStopping = false
        state.pendingRequest = nil
        state.draft = ""
        state.dismissedBackgroundEvidence = []
        state.processRows = scenario == "Unavailable" ? [ActivityProcess(id: "fixture-process", command: "Synthetic supporting process", status: "running")] : []
        state.currentModelSelection = ModelSelection(provider: "fixture", model: "Fixture model")
        state.sessionUsage = nil
        state.transcript = TranscriptState()
        state.transcript.ownSessionIDs = ["fixture", "fixture-runtime"]
        state.progress = MercuryCore.DurableProgressBridge.shared.initial().beginTurn(atEpochMillis: now - 12_000)
        state.transcript.loadTranscript([
            .init(role: "user", content: "Verify the native build and report the result."),
            .init(role: "assistant", content: "I’ll check the build first.", reasoningText: "Inspect the build output before reporting."),
            .init(role: "tool", content: "Synthetic build completed.", toolName: "terminal"),
        ])
        if state.isSending {
            state.transcript.apply(.messageStart(sessionID: "fixture-runtime", text: nil))
            if scenario == "Working" || scenario == "Long" {
                state.transcript.apply(.toolStart(sessionID: "fixture-runtime", toolID: "build", name: "terminal", context: scenario == "Long" ? "Checking the complete native activity typography and alignment regression suite" : "Checking native build output"))
            } else if scenario == "Thinking" {
                state.transcript.apply(.reasoningDelta(sessionID: "fixture-runtime", text: "Compare the reported results.", replace: false))
            } else if scenario == "Writing" {
                state.transcript.apply(.messageDelta(sessionID: "fixture-runtime", text: "The synthetic build is ready."))
            } else if scenario == "Needs you" {
                state.transcript.apply(.clarifyRequest(sessionID: "fixture-runtime", requestID: "fixture-question", question: "Choose a synthetic option", choices: ["Staging", "Production"], multiSelect: false))
            }
        } else {
            state.transcript.apply(.messageComplete(sessionID: "fixture-runtime", text: "The synthetic build is ready.", status: "completed", error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil))
        }
        let snapshot = MercuryCore.DurableProgressBridge.shared.liveSnapshotJson(payloadJson: #"{"name":"todo_list","result":{"revision":2,"todos":[{"id":"done","content":"Inspect build inputs","status":"completed"},{"id":"active","content":"Verify native build","status":"in_progress"},{"id":"next","content":"Check final response","status":"pending"}]}}"#)
        if scenario != "Thinking" {
            state.progress = state.progress.observe(observation: MercuryCore.ProgressObservationToolCompleted(toolCallId: "plan", toolName: "todo_list", summary: "Reported checklist snapshot", snapshot: snapshot, historical: false), atEpochMillis: now)
        }
        if scenario == "Idle" {
            state.transcript = TranscriptState()
            state.progress = MercuryCore.DurableProgressBridge.shared.initial()
        }
        let rows: [BackgroundTaskRow]
        if scenario == "Background" {
            rows = [
                BackgroundTaskRow(runtime: "fixture-runtime", childID: "review", goal: "Review lifecycle regressions", action: "Reading test results", status: .active, observedAtMillis: now),
                BackgroundTaskRow(runtime: "fixture-runtime", childID: "build", goal: "Verify the native build", action: "Build reported complete", status: .finished, observedAtMillis: now),
                BackgroundTaskRow(runtime: "fixture-runtime", childID: "offline", goal: "Inspect reconnect behavior", action: "Last observed: reading connection state", status: .active, observedAtMillis: now - 1000, available: false),
            ]
        } else if scenario == "Unavailable" {
            rows = [BackgroundTaskRow(runtime: "fixture-runtime", childID: "unknown", goal: "Historical task with unavailable status", action: nil, status: .unknown, observedAtMillis: 0, available: false)]
        } else { rows = [] }
        appModel.backgroundTasksBySession[scope] = BackgroundTasks(rows: rows)
    }

    private func recoverTasks() {
        let engine = MercuryCore.RelayLeaseRecoveryEngine(profile: "default")
        let raw = #"{"jsonrpc":"2.0","method":"relay.lease.attached","params":{"recovery_version":1,"lease_id":"synthetic","last_seq":8,"resume_cursor":0,"replay_gap":true,"recovery_reset":false,"bindings":[],"task_snapshot":[{"jsonrpc":"2.0","method":"event","params":{"session_id":"old","type":"subagent.complete","payload":{"subagent_id":"review","status":"completed","goal":"Review lifecycle regressions"},"recovery_revision":1,"recovery_binding":{"runtime_session_id":"old","durable_session_id":"fixture","profile":"default","live":false}}}]}}"#
        var tasks = BackgroundTasks()
        if let snapshot = try? engine.initialize(raw: raw) {
            tasks.recover(snapshot: snapshot, durableID: "fixture", profile: "default", runtime: "fixture-runtime")
        }
        appModel.backgroundTasksBySession[scope] = tasks
    }
}
#endif
