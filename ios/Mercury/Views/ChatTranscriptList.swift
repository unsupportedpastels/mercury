import SwiftUI

extension ChatView {
    // MARK: Transcript

    var transcriptList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if state.hasMoreHistory || state.historyError != nil {
                        Button {
                            Task { await loadEarlierHistory() }
                        } label: {
                            HStack(spacing: 6) {
                                if state.isLoadingHistory {
                                    ProgressView().controlSize(.small)
                                }
                                Text(state.historyError ?? "Load earlier messages")
                                    .font(.caption.weight(.medium))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                        .disabled(state.isLoadingHistory)
                        .accessibilityLabel(state.historyError.map { _ in "Retry loading earlier messages" } ?? "Load earlier messages")
                        .id(firstRowID)
                    }
                    ForEach(coalesceTranscriptEntries(state.transcript.rows)) { entry in
                        switch entry {
                        case .message(let row):
                            VStack(alignment: .leading, spacing: 4) {
                                if !row.reasoningText.isEmpty {
                                    ReasoningDisclosure(
                                        reasoningText: row.reasoningText,
                                        streaming: !row.completed
                                    )
                                }
                                if TranscriptPresentationPolicy.shouldRenderMessageBubble(
                                    role: row.role,
                                    text: row.text
                                ) {
                                    MessageBubble(role: row.role, text: row.text, isStreaming: !row.completed)
                                }
                                if row.role.lowercased() == "assistant", row.completed, !row.text.isEmpty {
                                    managedImages(in: row.text)
                                    if TranscriptPresentationPolicy.shouldShowPlaybackControl(
                                        enabled: showMessagePlaybackControls,
                                        role: row.role,
                                        text: row.text,
                                        completed: row.completed
                                    ), let readAloud = state.readAloud {
                                        ReadAloudButton(
                                            controller: readAloud,
                                            messageID: String(describing: row.id),
                                            text: row.text
                                        )
                                    }
                                }
                            }
                            .id(row.id)
                        case .toolRun(let rows):
                            TranscriptToolRunView(rows: rows)
                                .id(entry.id)
                        case .workBurst(let reasoning, let tools):
                            WorkBurstView(reasoning: reasoning, tools: tools)
                                .id(entry.id)
                        }
                    }
                    if !state.transcript.tools.isEmpty || !state.processRows.isEmpty {
                        ActivityStackView(
                            state: ActivityStackState(processes: state.processRows),
                            tools: state.transcript.tools,
                            turnActive: state.isSending
                        )
                    }
                    if let generating = state.transcript.generatingStatusText {
                        Label(generating, systemImage: "gearshape")
                            .font(.caption)
                            .foregroundStyle(Color.secondary)
                    }
                    if state.turnInFlight, let status = state.transcript.latestStatusText, !status.isEmpty {
                        RunStatusPill(text: status)
                    }
                    Color.clear.frame(height: 1).id(lastRowID)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .simultaneousGesture(
                DragGesture().onChanged { _ in
                    // A user drag disengages follow; reaching bottom re-engages.
                    state.followBottom = false
                }
            )
            .onChange(of: state.transcript.rows.count) {
                guard state.followBottom else { return }
                // A local user echo must land in its final position in one
                // layout pass. Animating the row-count scroll while the
                // keyboard/inset transition is also settling produces the
                // visible halfway-state before the bubble reaches the tail.
                if state.transcript.rows.last?.role.lowercased() == "user" {
                    // ChatGPT-style submission: place the new user turn at
                    // the top of the conversation viewport immediately. A
                    // bottom anchor here can briefly win while the keyboard
                    // is collapsing, then snap the short transcript upward.
                    if let userID = state.transcript.rows.last?.id {
                        state.userMessageScrollGeneration &+= 1
                        let generation = state.userMessageScrollGeneration
                        Task { @MainActor in
                            // Let SwiftUI commit and measure the inserted row
                            // before resolving its top anchor. The immediate
                            // call can be ignored or clamped against the
                            // previous content offset, producing the brief
                            // halfway position seen during submission.
                            await Task.yield()
                            try? await Task.sleep(nanoseconds: 16_000_000)
                            guard !Task.isCancelled,
                                  generation == state.userMessageScrollGeneration else { return }
                            proxy.scrollTo(userID, anchor: .top)
                        }
                    }
                } else {
                    withAnimation(.easeOut(duration: 0.15)) {
                        proxy.scrollTo(lastRowID, anchor: .bottom)
                    }
                }
            }
            .onChange(of: state.transcript.rows.last?.text) {
                guard state.followBottom else { return }
                proxy.scrollTo(lastRowID, anchor: .bottom)
            }
            .onChange(of: state.initialScrollDone) {
                // Initial jump waits for first content (async transcript load).
                guard !state.initialScrollDone, !state.transcript.rows.isEmpty else { return }
                state.initialScrollDone = true
                proxy.scrollTo(lastRowID, anchor: .bottom)
            }
        }
    }
}
