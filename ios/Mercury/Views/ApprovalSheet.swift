import SwiftUI

/// Blocking-input sheet for approval and clarify requests.
///
/// Clarify is a faithful port of Android's `ClarificationCard`: selectable
/// choice rows (single- or multi-select), an always-present "Other"
/// free-text field, mutual exclusivity between typing and picking, and
/// Skip / Continue confirmation. A choice is only ever sent if the server
/// offered it.
struct ApprovalSheet: View {
    enum Request {
        case approval(ChatEvent)
        case clarify(ChatEvent)
    }

    let request: Request
    let isBusy: Bool
    let onApprovalChoice: (String) async -> Void
    let onClarifyAnswer: (String) async -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            Group {
                switch request {
                case .approval(let event):
                    approvalBody(event)
                case .clarify(let event):
                    clarifyBody(event)
                }
            }
            .navigationTitle("Action required")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(isBusy)
        .amoledScreen()
    }

    // MARK: Approval

    @State private var clarifyState = ClarifySheetPolicy.State(choices: [], multiSelect: false)

    @ViewBuilder
    private func approvalBody(_ event: ChatEvent) -> some View {
        if case .approvalRequest(_, let requestID, let command, let description, let choices) = event {
        VStack(alignment: .leading, spacing: 16) {
            if let command, !command.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(command)
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                        .padding(10)
                }
                .background(Color.surfaceLow)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            if let description, !description.isEmpty {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
            }
            Spacer()
            ForEach(choices, id: \.self) { choice in
                Button {
                    Task { await onApprovalChoice(choice) }
                } label: {
                    Text(choice)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isBusy)
            }
            if requestID == nil {
                Text("The server did not tag this request; the newest pending approval will be answered.")
                    .font(.caption2)
                    .foregroundStyle(Color.secondary)
            }
        }
        .padding()
        }
    }

    // MARK: Clarify (Android ClarificationCard parity)

    @ViewBuilder
    private func clarifyBody(_ event: ChatEvent) -> some View {
        if case .clarifyRequest(_, _, let question, let choices, let multiSelect) = event {
        VStack(alignment: .leading, spacing: 14) {
            Text(question)
                .font(.headline)
                .textSelection(.enabled)

            if !choices.isEmpty && multiSelect {
                FlowLayout(spacing: 8) {
                    ForEach(choices, id: \.self) { choice in
                        clarifyChip(choice)
                    }
                }
            } else if !choices.isEmpty {
                VStack(spacing: 8) {
                    ForEach(choices, id: \.self) { choice in
                        clarifyRow(choice)
                    }
                }
            }

            TextField(
                ClarifySheetPolicy.otherFieldLabel(hasChoices: !choices.isEmpty),
                text: Binding(
                    get: { clarifyState.answer },
                    set: { clarifyState.typeAnswer($0) }
                ),
                axis: .vertical
            )
            .lineLimit(1...3)
            .padding(8)
            .background(Color.surfaceLow)
            .clipShape(RoundedRectangle(cornerRadius: 10))

            HStack(spacing: 12) {
                // Skip sends an empty answer, matching Android/desktop: the
                // agent treats it as "no preference / proceed".
                Button("Skip") {
                    Task { await onClarifyAnswer(ClarifySheetPolicy.skipAnswer) }
                }
                .buttonStyle(.bordered)
                .disabled(isBusy)

                Button("Continue") {
                    if let answer = clarifyState.pendingAnswer {
                        Task { await onClarifyAnswer(answer) }
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(isBusy || !clarifyState.canContinue)

                Spacer()
            }
            Spacer()
        }
        .padding()
        .onAppear {
            clarifyState = ClarifySheetPolicy.State(choices: choices, multiSelect: multiSelect)
        }
        .onChange(of: event) {
            clarifyState = ClarifySheetPolicy.State(choices: choices, multiSelect: multiSelect)
        }
        }
    }

    private func clarifyRow(_ choice: String) -> some View {
        let chosen = clarifyState.selectedChoices.contains(choice)
        return Button {
            clarifyState.select(choice)
        } label: {
            Text(choice)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .foregroundStyle(chosen ? Color.white : Color.primary)
        .background(chosen ? Color.accentPrimary : Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .disabled(isBusy)
    }

    private func clarifyChip(_ choice: String) -> some View {
        let chosen = clarifyState.selectedChoices.contains(choice)
        return Button {
            clarifyState.select(choice)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: chosen ? "checkmark" : "plus")
                    .font(.caption2)
                Text(choice)
            }
            .font(.caption.weight(.medium))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
        .foregroundStyle(chosen ? Color.white : Color.primary)
        .background(chosen ? Color.accentPrimary : Color.surfaceLow)
        .clipShape(Capsule())
        .disabled(isBusy)
    }
}

/// Simple wrapping flow layout for multi-select chips.
private struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > width, x > 0 {
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: .unspecified)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
