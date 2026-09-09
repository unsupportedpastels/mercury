import SwiftUI
import MercuryCore

/// Session-wide observations in one native sheet. Reported output stays reported
/// output; old timestamps, restored plans, and disconnected tools never animate.
struct SessionActivitySheet: View {
    @Bindable var state: ChatSessionState
    let backgroundTasks: BackgroundTasks
    let onRefresh: () -> Void
    let onReconnect: () -> Void
    var media: (String) -> AnyView = { _ in AnyView(EmptyView()) }
    var nowOverride: Int64? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var reportsExpanded = false

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { clock in
                let now = nowOverride ?? Int64(clock.date.timeIntervalSince1970 * 1000)
                let children = backgroundTasks.rows.filter {
                    !($0.isDismissible(now: now) && state.dismissedBackgroundEvidence.contains($0.dismissalKey))
                }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if state.activityTurnActive, let status = state.transcript.generatingStatusText ?? state.transcript.latestStatusText {
                            Text(status).font(.caption).foregroundStyle(.secondary)
                        }
                        observationAge(now: now)
                        if state.progress.partialHistory {
                            Text("Recent history").font(.caption).foregroundStyle(.secondary)
                        }
                        if state.isConnectionDown {
                            Button("Reconnect", action: onReconnect)
                                .accessibilityLabel("Reconnect to recover job status")
                        }
                        if let error = state.progressRefreshError {
                            Text(error).font(.caption).foregroundStyle(Color.statusAlert)
                        }
                        if state.transcript.pendingRequest != nil || state.outstandingSecure != nil {
                            section("Needs you")
                            Button("Respond to pending request") {
                                state.openInputAfterActivity = true
                                dismiss()
                            }
                        }
                        milestones(.inprogress, title: "In progress")
                        let currentRows = Array(state.transcript.rows.reversed().prefix { $0.role.lowercased() != "user" }.reversed())
                        if !currentRows.isEmpty {
                            section("Current turn")
                            ActivityTranscriptContent(rows: currentRows, media: media)
                        }
                        if !state.transcript.tools.isEmpty {
                            section("Tools")
                            ForEach(state.transcript.tools, id: \.toolID) { tool in
                                toolRow(tool)
                            }
                        }
                        milestones(.completed, title: "Done")
                        milestones(.pending, title: "Remaining")
                        if !children.isEmpty {
                            section("Background tasks")
                            ForEach(children) { row in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(row.goal).font(.subheadline)
                                    Text(row.label(now: now)).font(.caption)
                                    if row.observedAtMillis > 0 && row.observedAtMillis <= now {
                                        Text(row.timeLabel(now: now)).font(.caption).foregroundStyle(.secondary)
                                    }
                                    if let action = row.action { Text(action).font(.caption).foregroundStyle(.secondary) }
                                }
                                .accessibilityElement(children: .contain)
                            }
                            let unavailable = children.filter { !$0.terminal && $0.isDismissible(now: now) }
                            if !unavailable.isEmpty {
                                Button("Dismiss unavailable") {
                                    state.dismissedBackgroundEvidence.formUnion(unavailable.map(\.dismissalKey))
                                }.frame(minHeight: 44)
                            }
                            let completed = children.filter(\.terminal)
                            if !completed.isEmpty {
                                Button("Dismiss completed") {
                                    state.dismissedBackgroundEvidence.formUnion(completed.map(\.dismissalKey))
                                }.frame(minHeight: 44)
                            }
                        }
                        if !state.processRows.isEmpty {
                            section("Processes · last reported")
                            ForEach(state.processRows) { process in
                                HStack(alignment: .top) {
                                    Text(process.command).font(.caption.monospaced()).lineLimit(2)
                                    Spacer()
                                    Text(process.exitCode.map { "\(process.status) (\($0))" } ?? process.status)
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                .accessibilityLabel("Process-local process \(process.id), \(process.status)")
                            }
                        }
                        if !state.progress.evidence.isEmpty {
                            DisclosureGroup("Tool reports", isExpanded: $reportsExpanded) {
                                ForEach(state.progress.evidence, id: \.toolCallId) { report in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(report.toolName).font(.caption.weight(.medium))
                                        if let summary = report.summary {
                                            Text(summary).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                                        }
                                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
                                }
                            }
                            .font(.subheadline)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
                }
            }
            .navigationTitle("Activity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button(action: onRefresh) {
                        if state.progressRefreshing { ProgressView() }
                        else { Image(systemName: "arrow.clockwise") }
                    }
                    .disabled(state.progressRefreshing)
                    .accessibilityLabel("Get progress update (read-only)")
                }
            }
            .accessibilityIdentifier("Session activity sheet")
            .amoledScreen()
        }
        .presentationDetents([.large])
    }

    @ViewBuilder
    private func observationAge(now: Int64) -> some View {
        if let at = state.progress.lastObservedAtEpochMillis?.int64Value, at > 0, at <= now {
            HStack(spacing: 4) {
                if state.progress.restored { Text("Saved ·") }
                Text(Date(timeIntervalSince1970: Double(at) / 1000), style: .relative)
                Text("ago")
            }.font(.caption).foregroundStyle(.secondary)
        } else if state.progress.restored {
            Text("Saved").font(.caption).foregroundStyle(.secondary)
        }
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.caption.weight(.semibold)).foregroundStyle(Color.accentPrimary).padding(.top, 8)
    }

    @ViewBuilder
    private func milestones(_ status: MercuryCore.RunTodoStatus, title: String) -> some View {
        let rows = state.progress.milestones.filter { $0.status == status }
        if !rows.isEmpty {
            section(title)
            ForEach(rows, id: \.id) { item in
                HStack(alignment: .top, spacing: 8) {
                    if status == .inprogress && state.activityMilestonesLive { ProgressView().controlSize(.mini) }
                    else {
                        Image(systemName: status == .completed ? "checkmark" : status == .inprogress ? "circle.fill" : "circle")
                            .font(.caption2).foregroundStyle(status == .completed ? Color.statusHealthy : Color.secondaryContent)
                    }
                    Text(item.content).font(.subheadline)
                }
            }
        }
    }

    private func toolRow(_ tool: TranscriptState.ToolRow) -> some View {
        let running = tool.state == .running
        let detail = running ? tool.context : tool.summary
        return HStack(spacing: 8) {
            if running && state.activityToolRowsLive { ProgressView().controlSize(.mini) }
            else {
                Image(systemName: running ? "circle.fill" : "checkmark")
                    .font(.caption2).foregroundStyle(running ? Color.secondaryContent : Color.statusHealthy)
            }
            Text(tool.name + (detail.map { " · \($0)" } ?? ""))
                .font(.caption).lineLimit(2)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(running ? "Running" : "Completed") tool \(tool.name)\(detail.map { ": \($0)" } ?? "")")
    }
}
