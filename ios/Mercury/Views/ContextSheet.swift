import SwiftUI

/// Explicit, live-runtime-only context and maintenance controls.
struct ContextSheet: View {
    let usage: SessionUsage?
    let breakdown: SessionContextBreakdown?
    let isLoading: Bool
    let isBusy: Bool
    let isIdle: Bool
    let statusMessage: String?
    let errorMessage: String?
    let compressSupported: Bool
    let undoSupported: Bool
    let branchSupported: Bool
    let onRefresh: () -> Void
    let onCompress: () -> Void
    let onUndo: () -> Void
    let onBranch: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var confirmCompress = false
    @State private var confirmUndo = false
    @State private var showBranch = false
    @State private var branchName = ""

    var body: some View {
        NavigationStack {
            List {
                if let statusMessage {
                    Section {
                        Label(statusMessage, systemImage: "checkmark.circle.fill")
                            .foregroundStyle(Color.accentPrimary)
                    }
                }
                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(Color.statusAlert)
                    }
                }

                Section("Context usage") {
                    if let usage {
                        usageView(usage)
                    } else if isLoading {
                        HStack { Spacer(); ProgressView(); Spacer() }
                    } else {
                        Text("Usage is unavailable for this runtime.")
                            .foregroundStyle(.secondary)
                    }
                }

                if let breakdown, !breakdown.categories.isEmpty {
                    Section("Breakdown") {
                        ForEach(Array(breakdown.categories.enumerated()), id: \.offset) { _, category in
                            VStack(alignment: .leading, spacing: 5) {
                                HStack {
                                    Text(category.name)
                                    Spacer()
                                    if let tokens = category.tokens {
                                        Text(tokens.formatted())
                                            .monospacedDigit()
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                if let fraction = categoryFraction(category, breakdown: breakdown) {
                                    ProgressView(value: fraction)
                                        .tint(Color.accentPrimary)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }

                Section("Session actions") {
                    if compressSupported {
                        Button {
                            confirmCompress = true
                        } label: {
                            Label("Compress context", systemImage: "arrow.down.right.and.arrow.up.left")
                        }
                        .disabled(!isIdle || isBusy)
                    }
                    if undoSupported {
                        Button(role: .destructive) {
                            confirmUndo = true
                        } label: {
                            Label("Undo last turn", systemImage: "arrow.uturn.backward")
                        }
                        .disabled(!isIdle || isBusy)
                    }
                    if branchSupported {
                        Button {
                            branchName = ""
                            showBranch = true
                        } label: {
                            Label("Branch session", systemImage: "arrow.triangle.branch")
                        }
                        .disabled(!isIdle || isBusy)
                    }
                    if !isIdle {
                        Text("Maintenance actions are available when the active turn finishes.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Context")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(action: onRefresh) {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(isLoading || isBusy)
                    .accessibilityLabel("Refresh context")
                }
            }
            .confirmationDialog("Compress this session's context?", isPresented: $confirmCompress, titleVisibility: .visible) {
                Button("Compress", action: onCompress)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Hermes will summarize older context for this session.")
            }
            .confirmationDialog("Remove the last user turn?", isPresented: $confirmUndo, titleVisibility: .visible) {
                Button("Undo last turn", role: .destructive, action: onUndo)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The last user turn and its assistant/tool responses will be removed.")
            }
            .alert("Branch Session", isPresented: $showBranch) {
                TextField("Branch name", text: $branchName)
                Button("Create") {
                    let trimmed = branchName.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { onBranch(trimmed) }
                }
                .disabled(branchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Create a new session from the current transcript. The parent session stays open.")
            }
        }
    }

    @ViewBuilder
    private func usageView(_ usage: SessionUsage) -> some View {
        if let used = usage.contextUsedTokens, let maximum = usage.contextMaxTokens, maximum > 0 {
            ProgressView(value: Double(used), total: Double(maximum))
                .tint(Color.accentPrimary)
            HStack {
                Text("\(used.formatted()) of \(maximum.formatted()) tokens")
                Spacer()
                if let percent = usage.contextPercent {
                    Text("\(percent, specifier: "%.1f")%")
                        .monospacedDigit()
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        tokenRow("Input", usage.inputTokens)
        tokenRow("Output", usage.outputTokens)
        tokenRow("Total", usage.totalTokens)
        tokenRow("Calls", usage.calls)
    }

    private func tokenRow(_ label: String, _ value: Int64?) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value?.formatted() ?? "—")
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
    }

    private func categoryFraction(_ category: ContextBreakdownCategory, breakdown: SessionContextBreakdown) -> Double? {
        if let percent = category.percent { return min(1, max(0, percent / 100)) }
        guard let tokens = category.tokens, let maximum = breakdown.maxTokens, maximum > 0 else { return nil }
        return min(1, max(0, Double(tokens) / Double(maximum)))
    }
}
