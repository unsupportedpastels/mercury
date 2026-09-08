import SwiftUI
import MercuryCore

#if DEBUG
/// Synthetic simulator-only visual fixture. No connections or credentials.
struct BackgroundTaskFixtureView: View {
    @State private var draft = ""
    @State private var recovered = false
    private var unavailableHistory: Bool {
        ProcessInfo.processInfo.arguments.contains("-uitest-unavailable-history")
    }
    private var unavailableTasks: Mercury.BackgroundTasks {
        Mercury.BackgroundTasks(rows: [
            BackgroundTaskRow(runtime: "fixture", childID: "unknown", goal: "Historical task with unavailable status",
                              action: nil, status: .unknown, observedAtMillis: 0, available: false),
        ])
    }
    private var processHistory: [ActivityProcess] {
        [ActivityProcess(id: "fixture-emulator", command: "Synthetic supporting process", status: "running")] +
            (1...7).map { ActivityProcess(id: "fixture-completed-\($0)", command: "Synthetic completed build", status: "exited", exitCode: 0) }
    }
    private var recoveredTasks: Mercury.BackgroundTasks {
        let engine = MercuryCore.RelayLeaseRecoveryEngine(profile: "default")
        let raw = #"{"jsonrpc":"2.0","method":"relay.lease.attached","params":{"recovery_version":1,"lease_id":"synthetic","last_seq":8,"resume_cursor":0,"replay_gap":true,"recovery_reset":false,"bindings":[],"task_snapshot":[{"jsonrpc":"2.0","method":"event","params":{"session_id":"old","type":"subagent.complete","payload":{"subagent_id":"review","status":"completed","goal":"Review lifecycle regressions"},"recovery_revision":1,"recovery_binding":{"runtime_session_id":"old","durable_session_id":"fixture","profile":"default","live":false}}}]}}"#
        var tasks = Mercury.BackgroundTasks()
        if let snapshot = try? engine.initialize(raw: raw) {
            tasks.recover(snapshot: snapshot, durableID: "fixture", profile: "default", runtime: "replacement")
        }
        return tasks
    }
    private let now: Int64 = 10_000
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("The parent response has ended. Child tasks remain visible below. This is a synthetic UI fixture, not live task evidence.")
                Button("Recover synthetic tasks") { recovered = true }
                Spacer()
                if unavailableHistory {
                    ActivityStackView(state: ActivityStackState(processes: processHistory))
                }
                BackgroundTaskStrip(tasks: unavailableHistory ? unavailableTasks : recovered ? recoveredTasks : Mercury.BackgroundTasks(rows: [
                    BackgroundTaskRow(runtime: "fixture", childID: "review", goal: "Review lifecycle regressions", action: "Reading test results", status: .active, observedAtMillis: 3000),
                    BackgroundTaskRow(runtime: "fixture", childID: "build", goal: "Verify the native build", action: "Build verified", status: .finished, observedAtMillis: 3000),
                    BackgroundTaskRow(runtime: "fixture", childID: "offline", goal: "Inspect reconnect behavior", action: "Last observed: reading connection state", status: .active, observedAtMillis: 1000, available: false),
                ]), nowOverride: now)
                TextField("Message Hermes", text: $draft)
                    .padding().background(.regularMaterial, in: Capsule())
                    .accessibilityIdentifier("fixture-composer")
            }.padding().navigationTitle("Background tasks · fixture").navigationBarTitleDisplayMode(.inline)
        }.preferredColorScheme(.dark)
    }
}
#endif
