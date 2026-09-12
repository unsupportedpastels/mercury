import SwiftUI
import Foundation

extension ChatView {
    // MARK: Transcript

    var transcriptList: some View {
        transcriptList(backgroundTasks: backgroundTasks)
    }

    /// Parameterized only so the DEBUG fixture can render this production UI
    /// without evaluating ChatView's environment-backed task store directly.
    func transcriptList(backgroundTasks: BackgroundTasks) -> some View {
        let entries = foldTranscriptTurns(state.transcript.rows, turnActive: state.activityTurnActive)
        let windowSize = 100
        let start = state.transcriptWindowStartID.flatMap { anchor in entries.firstIndex { $0.id == anchor } }
            ?? max(0, entries.count - windowSize)
        let end = min(entries.count, start + windowSize)
        return ScrollViewReader { proxy in
            GeometryReader { viewport in
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        // Measure at most one window. Lazy height estimates broke
                        // true-tail jumps; bounded eager rows preserve exact geometry
                        // without retaining every Markdown view from paged history.
                        VStack(alignment: .leading, spacing: 12) {
                    if start > 0 || state.hasMoreHistory || state.historyError != nil {
                        Button {
                            state.followBottom = false
                            if start > 0 {
                                state.transcriptWindowStartID = entries[max(0, start - 75)].id
                            } else {
                                Task {
                                    await loadEarlierHistory()
                                    state.transcriptWindowStartID = foldTranscriptTurns(
                                        state.transcript.rows, turnActive: state.activityTurnActive
                                    ).first?.id
                                }
                            }
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
                    ForEach(Array(entries[start..<end])) { entry in
                        switch entry {
                        case .message(let row):
                            VStack(alignment: .leading, spacing: 4) {
                                if TranscriptPresentationPolicy.shouldRenderMessageBubble(
                                    role: row.role,
                                    text: row.text
                                ) {
                                    if row.role.lowercased() == "assistant", row.completed {
                                        completedAssistantMessage(in: row.text)
                                    } else {
                                        MessageBubble(role: row.role, text: row.text, isStreaming: !row.completed)
                                    }
                                }
                                if row.role.lowercased() == "assistant", row.completed, !row.text.isEmpty {
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
                        case .activity(_, let steps, let reasoning, let count):
                            TurnActivityView(steps: steps, answerReasoning: reasoning, stepCount: count,
                                             media: { AnyView(completedAssistantMessage(in: $0)) })
                                .id(entry.id)
                        }
                    }
                    if end < entries.count {
                        Button("Show newer messages") {
                            let next = min(max(0, entries.count - windowSize), start + 75)
                            state.transcriptWindowStartID = next + windowSize >= entries.count ? nil : entries[next].id
                        }
                        .frame(minHeight: 44)
                    }
                    if let request = state.transcript.pendingRequest, state.pendingRequest == nil {
                        Button("Respond to pending request") {
                            switch request {
                            case .approval(let event): state.pendingRequest = .approval(event)
                            case .clarify(let event): state.pendingRequest = .clarify(event)
                            }
                        }.frame(minHeight: 44)
                    }
                    if state.outstandingSecure != nil && state.pendingSecure == nil {
                        Button("Provide requested input") { state.presentPendingInput() }
                            .frame(minHeight: 44)
                    }
                            Color.clear
                                .frame(height: 1)
                                .id(lastRowID)
                                .background {
                                    GeometryReader { tail in
                                        Color.clear.preference(
                                            key: TranscriptTailPreferenceKey.self,
                                            value: tail.frame(in: .named("chat-transcript-viewport")).maxY
                                        )
                                    }
                                }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                    }
                    .coordinateSpace(name: "chat-transcript-viewport")
                    .accessibilityIdentifier("Chat transcript")
                    .scrollDismissesKeyboard(.interactively)
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { _ in
                                state.followBottom = false
                            }
                            .onEnded { _ in
                                resumeFollowingIfAtBottom()
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
                    if !isTranscriptAtBottom,
                       state.pendingRequest == nil,
                       state.pendingSecure == nil {
                        Button {
                            state.transcriptWindowStartID = nil
                            state.followBottom = true
                            Task { @MainActor in
                                await Task.yield()
                                guard state.followBottom else { return }
                                withAnimation(.easeOut(duration: 0.15)) {
                                    proxy.scrollTo(lastRowID, anchor: .bottom)
                                }
                            }
                        } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 20, weight: .semibold))
                                .frame(width: 44, height: 44)
                                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                                .shadow(color: .black.opacity(0.22), radius: 4, y: 2)
                        }
                        .buttonStyle(.plain)
                        .padding(.trailing, 12)
                        .padding(.bottom, 8)
                        .accessibilityLabel("Scroll to latest message")
                    }
                }
                .onAppear {
                    state.transcriptViewportHeight = viewport.size.height
                }
                .onChange(of: viewport.size.height) { _, height in
                    state.transcriptViewportHeight = height
                    resumeFollowingIfAtBottom()
                }
                .onPreferenceChange(TranscriptTailPreferenceKey.self) { tailMaxY in
                    state.transcriptTailMaxY = tailMaxY
                    resumeFollowingIfAtBottom()
                }
            }
        }
    }

    private var isTranscriptAtBottom: Bool {
        state.transcriptWindowStartID == nil && TranscriptScrollPosition.isAtBottom(
            tailMaxY: state.transcriptTailMaxY,
            viewportHeight: state.transcriptViewportHeight
        )
    }

    private func resumeFollowingIfAtBottom() {
        if isTranscriptAtBottom {
            state.followBottom = true
        }
    }
}
