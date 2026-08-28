import SwiftUI

/// Session browser for one approved relay target. Relay mode has no REST
/// surface, so the inbox comes from the in-process `relay.sessions.list`
/// read and chat runs over the same admitted encrypted channel.
struct RelaySessionsView: View {
    @State private var model: RelaySessionModel
    @Environment(\.dismiss) private var dismiss

    init(target: RelayPairedTarget) {
        _model = State(initialValue: RelaySessionModel(target: target))
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(model.target.displayLabel)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Disconnect") {
                            Task {
                                await model.disconnect()
                                dismiss()
                            }
                        }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        NavigationLink {
                            RelayChatView(model: model, startNew: true)
                        } label: {
                            Image(systemName: "square.and.pencil")
                        }
                        .disabled(model.phase != .connected)
                    }
                }
        }
        .task { await model.connect() }
        .onDisappear { Task { await model.disconnect() } }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .connecting:
            VStack(spacing: 12) {
                ProgressView()
                Text("Connecting through the relay…")
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .notAuthorized:
            statusMessage(
                icon: "person.fill.questionmark",
                text: "The host hasn't approved this device — or it was revoked. Approve it on the host, then try again."
            )
        case .offline:
            statusMessage(
                icon: "wifi.slash",
                text: "The relay or host is unreachable. The host keeps running sessions; reconnect when you're back online."
            )
        case .failed(let message):
            statusMessage(icon: "exclamationmark.triangle.fill", text: message)
        case .connected:
            sessionList
        }
    }

    private var sessionList: some View {
        List {
            if model.sessions.isEmpty {
                Text("No sessions yet. Start one with the compose button.")
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
                    .listRowBackground(Color.clear)
            }
            ForEach(model.sessions) { row in
                NavigationLink {
                    RelayChatView(model: model, sessionID: row.id)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(row.title.isEmpty ? "Untitled session" : row.title)
                            .fontWeight(.medium)
                            .lineLimit(1)
                        if !row.preview.isEmpty {
                            Text(row.preview)
                                .font(.caption)
                                .foregroundStyle(Color.secondary)
                                .lineLimit(2)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .listStyle(.plain)
        .refreshable { await model.loadSessions() }
    }

    private func statusMessage(icon: String, text: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 36))
                .foregroundStyle(Color.secondary)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Color.secondary)
                .multilineTextAlignment(.center)
            Button("Retry") { Task { await model.connect() } }
                .buttonStyle(.bordered)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Minimal relay chat surface: transcript, streaming deltas, composer,
/// interrupt, and approval/clarification responses — exactly the approved v1
/// relay method set. Attachments, model pickers, and project features stay
/// direct-mode-only until the transport refactor lands.
struct RelayChatView: View {
    @Bindable var model: RelaySessionModel
    var sessionID: String? = nil
    var startNew = false

    @State private var draft = ""
    @State private var clarifyAnswer = ""

    var body: some View {
        VStack(spacing: 0) {
            transcriptList
            if let approval = model.approval {
                approvalBar(approval)
            }
            if let clarify = model.clarify {
                clarifyBar(clarify)
            }
            if let error = model.chatError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.statusAlert)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            composer
        }
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if startNew {
                await model.startNewSession()
            } else if let sessionID {
                await model.openSession(durableSessionID: sessionID)
            }
        }
    }

    private var transcriptList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(model.transcript) { row in
                        transcriptRow(row)
                            .id(row.id)
                    }
                    if model.running {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small)
                            Text("Working…")
                                .font(.caption)
                                .foregroundStyle(Color.secondary)
                        }
                        .id("working")
                    }
                }
                .padding(16)
            }
            .onChange(of: model.transcript) {
                if let last = model.transcript.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }

    @ViewBuilder
    private func transcriptRow(_ row: RelaySessionModel.ChatRow) -> some View {
        switch row.role {
        case "user":
            Text(row.text)
                .padding(10)
                .background(Color.accentPrimary.opacity(0.25), in: RoundedRectangle(cornerRadius: 12))
                .frame(maxWidth: .infinity, alignment: .trailing)
        case "tool":
            Label(row.text, systemImage: "wrench.and.screwdriver")
                .font(.caption)
                .foregroundStyle(Color.secondary)
        default:
            Text(row.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
    }

    private func approvalBar(_ approval: RelaySessionModel.PendingApproval) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(
                approval.description ?? approval.command ?? "The agent needs approval.",
                systemImage: "hand.raised"
            )
            .font(.footnote)
            HStack {
                ForEach(approval.choices, id: \.self) { choice in
                    Button(choice.capitalized) {
                        Task { await model.respondToApproval(choice: choice) }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.surfaceMid)
    }

    private func clarifyBar(_ clarify: RelaySessionModel.PendingClarify) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(clarify.question, systemImage: "questionmark.bubble")
                .font(.footnote)
            if clarify.choices.isEmpty {
                HStack {
                    TextField("Answer", text: $clarifyAnswer)
                        .padding(8)
                        .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 8))
                    Button("Send") {
                        let answer = clarifyAnswer
                        clarifyAnswer = ""
                        Task { await model.respondToClarify(answer: answer) }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            } else {
                ForEach(clarify.choices, id: \.self) { choice in
                    Button(choice) {
                        Task { await model.respondToClarify(answer: choice) }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.surfaceMid)
    }

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("Message", text: $draft, axis: .vertical)
                .lineLimit(1...4)
                .padding(10)
                .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
            if model.running {
                Button {
                    Task { await model.interrupt() }
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.title2)
                }
            } else {
                Button {
                    let text = draft
                    draft = ""
                    Task { await model.submit(text) }
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                }
                .disabled(
                    draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        || model.runtimeSessionID == nil
                )
            }
        }
        .padding(12)
    }
}
