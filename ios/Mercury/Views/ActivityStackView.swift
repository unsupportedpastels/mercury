import SwiftUI

/// Compact disclosure for the bounded, audited M9 families only. Unsupported
/// families are absent from the model and therefore remain hidden.
struct ActivityStackView: View {
    let state: ActivityStackState
    var tools: [TranscriptState.ToolRow] = []
    /// The overall assistant turn can still be waiting for its final model
    /// response after the last tool completes. Keep the activity row honest
    /// until `message.complete`, rather than showing a misleading checkmark.
    var turnActive = false
    var onProcessSelected: ((ActivityProcess) -> Void)? = nil
    var onLoopSelected: ((ActivityLoop) -> Void)? = nil

    @State private var isExpanded = false

    var body: some View {
        if !state.isEmpty || !tools.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) { isExpanded.toggle() }
                } label: {
                    HStack(spacing: 8) {
                        if isRunning {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "checkmark.circle")
                                .foregroundStyle(.green)
                        }
                        Text(summary)
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(summary + ", " + (isExpanded ? "expanded" : "collapsed"))

                if isExpanded {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 8) {
                            if !state.todos.isEmpty {
                                sectionLabel("Tasks")
                                ForEach(state.todos) { todoRow($0) }
                            }
                            if !state.loops.isEmpty {
                                sectionLabel("Loops")
                                ForEach(state.loops) { loopRow($0) }
                            }
                            if !tools.isEmpty {
                                sectionLabel("Tools")
                                ForEach(tools, id: \.toolID) { ToolActivityRow(toolRow: $0) }
                            }
                            if !state.processes.isEmpty {
                                sectionLabel("Processes · process-local")
                                ForEach(state.processes) { processRow($0) }
                            }
                        }
                    }
                    .frame(maxHeight: 280)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var isRunning: Bool {
        turnActive || state.isRunning || tools.contains { $0.state == .running }
    }

    private var summary: String {
        let countedTodos = state.todos.filter { $0.status != .cancelled }
        return TranscriptPresentationPolicy.activitySummary(
            toolCount: tools.count,
            completedTodos: countedTodos.filter { $0.status == .completed }.count,
            todoCount: countedTodos.count,
            loopCount: state.loops.count,
            processCount: state.processes.count
        )
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text).font(.caption2.weight(.semibold)).foregroundStyle(Color.accentColor)
    }

    private func todoRow(_ todo: ActivityTodo) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(todoMarker(todo.status))
                .foregroundStyle(todo.status == .completed ? Color.green : Color.accentColor)
            Text(todo.content).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
            Text(todoLabel(todo.status)).font(.caption2).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Task \(todo.content), \(todoLabel(todo.status))")
    }

    private func loopRow(_ loop: ActivityLoop) -> some View {
        Button { onLoopSelected?(loop) } label: {
            HStack(spacing: 8) {
                Image(systemName: loop.status == .running ? "repeat.circle.fill" : "repeat.circle")
                Text(loop.title).font(.caption).lineLimit(2)
                Spacer()
                Text(loop.status.rawValue).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
        .disabled(onLoopSelected == nil)
    }

    private func processRow(_ process: ActivityProcess) -> some View {
        Button { onProcessSelected?(process) } label: {
            HStack(alignment: .top, spacing: 8) {
                Text(process.command.split(separator: "\n", maxSplits: 1).first.map(String.init) ?? "background process")
                    .font(.caption.monospaced())
                    .lineLimit(2)
                Spacer()
                Text(process.exitCode.map { "\(process.status) (\($0))" } ?? process.status)
                    .font(.caption2)
                    .foregroundStyle(process.status.lowercased() == "running" ? Color.accentColor : Color.secondary)
            }
        }
        .buttonStyle(.plain)
        .disabled(onProcessSelected == nil)
        .accessibilityLabel("Process-local process \(process.id), \(process.status)")
    }

    private func todoMarker(_ status: ActivityTodoStatus) -> String {
        switch status {
        case .pending: "○"
        case .inProgress: "•"
        case .completed: "✓"
        case .cancelled: "–"
        }
    }

    private func todoLabel(_ status: ActivityTodoStatus) -> String {
        switch status {
        case .pending: "pending"
        case .inProgress: "in progress"
        case .completed: "completed"
        case .cancelled: "cancelled"
        }
    }
}
