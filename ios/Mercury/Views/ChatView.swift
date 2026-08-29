import SwiftUI

/// Conversation screen for one session.
///
/// Loads transcript history over REST, opens a ticketed chat WebSocket, and
/// streams live turns into the transcript. Auto-scroll policy is ported from
/// Android's chat pane:
/// - Follow is an INTENT: only a user drag disengages it; scrolling back to
///   the bottom re-engages it (index-visibility gating permanently broke
///   follow during streaming bursts on Android).
/// - The initial jump-to-bottom waits for first content instead of firing
///   once before layout exists.
struct ChatView: View {
    let sessionID: String
    let title: String

    /// True for the "+" flow: no durable session exists yet, so the runtime is
    /// created lazily over WebSocket (`session.create`) instead of resumed,
    /// and the REST transcript load is skipped entirely.
    let isNewSession: Bool
    /// Project workspace for a project-scoped new session: forwarded as
    /// `session.create`'s `cwd` (Android createProjectSession parity). Nil for
    /// Home / no-project sessions — the server applies its default cwd.
    let newSessionWorkspacePath: String?
    let incomingShare: IncomingShareDraft?

    init(
        sessionID: String,
        title: String,
        isNewSession: Bool = false,
        newSessionWorkspacePath: String? = nil,
        incomingShare: IncomingShareDraft? = nil
    ) {
        self.sessionID = sessionID
        self.title = title
        self.isNewSession = isNewSession
        self.newSessionWorkspacePath = newSessionWorkspacePath
        self.incomingShare = incomingShare
        _titleText = State(initialValue: title)
        _draft = State(initialValue: incomingShare?.text ?? "")
        _composerNotice = State(initialValue: incomingShare?.notice)
        var initialTranscript = TranscriptState(isNewSession: isNewSession)
        // Mirrors pre-extraction isOurSession(_:): an empty navigation id
        // never matches anything.
        if !sessionID.isEmpty {
            initialTranscript.ownSessionIDs.insert(sessionID)
        }
        _transcript = State(initialValue: initialTranscript)
    }

    /// Entry point for SessionListView's new-chat flow: empty session id,
    /// placeholder title, lazy runtime creation on first connect.
    static func newSession(incomingShare: IncomingShareDraft? = nil) -> ChatView {
        ChatView(sessionID: "", title: "New chat", isNewSession: true, incomingShare: incomingShare)
    }

    /// Entry point for the Projects flow: same lazy `session.create` path,
    /// but rooted in the project's workspace (Android "New task" parity).
    static func newProjectSession(workspacePath: String?) -> ChatView {
        ChatView(
            sessionID: "",
            title: "New task",
            isNewSession: true,
            newSessionWorkspacePath: workspacePath
        )
    }

    @Environment(AppModel.self) private var appModel
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(VoiceDisplayPreferences.playbackControlsKey)
    private var showMessagePlaybackControls = false

    // MARK: Transcript state
    //
    // Row modeling and event mutation live in TranscriptState (pure value
    // type, MercuryKit/Chat/TranscriptReducer.swift). This view only renders
    // from it and keeps the UI-only concerns: scroll/follow-bottom intent,
    // reconnect policy, and sheet presentation triggers.

    @State private var transcript = TranscriptState()
    @State private var loadError: String?
    @State private var draft = ""
    @State private var composerError: String?
    @State private var dictation: ComposerDictationCoordinator?
    @State private var readAloud: ReadAloudController?
    @State private var incomingShareApplied = false
    @State private var processRows: [ActivityProcess] = []
    @State private var composerNotice: String?
    @State private var isSending = false
    @State private var isStopping = false
    @State private var isComposerActionPending = false
    @State private var userMessageScrollGeneration = 0
    @State private var connectionNote: String?

    // MARK: M7 session controls

    @State private var showModelPicker = false
    @State private var modelOptions: ModelOptions?
    @State private var currentModelSelection: ModelSelection?
    @State private var currentReasoningEffort: String?
    @State private var currentFastMode: Bool?
    @State private var modelPickerLoading = false
    @State private var modelPickerApplying = false
    @State private var modelPickerError: String?
    @State private var modelFeatureSupported = true
    @State private var steerSupported = true
    @State private var pendingModelConfirmation: PendingModelConfirmation?

    @State private var showContextSheet = false
    @State private var sessionUsage: SessionUsage?
    @State private var contextBreakdown: SessionContextBreakdown?
    @State private var contextLoading = false
    @State private var contextBusy = false
    @State private var contextError: String?
    @State private var contextStatus: String?
    @State private var usageSupported = true
    @State private var breakdownSupported = true
    @State private var compressSupported = true
    @State private var undoSupported = true
    @State private var branchSupported = true
    @State private var contextGeneration = 0
    @State private var branchDestination: BranchDestination?

    @State private var slashItems: [SlashCompletionItem] = []
    @State private var slashReplaceFrom = 0
    @State private var slashGeneration = 0
    @State private var slashCompletionTask: Task<Void, Never>?
    @State private var slashCompletionSupported = true

    // MARK: Attachments (M6.3)
    //
    // Staged metadata rides StagedAttachment (policy-admitted); the raw bytes
    // live only in this transient dictionary until send, never persisted.

    @State private var stagedAttachments: [StagedAttachment] = []
    @State private var stagedBytes: [String: Data] = [:]
    @State private var stagedHostReferences: [StagedHostReference] = []

    // MARK: Secure blocking input (M5.4)

    /// Secret/sudo prompt awaiting user input. terminalRead/previewRead/
    /// windowRead never surface UI — they are auto-answered empty (Android
    /// parity: the released bridge contract defines an empty response as
    /// "surface unavailable").
    private struct SecureRequest: Identifiable {
        let kind: UnsupportedBlockingKind
        let requestID: String
        let prompt: String?
        var id: String { requestID }
    }

    private struct PendingModelConfirmation: Identifiable {
        let selection: ModelSelection
        let message: String
        var id: String { selection.provider + "\u{0}" + selection.model }
    }

    private struct BranchDestination: Hashable {
        let durableID: String
        let title: String
    }

    @State private var pendingSecure: SecureRequest?

    // MARK: Live connection

    @State private var connection: ChatConnection?
    @State private var connectionOwnership = ChatConnectionOwnership()
    @State private var runtimeSessionID: String?
    /// Durable session id adopted from `session.create`'s stored_session_id
    /// once the gateway persists the new runtime session.
    @State private var durableID: String?
    @State private var eventTask: Task<Void, Never>?
    @State private var pendingRequest: ApprovalSheet.Request?
    @State private var didOpen = false

    // MARK: Reconnect policy (Android recoverChat parity)

    private enum ConnectionState {
        case connecting
        case live
        case reconnecting(attempt: Int)
        case offline
    }

    @State private var connectionState: ConnectionState = .connecting
    /// Scheduled reconnect attempt; cancelled on disappear so a pending
    /// timer never fires after the screen is gone.
    @State private var reconnectTask: Task<Void, Never>?
    /// Set when close() was deliberate — peer drops trigger recovery, our
    /// own teardown must not.
    @State private var closedByUs = false

    /// Android: MAX_CHAT_RECOVERIES_PER_OPERATION = 2, backoff 500/1000/2000ms.
    private static let maxRecoveryAttempts = 3
    private static let recoveryBackoffMillis: [UInt64] = [500, 1_000, 2_000]

    /// Displayed title; seeded from `title`, updated live when a
    /// .sessionTitle event renames our (new) session.
    @State private var titleText: String

    // MARK: Follow-scroll intent

    @State private var followBottom = true
    @State private var initialScrollDone = false
    @State private var loadedTranscriptCount = 0
    @State private var hasMoreHistory = false
    @State private var isLoadingHistory = false
    @State private var historyError: String?

    private let lastRowID = "transcript-end"
    private let firstRowID = "transcript-start"

    /// Composer stays disabled while the live connection is down.
    private var isConnectionDown: Bool {
        switch connectionState {
        case .reconnecting, .offline: return true
        default: return false
        }
    }

    private var connectionStateIsNotLive: Bool {
        if case .live = connectionState { return false }
        return true
    }

    /// The durable id used for notification keying and deep-links. Prefer the
    /// adopted durable id; fall back to the navigation session id (an existing
    /// session opened directly). A brand-new chat with neither yet is nil until
    /// `session.create` returns a stored id.
    private var notificationSessionID: String? {
        if let durableID, !durableID.isEmpty { return durableID }
        if !sessionID.isEmpty { return sessionID }
        return nil
    }

    private var currentModelCapabilities: ModelCapabilities? {
        modelOptions?.capabilities(for: currentModelSelection ?? modelOptions?.current)
    }

    private var composerModelLabel: String? {
        currentModelSelection?.model.split(separator: "/").last.map(String.init)
    }

    private var composerContextPercent: Double? {
        if let percent = sessionUsage?.contextPercent { return percent }
        if let used = sessionUsage?.contextUsedTokens,
           let maximum = sessionUsage?.contextMaxTokens,
           maximum > 0 {
            return Double(used) * 100 / Double(maximum)
        }
        return contextBreakdown?.percent
    }

    var body: some View {
        VStack(spacing: 0) {
            if let connectionNote {
                Label(connectionNote, systemImage: "wifi.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .padding(.vertical, 4)
            }
            switch connectionState {
            case .connecting:
                Label("Connecting…", systemImage: "bolt.horizontal")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .padding(.vertical, 4)
            case .reconnecting(let attempt):
                Label("Reconnecting… (attempt \(attempt))", systemImage: "arrow.clockwise")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .padding(.vertical, 4)
            case .offline:
                Button(action: retryConnectionNow) {
                    Label("Chat offline — tap to retry", systemImage: "wifi.exclamationmark")
                        .font(.footnote)
                        .foregroundStyle(Color.statusAlert)
                }
                .padding(.vertical, 4)
            case .live:
                EmptyView()
            }
            if let loadError {
                Label(loadError, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .padding(.vertical, 4)
            }

            transcriptList

            Divider().overlay(Color.separatorSubtle)

            SlashCompletionOverlay(items: slashItems) { item in
                draft = applySlashCompletion(draft, item: item, replaceFrom: slashReplaceFrom)
                slashItems = []
            }

            if let dictation, case .failed(let failure) = dictation.state {
                DictationFailureLabel(failure: failure)
                    .padding(.horizontal)
            }

            ComposerBar(
                draft: $draft,
                errorMessage: Binding(get: { composerError }, set: { composerError = $0 }),
                noticeMessage: composerNotice,
                isSending: composerIsBusy,
                onSend: {
                    sendDraft()
                },
                showStop: M7ComposerPolicy.shouldShowStopButton(
                    isSending: isSending,
                    turnActive: turnInFlight,
                    draft: draft
                ),
                isStopping: isStopping,
                onStop: interruptTurn,
                isSteering: turnInFlight && steerSupported,
                attachmentsEnabled: !turnInFlight,
                attachments: stagedAttachments,
                onAttachmentPicked: { filename, mimeType, data in
                    stageAttachment(filename: filename, mimeType: mimeType, data: data)
                },
                onRemoveAttachment: { id in
                    stagedAttachments.removeAll { $0.id == id }
                    stagedBytes[id] = nil
                },
                onAttachmentError: { message in
                    composerError = message
                },
                hostReferences: stagedHostReferences,
                onHostReferencePicked: stageHostReference,
                onRemoveHostReference: { id in
                    stagedHostReferences.removeAll { $0.id == id }
                },
                dictation: dictation,
                modelLabel: composerModelLabel,
                reasoningEffort: currentReasoningEffort,
                reasoningSupported: currentModelCapabilities?.reasoning == true
                    || currentReasoningEffort != nil,
                fastSupported: currentModelCapabilities?.fast == true,
                fastEnabled: currentFastMode == true,
                contextPercent: composerContextPercent,
                metadataControlsEnabled: !connectionStateIsNotLive && runtimeSessionID != nil,
                onOpenModelPicker: modelFeatureSupported ? openModelPicker : nil,
                onReasoningSelected: applyReasoning,
                onFastSelected: applyFast,
                onOpenContext: (usageSupported || breakdownSupported || compressSupported || undoSupported || branchSupported)
                    ? openContextSheet
                    : nil
            )
        }
        .navigationTitle(titleText.isEmpty ? "Session" : titleText)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.amoledBlack, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .task {
            // Visibility can change while SwiftUI retains this view and restarts
            // its task. Restore it even when the connection is already owned.
            appModel.setVisibleSession(notificationSessionID)
            // SwiftUI may recreate the task while the view is still mounted.
            // Android's ViewModel refuses a second open for an already-owned
            // session; make the same admission decision before any await.
            guard !didOpen else { return }
            didOpen = true
            applyIncomingShare()
            if let notifyID = notificationSessionID {
                // Engaged scope: background reconciliation may notify about a
                // session only after the app has opened it (Android parity).
                appModel.markSessionEngaged(notifyID)
                Task { await appModel.clearNotifications(sessionID: notifyID) }
            }
            await configureNativeVoice()
            await open()
            didOpen = true
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await catchUpAfterForeground() }
        }
        .onChange(of: draft) {
            scheduleSlashCompletion(for: draft)
        }
        .onDisappear {
            // A transient SwiftUI disappearance must not interrupt an active
            // server-side turn. Android keeps its live controller in the
            // ViewModel; preserve the iOS connection while a turn is active so
            // the next foreground/open can resume the same runtime instead of
            // creating a second visible attempt.
            if ChatDisappearancePolicy.action(
                turnInFlight: turnInFlight,
                isSending: isSending
            ) == .preserveConnectionAndObserver {
                appModel.setVisibleSession(nil)
                return
            }
            // Deliberate teardown for an idle session: no reconnect may fire
            // after dismissal.
            closedByUs = true
            appModel.setVisibleSession(nil)
            reconnectTask?.cancel()
            reconnectTask = nil
            slashCompletionTask?.cancel()
            slashCompletionTask = nil
            dictation?.cancel()
            readAloud?.stop()
            eventTask?.cancel()
            eventTask = nil
            let closingConnection = connection
            connectionOwnership.invalidate()
            connection = nil
            Task { await closingConnection?.close() }
        }
        .sheet(isPresented: $showModelPicker) {
            ModelPickerSheet(
                options: modelOptions,
                selection: currentModelSelection,
                isLoading: modelPickerLoading,
                isApplying: modelPickerApplying,
                errorMessage: modelPickerError,
                onRetry: loadModelOptions,
                onSelectModel: { applyModel($0, confirmed: false) }
            )
        }
        .sheet(isPresented: $showContextSheet) {
            ContextSheet(
                usage: sessionUsage,
                breakdown: contextBreakdown,
                isLoading: contextLoading,
                isBusy: contextBusy,
                isIdle: !turnInFlight,
                statusMessage: contextStatus,
                errorMessage: contextError,
                compressSupported: compressSupported,
                undoSupported: undoSupported,
                branchSupported: branchSupported,
                onRefresh: loadContext,
                onCompress: compressContext,
                onUndo: undoLastTurn,
                onBranch: branchSession
            )
        }
        .alert(item: $pendingModelConfirmation) { pending in
            Alert(
                title: Text("Confirm model change"),
                message: Text(pending.message),
                primaryButton: .default(Text("Use Model")) {
                    applyModel(pending.selection, confirmed: true)
                },
                secondaryButton: .cancel()
            )
        }
        .navigationDestination(isPresented: Binding(
            get: { branchDestination != nil },
            set: { if !$0 { branchDestination = nil } }
        )) {
            if let destination = branchDestination {
                ChatView(sessionID: destination.durableID, title: destination.title)
            }
        }
        .sheet(item: $pendingRequest) { request in
            ApprovalSheet(
                request: request,
                isBusy: isSending,
                onApprovalChoice: { choice in
                    await answerApproval(choice)
                },
                onClarifyAnswer: { answer in
                    await answerClarify(answer)
                },
                onDismiss: { pendingRequest = nil }
            )
        }
        .sheet(item: $pendingSecure) { secure in
            SecureInputSheet(
                kind: secure.kind,
                prompt: secure.prompt,
                onSubmit: { value in
                    let requestID = secure.requestID
                    let kind = secure.kind
                    pendingSecure = nil
                    Task { await answerSecure(kind: kind, requestID: requestID, value: value) }
                },
                onCancel: { pendingSecure = nil }
            )
        }
        .amoledScreen()
    }

    // MARK: Transcript

    private var turnInFlight: Bool {
        transcript.hasStreamingAssistant
    }

    /// A running turn is intentionally not busy: its composer steers. Only a
    /// transport outage, a local RPC, or pre-stream prompt submission blocks it.
    private var composerIsBusy: Bool {
        isConnectionDown || isComposerActionPending || (isSending && (!turnInFlight || !steerSupported))
    }

    private var transcriptList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if hasMoreHistory || historyError != nil {
                        Button {
                            Task { await loadEarlierHistory() }
                        } label: {
                            HStack(spacing: 6) {
                                if isLoadingHistory {
                                    ProgressView().controlSize(.small)
                                }
                                Text(historyError ?? "Load earlier messages")
                                    .font(.caption.weight(.medium))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                        .disabled(isLoadingHistory)
                        .accessibilityLabel(historyError.map { _ in "Retry loading earlier messages" } ?? "Load earlier messages")
                        .id(firstRowID)
                    }
                    ForEach(coalesceTranscriptEntries(transcript.rows)) { entry in
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
                                    ), let readAloud {
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
                    if !transcript.tools.isEmpty || !processRows.isEmpty {
                        ActivityStackView(
                            state: ActivityStackState(processes: processRows),
                            tools: transcript.tools,
                            turnActive: isSending
                        )
                    }
                    if let generating = transcript.generatingStatusText {
                        Label(generating, systemImage: "gearshape")
                            .font(.caption)
                            .foregroundStyle(Color.secondary)
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
                    followBottom = false
                }
            )
            .onChange(of: transcript.rows.count) {
                guard followBottom else { return }
                // A local user echo must land in its final position in one
                // layout pass. Animating the row-count scroll while the
                // keyboard/inset transition is also settling produces the
                // visible halfway-state before the bubble reaches the tail.
                if transcript.rows.last?.role.lowercased() == "user" {
                    // ChatGPT-style submission: place the new user turn at
                    // the top of the conversation viewport immediately. A
                    // bottom anchor here can briefly win while the keyboard
                    // is collapsing, then snap the short transcript upward.
                    if let userID = transcript.rows.last?.id {
                        userMessageScrollGeneration &+= 1
                        let generation = userMessageScrollGeneration
                        Task { @MainActor in
                            // Let SwiftUI commit and measure the inserted row
                            // before resolving its top anchor. The immediate
                            // call can be ignored or clamped against the
                            // previous content offset, producing the brief
                            // halfway position seen during submission.
                            await Task.yield()
                            try? await Task.sleep(nanoseconds: 16_000_000)
                            guard !Task.isCancelled,
                                  generation == userMessageScrollGeneration else { return }
                            proxy.scrollTo(userID, anchor: .top)
                        }
                    }
                } else {
                    withAnimation(.easeOut(duration: 0.15)) {
                        proxy.scrollTo(lastRowID, anchor: .bottom)
                    }
                }
            }
            .onChange(of: transcript.rows.last?.text) {
                guard followBottom else { return }
                proxy.scrollTo(lastRowID, anchor: .bottom)
            }
            .onChange(of: initialScrollDone) {
                // Initial jump waits for first content (async transcript load).
                guard !initialScrollDone, !transcript.rows.isEmpty else { return }
                initialScrollDone = true
                proxy.scrollTo(lastRowID, anchor: .bottom)
            }
        }
    }

    // MARK: Open / resume

    private func open() async {
        if isNewSession {
            // Brand-new chat: no history exists, so skip the REST transcript
            // load entirely and go straight to the live connection.
            await connectAndResume()
            return
        }
        await loadTranscript()
        await connectAndResume()
    }

    @discardableResult
    private func loadTranscript(
        durableSessionID requestedID: String? = nil,
        preservingActiveTurn: Bool = false
    ) async -> Bool {
        let isRelay = appModel.activeRelayTarget != nil
        guard isRelay || appModel.serverOrigin != nil else {
            loadError = "No server connected."
            return false
        }
        let transcriptID = requestedID ?? sessionID
        guard !transcriptID.isEmpty else { return false }
        let turnWasActive = preservingActiveTurn && (isSending || transcript.hasStreamingAssistant)
        if !preservingActiveTurn, let origin = appModel.serverOrigin {
            let cached = await appModel.cachedTranscript(
                origin: origin,
                profile: appModel.activeProfile,
                sessionID: transcriptID
            )
            if !cached.isEmpty {
                transcript.loadTranscript(cached.map {
                    TranscriptState.RestoredMessage(
                        role: $0.role.rawValue,
                        content: $0.text,
                        reasoningText: $0.reasoningText
                    )
                })
                initialScrollDone = true
            }
        }
        do {
            let fetched: [TranscriptMessage]
            if isRelay {
                fetched = try await relayTranscriptMessages(
                    transcriptID: transcriptID, limit: 100, offset: 0
                )
            } else {
                guard let origin = appModel.serverOrigin else { return false }
                let client = makeHTTPClient(origin: origin)
                let sessions = SessionsClient(client: client, profile: appModel.activeProfile)
                fetched = try await sessions.transcript(sessionID: transcriptID)
            }
            let history = TranscriptPageOrdering.forDisplay(fetched)
            let restored = history.map { message in
                TranscriptState.RestoredMessage(
                    role: message.role,
                    content: message.content,
                    toolName: message.toolName,
                    reasoningText: message.reasoningText
                )
            }
            if preservingActiveTurn {
                isSending = transcript.reconcileForegroundTranscript(
                    restored,
                    turnWasActive: turnWasActive
                )
            } else {
                transcript.loadTranscript(restored)
            }
            loadedTranscriptCount = history.count
            hasMoreHistory = TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: history.count)
            historyError = nil
            await cacheCurrentTranscript()
            initialScrollDone = !transcript.rows.isEmpty
            loadError = nil
            return true
        } catch {
            // Keep whatever rendered; surface a banner. No secret material here.
            loadError = "Could not load transcript for this session."
            return false
        }
    }

    /// Fetches one transcript page over the relay's in-process read
    /// (`relay.session.transcript`, same shape as the REST endpoint). The
    /// live chat connection carries the read on its own encrypted channel;
    /// before one exists, a short-lived relay connection serves it (the
    /// router permits one device socket, so never both at once).
    private func relayTranscriptMessages(
        transcriptID: String,
        limit: Int,
        offset: Int
    ) async throws -> [TranscriptMessage] {
        guard let target = appModel.activeRelayTarget else {
            throw ChatError.transport("No relay target is active")
        }
        let params: [String: Any] = [
            "profile": appModel.activeProfile,
            "session_id": transcriptID,
            "limit": limit,
            "offset": offset,
            "order": "latest",
        ]
        let result: [String: Any]
        if let live = connection {
            result = try await live.relayRequest("relay.session.transcript", params: params)
        } else {
            let connected = try await RelayConnector.connect(
                target: target, profile: appModel.activeProfile
            )
            let short = try ChatConnection(socket: RelayChatSocket(connected: connected))
            _ = short.start()
            defer { Task { await short.close() } }
            result = try await short.relayRequest("relay.session.transcript", params: params)
        }
        guard let raw = result["messages"] as? [[String: Any]] else { return [] }
        let data = try JSONSerialization.data(withJSONObject: raw)
        return (try? JSONDecoder().decode([TranscriptMessage].self, from: data)) ?? []
    }

    /// SwiftUI keeps this view alive while iOS backgrounds the app, so its
    /// initial task does not run again on reopen. Refresh the visible transcript
    /// explicitly and reset a dead socket's foreground reconnect budget.
    private func catchUpAfterForeground() async {
        guard didOpen, !closedByUs else { return }
        appModel.setVisibleSession(notificationSessionID)
        if !isNewSession {
            _ = await loadTranscript(
                durableSessionID: durableID ?? sessionID,
                preservingActiveTurn: true
            )
        }
        if connection == nil {
            retryConnectionNow()
        }
    }

    /// Prepends one older window of server-side transcript history. The
    /// reducer keeps row identity stable so the scroll anchor survives the
    /// insertion.
    private func loadEarlierHistory() async {
        guard !isLoadingHistory, !(hasMoreHistory == false && historyError == nil) else { return }
        let isRelay = appModel.activeRelayTarget != nil
        guard isRelay || appModel.serverOrigin != nil else { return }
        let transcriptID = durableID ?? sessionID
        guard !transcriptID.isEmpty else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        do {
            let fetchedOlder: [TranscriptMessage]
            if isRelay {
                fetchedOlder = try await relayTranscriptMessages(
                    transcriptID: transcriptID,
                    limit: TranscriptHistoryPolicy.pageSize,
                    offset: TranscriptHistoryPolicy.nextOffset(loadedCount: loadedTranscriptCount)
                )
            } else {
                guard let origin = appModel.serverOrigin else { return }
                let client = makeHTTPClient(origin: origin)
                let sessions = SessionsClient(client: client, profile: appModel.activeProfile)
                fetchedOlder = try await sessions.olderTranscript(
                    sessionID: transcriptID,
                    offset: TranscriptHistoryPolicy.nextOffset(loadedCount: loadedTranscriptCount)
                )
            }
            let older = TranscriptPageOrdering.forDisplay(fetchedOlder)
            guard !older.isEmpty else {
                hasMoreHistory = false
                return
            }
            let restored = older.map { message in
                TranscriptState.RestoredMessage(
                    role: message.role,
                    content: message.content,
                    toolName: message.toolName,
                    reasoningText: message.reasoningText
                )
            }
            transcript.prependHistory(restored)
            loadedTranscriptCount += older.count
            hasMoreHistory = TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: older.count)
            historyError = nil
        } catch {
            historyError = "Could not load earlier messages. Tap to retry."
        }
    }

    private func cacheCurrentTranscript() async {
        guard let origin = appModel.serverOrigin else { return }
        let durableSessionID = durableID ?? sessionID
        guard !durableSessionID.isEmpty else { return }
        let summary = appModel.sessions.first(where: { $0.id == durableSessionID })
            ?? SessionRow(
                id: durableSessionID,
                title: titleText,
                preview: transcript.rows.last?.text ?? "",
                profile: appModel.activeProfile
            )
        let messages = transcript.rows.compactMap { row -> OfflineCachedMessage? in
            guard let role = OfflineCachedMessageRole(rawValue: row.role.lowercased()) else { return nil }
            return OfflineCachedMessage(role: role, text: row.text, reasoningText: row.reasoningText)
        }
        await appModel.cacheTranscript(
            origin: origin,
            profile: appModel.activeProfile,
            summary: summary,
            messages: messages
        )
    }

    private func loadProcessRows() async {
        guard let connection, let runtimeSessionID else { return }
        let client = OperationsClient(request: { method, params in
            try await connection.operationsRequest(method, params: params)
        })
        processRows = (try? await client.listProcesses(runtimeSessionID: runtimeSessionID)) ?? []
    }

    /// One connect attempt: ticket → socket → event loop → resume/create.
    /// Returns true when the session reached `.live` (resume/create succeeded);
    /// false means the caller should try again or give up.
    @discardableResult
    private func establishConnection(attempt: Int) async -> Bool {
        // Task re-entry safety: for the new-chat flow, once the runtime exists
        // (createSession already ran) never run this again.
        if isNewSession && runtimeSessionID != nil { return true }
        guard appModel.activeRelayTarget != nil || appModel.serverOrigin != nil else {
            return false
        }

        var candidateConnection: ChatConnection?
        do {
            let candidate: ChatConnection
            if let relayTarget = appModel.activeRelayTarget {
                // Relay mode: same Hermes JSON-RPC contract through the
                // E2EE channel. The router permits one device socket per
                // installation, so while this chat is open it owns the app's
                // only relay connection (list refreshes pause while a chat
                // is visible).
                let connected = try await RelayConnector.connect(
                    target: relayTarget,
                    profile: appModel.activeProfile
                )
                candidate = try ChatConnection(socket: RelayChatSocket(connected: connected))
            } else {
                guard let origin = appModel.serverOrigin else { return false }
                let token = storedAccessToken(origin: origin)
                let ticketClient = WsTicketClient(session: .shared)
                let gateway = try ChatGateway(
                    origin: origin,
                    accessToken: token,
                    ticketClient: ticketClient,
                    socketFactory: URLSessionChatWebSocketFactory()
                )
                // Tickets are single-use: every attempt mints a fresh one.
                let socket = try await gateway.connect()
                candidate = try ChatConnection(socket: socket)
            }
            candidateConnection = candidate
            let stream = candidate.start()

            // Keep the candidate private until create/resume proves it owns a
            // valid runtime. Publishing earlier lets a failed or stale attempt
            // clear a newer connection when its event stream finishes.
            if isNewSession {
                let created = try await candidate.createSession(
                    profile: nil,
                    workspacePath: newSessionWorkspacePath
                )
                runtimeSessionID = created.runtimeSessionID
                transcript.ownSessionIDs.insert(created.runtimeSessionID)
                if let stored = created.durableSessionID {
                    durableID = stored
                    // A brand-new session's durable id only exists after
                    // create; register it now so background reconciliation is
                    // allowed to notify about it (Android engaged-scope parity).
                    appModel.markSessionEngaged(stored)
                }
            } else {
                let resumed = try await candidate.resume(durableSessionID: sessionID, profile: nil)
                runtimeSessionID = resumed.runtimeSessionID
                transcript.ownSessionIDs.insert(resumed.runtimeSessionID)
                if let provider = resumed.provider, let model = resumed.model {
                    currentModelSelection = ModelSelection(provider: provider, model: model)
                }
                currentReasoningEffort = resumed.reasoningEffort
                currentFastMode = resumed.fastMode
                // `session.resume` carries an authoritative message snapshot.
                // REST may have raced the turn completion or failed while the
                // app was away, so do not rely on the earlier REST load alone.
                // Android applies this snapshot before reconciling in-flight
                // state; Mercury must do the same to show replies after a
                // terminate/relaunch cycle.
                let resumedRows = transcriptRows(from: resumed.messages)
                if !resumedRows.isEmpty {
                    transcript.loadTranscript(resumedRows)
                }
                if resumed.running || resumed.inflight != nil {
                    // A turn was already executing when we attached; reopen the
                    // REST-loaded assistant row (rather than creating a second
                    // bubble) so subsequent deltas append to the same reply.
                    let inflightText = resumed.inflight?.assistant ?? ""
                    transcript.ensureInflightAssistantRow(
                        text: inflightText,
                        completed: false
                    )
                } else {
                    // The turn completed while we were away. Android performs
                    // a second REST transcript load here because the first
                    // load may have raced the tool phase and omitted the final
                    // assistant response. Keep the resume snapshot visible if
                    // that follow-up request is temporarily unavailable.
                    _ = await loadTranscript(durableSessionID: sessionID)
                    transcript.finishStreamingAssistant()
                }
            }

            // Task cancellation is cooperative: a socket/create/resume await
            // may return normally after dismissal or a manual retry cancelled
            // this attempt. Never let that stale candidate become active.
            guard let ownershipToken = connectionOwnership.publish(
                when: !Task.isCancelled && !closedByUs
            ) else {
                await candidate.close()
                return false
            }
            connection = candidate
            eventTask?.cancel()
            eventTask = Task { [weak candidate] in
                guard let candidate else { return }
                for await event in stream {
                    let stillOwnsConnection = await MainActor.run {
                        connectionOwnership.isCurrent(ownershipToken)
                            && connection === candidate
                    }
                    guard stillOwnsConnection else { break }
                    handleEvent(event)
                }
                await MainActor.run {
                    guard connectionOwnership.isCurrent(ownershipToken),
                          connection === candidate else { return }
                    _ = connectionOwnership.release(ifCurrent: ownershipToken)
                    connection = nil
                    clearSlashCompletion()
                    // Unexpected stream end (peer drop / transport death) —
                    // deliberate close() never reaches here with closedByUs false.
                    guard !closedByUs else { return }
                    scheduleReconnect()
                }
            }

            if let durableID {
                transcript.ownSessionIDs.insert(durableID)
            }
            // Live notifications are keyed on the durable id; keep the visible
            // session in sync so suppression matches while on screen.
            appModel.setVisibleSession(notificationSessionID)
            connectionNote = nil
            connectionState = .live
            scheduleSlashCompletion(for: draft)
            loadModelOptions()
            loadContext()
            await loadProcessRows()
            return true
        } catch {
            if let candidateConnection {
                await candidateConnection.close()
            }
            return false
        }
    }

    private func connectAndResume() async {
        connectionState = .connecting
        closedByUs = false
        let ok = await establishConnection(attempt: 0)
        if !ok {
            scheduleReconnect()
        }
    }

    /// Android recoverChat parity: bounded attempts, fixed backoff ladder
    /// (500ms/1s/2s), cancel-safe between sleeps, offline banner at the end
    /// with a manual-retry tap target.
    private func scheduleReconnect() {
        guard reconnectTask == nil, !closedByUs else { return }
        reconnectTask = Task {
            for attempt in 1...Self.maxRecoveryAttempts {
                guard !Task.isCancelled, !closedByUs else { break }
                connectionState = .reconnecting(attempt: attempt)
                let millis = Self.recoveryBackoffMillis[min(attempt - 1, Self.recoveryBackoffMillis.count - 1)]
                try? await Task.sleep(nanoseconds: millis * 1_000_000)
                guard !Task.isCancelled, !closedByUs else { break }
                if await establishConnection(attempt: attempt) {
                    reconnectTask = nil
                    return
                }
            }
            if !Task.isCancelled, !closedByUs {
                connectionState = .offline
            }
            reconnectTask = nil
        }
    }

    private func retryConnectionNow() {
        reconnectTask?.cancel()
        reconnectTask = nil
        guard connection == nil, !closedByUs else { return }
        reconnectTask = Task {
            connectionState = .reconnecting(attempt: 1)
            if await establishConnection(attempt: 1) {
                reconnectTask = nil
                return
            }
            reconnectTask = nil
            scheduleReconnect()
        }
    }

    // MARK: M7 model, context, and completion controls

    private func openModelPicker() {
        guard modelFeatureSupported else {
            composerError = "Session model controls are not supported by this server."
            return
        }
        guard connection != nil, runtimeSessionID != nil else {
            composerError = "Not connected — reopen this session to choose a model."
            return
        }
        showModelPicker = true
        loadModelOptions()
    }

    private func loadModelOptions() {
        guard modelFeatureSupported, let connection, let runtimeSessionID else { return }
        modelPickerLoading = true
        modelPickerError = nil
        Task {
            do {
                let loaded = try await connection.loadModelOptions(runtimeSessionID: runtimeSessionID)
                await MainActor.run {
                    guard self.connection === connection else { return }
                    modelOptions = loaded
                    if let advertised = loaded.current { currentModelSelection = advertised }
                    modelPickerLoading = false
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    modelFeatureSupported = false
                    modelPickerLoading = false
                    showModelPicker = false
                }
            } catch {
                await MainActor.run {
                    modelPickerLoading = false
                    modelPickerError = "Could not load models for this session."
                }
            }
        }
    }

    private func applyModel(_ selection: ModelSelection, confirmed: Bool) {
        guard let options = modelOptions,
              options.providers.contains(where: { $0.slug == selection.provider && $0.models.contains(selection.model) }),
              let connection, let runtimeSessionID else {
            modelPickerError = "That model is not in the server's advertised catalog."
            return
        }
        modelPickerApplying = true
        modelPickerError = nil
        Task {
            do {
                let result = try await connection.setModel(
                    runtimeSessionID: runtimeSessionID,
                    provider: selection.provider,
                    model: selection.model,
                    confirmExpensiveModel: confirmed
                )
                await MainActor.run {
                    modelPickerApplying = false
                    if result.confirmationRequired {
                        pendingModelConfirmation = PendingModelConfirmation(
                            selection: selection,
                            message: result.confirmationMessage ?? "The server requires confirmation for this model."
                        )
                        showModelPicker = false
                    } else if result.accepted {
                        currentModelSelection = selection
                        showModelPicker = false
                        composerNotice = result.deferred
                            ? "Model will change after the active turn."
                            : "Session model updated."
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    modelFeatureSupported = false
                    modelPickerApplying = false
                    showModelPicker = false
                }
            } catch {
                await MainActor.run {
                    modelPickerApplying = false
                    modelPickerError = "Could not change the session model."
                }
            }
        }
    }

    private func applyReasoning(_ effort: String) {
        guard let canonical = ReasoningEffort.canonical(effort),
              modelFeatureSupported,
              let connection, let runtimeSessionID else {
            composerError = "Reasoning could not be changed."
            return
        }
        isComposerActionPending = true
        modelPickerApplying = true
        Task {
            do {
                let catalog: ModelOptions
                if let existing = modelOptions {
                    catalog = existing
                } else {
                    catalog = try await connection.loadModelOptions(runtimeSessionID: runtimeSessionID)
                }
                guard catalog.capabilities(for: currentModelSelection ?? catalog.current)?.reasoning == true else {
                    await MainActor.run {
                        modelOptions = catalog
                        if currentModelSelection == nil { currentModelSelection = catalog.current }
                        isComposerActionPending = false
                        modelPickerApplying = false
                        composerError = "The selected model does not explicitly advertise reasoning support."
                    }
                    return
                }
                try await connection.setReasoning(runtimeSessionID: runtimeSessionID, effort: canonical)
                await MainActor.run {
                    modelOptions = catalog
                    if currentModelSelection == nil { currentModelSelection = catalog.current }
                    currentReasoningEffort = canonical
                    isComposerActionPending = false
                    modelPickerApplying = false
                    composerError = nil
                    composerNotice = "Reasoning set to \(canonical)."
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    modelFeatureSupported = false
                    showModelPicker = false
                    isComposerActionPending = false
                    modelPickerApplying = false
                    composerError = "Session model controls are not supported by this server."
                }
            } catch {
                await MainActor.run {
                    isComposerActionPending = false
                    modelPickerApplying = false
                    composerError = "Could not change reasoning effort."
                }
            }
        }
    }

    private func applyFast(_ enabled: Bool) {
        guard currentModelCapabilities?.fast == true,
              let connection,
              let runtimeSessionID else {
            composerError = "Fast mode is unavailable for this model."
            return
        }
        isComposerActionPending = true
        Task {
            do {
                try await connection.setFast(runtimeSessionID: runtimeSessionID, enabled: enabled)
                await MainActor.run {
                    guard self.connection === connection else { return }
                    currentFastMode = enabled
                    isComposerActionPending = false
                    composerNotice = enabled ? "Fast mode enabled." : "Normal mode enabled."
                }
            } catch {
                await MainActor.run {
                    isComposerActionPending = false
                    composerError = "Could not change fast mode."
                }
            }
        }
    }

    private func openContextSheet() {
        guard connection != nil, runtimeSessionID != nil else { return }
        showContextSheet = true
        contextStatus = nil
        loadContext()
    }

    /// Explicit-only sequential usage → breakdown loading for this exact live runtime.
    private func loadContext() {
        guard let connection, let runtimeSessionID else { return }
        contextGeneration += 1
        let generation = contextGeneration
        contextLoading = true
        contextError = nil
        Task {
            var loadedUsage: SessionUsage?
            var loadedBreakdown: SessionContextBreakdown?
            var errorMessage: String?

            if usageSupported {
                do {
                    loadedUsage = try await connection.loadSessionUsage(runtimeSessionID: runtimeSessionID)
                } catch is ChatMethodNotFoundError {
                    await MainActor.run { usageSupported = false }
                } catch {
                    errorMessage = "Could not load session usage."
                }
            }
            if breakdownSupported {
                do {
                    loadedBreakdown = try await connection.loadContextBreakdown(runtimeSessionID: runtimeSessionID)
                } catch is ChatMethodNotFoundError {
                    await MainActor.run { breakdownSupported = false }
                } catch {
                    if errorMessage == nil { errorMessage = "Could not load context breakdown." }
                }
            }

            await MainActor.run {
                guard self.connection === connection, generation == contextGeneration else { return }
                if let loadedUsage { sessionUsage = loadedUsage }
                if let loadedBreakdown { contextBreakdown = loadedBreakdown }
                contextError = errorMessage
                contextLoading = false
            }
        }
    }

    private func compressContext() {
        guard !turnInFlight, compressSupported, let connection, let runtimeSessionID else { return }
        contextBusy = true
        contextError = nil
        contextStatus = nil
        Task {
            do {
                let result = try await connection.compressSession(runtimeSessionID: runtimeSessionID)
                await MainActor.run {
                    contextBusy = false
                    if result.status == "compressed" {
                        transcript.loadTranscript(transcriptRows(from: result.messages))
                        if let usage = result.usage { sessionUsage = usage }
                        contextStatus = "Context compressed."
                    } else {
                        contextError = "Context was not compressed."
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    compressSupported = false
                    contextBusy = false
                }
            } catch {
                await MainActor.run {
                    contextBusy = false
                    contextError = "Could not compress context."
                }
            }
        }
    }

    private func undoLastTurn() {
        guard !turnInFlight, undoSupported, let connection, let runtimeSessionID else { return }
        let transcriptID = durableID ?? sessionID
        guard !transcriptID.isEmpty else {
            contextError = "This session has not been stored yet."
            return
        }
        contextBusy = true
        contextError = nil
        contextStatus = nil
        Task {
            do {
                _ = try await connection.undoSession(runtimeSessionID: runtimeSessionID)
                let reloaded = await loadTranscript(durableSessionID: transcriptID)
                await MainActor.run {
                    contextBusy = false
                    if reloaded {
                        contextStatus = "Last turn undone."
                    } else {
                        contextError = "The turn was undone, but the transcript could not be reloaded."
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    undoSupported = false
                    contextBusy = false
                }
            } catch {
                await MainActor.run {
                    contextBusy = false
                    contextError = "Could not undo the last turn."
                }
            }
        }
    }

    private func branchSession(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !turnInFlight, branchSupported, !trimmed.isEmpty,
              let connection, let runtimeSessionID else { return }
        contextBusy = true
        contextError = nil
        contextStatus = nil
        Task {
            do {
                let result = try await connection.branchSession(
                    runtimeSessionID: runtimeSessionID,
                    count: nil,
                    name: trimmed
                )
                await MainActor.run {
                    contextBusy = false
                    let branchTitle = result.title ?? trimmed
                    branchDestination = BranchDestination(
                        durableID: result.durableSessionID,
                        title: branchTitle
                    )
                    showContextSheet = false
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    branchSupported = false
                    contextBusy = false
                }
            } catch {
                await MainActor.run {
                    contextBusy = false
                    contextError = "Could not branch this session."
                }
            }
        }
    }

    private func clearSlashCompletion() {
        slashCompletionTask?.cancel()
        slashCompletionTask = nil
        slashGeneration += 1
        slashItems = []
    }

    private func scheduleSlashCompletion(for text: String) {
        slashCompletionTask?.cancel()
        slashGeneration += 1
        let generation = slashGeneration
        guard slashCompletionSupported,
              M7ComposerPolicy.shouldRequestSlashCompletion(
                text: text,
                connectionIsLive: !connectionStateIsNotLive && connection != nil
              ),
              let connection else {
            slashItems = []
            return
        }

        slashCompletionTask = Task {
            do {
                try await Task.sleep(nanoseconds: 60_000_000)
                try Task.checkCancellation()
                let result = try await connection.completeSlash(text: text)
                try Task.checkCancellation()
                await MainActor.run {
                    guard self.connection === connection,
                          M7ComposerPolicy.mayPublishSlashCompletion(
                            responseGeneration: generation,
                            currentGeneration: slashGeneration
                          ) else { return }
                    slashItems = result.items
                    slashReplaceFrom = result.replaceFrom
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    slashCompletionSupported = false
                    slashItems = []
                }
            } catch {
                await MainActor.run {
                    if generation == slashGeneration { slashItems = [] }
                }
            }
        }
    }

    private func transcriptRows(from messages: [[String: Any]]) -> [TranscriptState.RestoredMessage] {
        messages.compactMap { message in
            guard let role = message["role"] as? String else { return nil }
            let content = (message["content"] as? String)
                ?? (message["text"] as? String)
                ?? (message["context"] as? String)
                ?? ""
            let toolName = (message["tool_name"] as? String) ?? (message["name"] as? String)
            let reasoning = (message["reasoning"] as? String)
                ?? (message["reasoning_content"] as? String)
                ?? (message["reasoning_details"] as? String)
                ?? ""
            return TranscriptState.RestoredMessage(
                role: role,
                content: content,
                toolName: toolName,
                reasoningText: reasoning
            )
        }
    }

    // MARK: Sending

    private func sendDraft() {
        guard !isComposerActionPending else { return }
        let action = M7ComposerPolicy.route(
            draft: draft,
            turnActive: turnInFlight,
            hasAttachments: !stagedAttachments.isEmpty || !stagedHostReferences.isEmpty
        )
        composerNotice = nil

        switch action {
        case .openModelPicker:
            draft = ""
            clearSlashCompletion()
            openModelPicker()

        case .setReasoning(let effort):
            draft = ""
            clearSlashCompletion()
            applyReasoning(effort)

        case .steer(let text):
            steerActiveTurn(text)

        case .submit(let text):
            submitPrompt(text)

        case .reject(let rejection):
            switch rejection {
            case .blankPrompt:
                break
            case .blankSteer:
                composerError = "Enter guidance after /steer."
            case .noActiveTurnToSteer:
                composerError = "There is no active turn to steer."
            case .attachmentsUnavailableWhileSteering:
                composerError = "Attachments are unavailable while steering an active turn."
            }
        }
    }

    private func steerActiveTurn(_ text: String) {
        guard steerSupported, let connection, let runtimeSessionID else {
            composerError = "Not connected — reopen this session to steer."
            return
        }
        let originalDraft = draft
        draft = ""
        clearSlashCompletion()
        composerError = nil
        isComposerActionPending = true
        Task {
            do {
                let result = try await connection.steerSession(runtimeSessionID: runtimeSessionID, text: text)
                await MainActor.run {
                    isComposerActionPending = false
                    switch result.status {
                    case .queued:
                        composerNotice = "Guidance queued for the active turn."
                    case .rejected:
                        composerError = "Could not steer active turn."
                        if draft.isEmpty { draft = originalDraft }
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    steerSupported = false
                    isComposerActionPending = false
                    composerError = "Active-turn steering is not supported by this server."
                    if draft.isEmpty { draft = originalDraft }
                }
            } catch {
                await MainActor.run {
                    isComposerActionPending = false
                    composerError = "Could not steer active turn."
                    if draft.isEmpty { draft = originalDraft }
                }
            }
        }
    }

    private func submitPrompt(_ trimmed: String) {
        guard let connection, let runtimeSessionID else {
            composerError = "Not connected — reopen this session to chat."
            return
        }

        let attachments = stagedAttachments
        let bytes = stagedBytes
        let hostReferences = stagedHostReferences
        draft = ""
        clearSlashCompletion()
        composerError = nil
        isSending = true
        isComposerActionPending = true
        followBottom = true
        Task {
            var submissionAccepted = false
            do {
                // Attach-on-send, Android parity: images ride the session's
                // queued list (image.attach_bytes); files return @file: refs.
                var fileRefs: [String] = []
                var imageNames: [String] = []
                var cumulative: Int64 = 0
                for attachment in attachments {
                    guard let data = bytes[attachment.id] else { continue }
                    cumulative += Int64(data.count)
                    try AttachmentPolicy.validateStagedBytes(
                        displayName: attachment.displayName,
                        kind: attachment.kind,
                        actualBytes: Int64(data.count),
                        cumulativeBytes: cumulative
                    )
                    let base64 = data.base64EncodedString()
                    switch attachment.kind {
                    case .image:
                        try await connection.attachImageBytes(
                            runtimeSessionID: runtimeSessionID,
                            filename: attachment.displayName,
                            base64Content: base64
                        )
                        imageNames.append(attachment.displayName)
                    case .file:
                        let ref = try await connection.attachFile(
                            runtimeSessionID: runtimeSessionID,
                            filename: attachment.displayName,
                            mimeType: attachment.mimeType ?? "application/octet-stream",
                            base64Content: base64
                        )
                        fileRefs.append(ref)
                    }
                }
                let prompt = AttachmentPolicy.composePromptText(
                    typedText: trimmed,
                    fileRefs: fileRefs + hostReferences.map(\.text),
                    attachedNames: imageNames
                )
                guard !prompt.isEmpty else {
                    await MainActor.run {
                        isSending = false
                        isComposerActionPending = false
                    }
                    return
                }
                await MainActor.run {
                    transcript.appendUserMessage(prompt)
                    stagedAttachments = []
                    stagedBytes = [:]
                }
                _ = try await connection.submitPrompt(runtimeSessionID: runtimeSessionID, text: prompt)
                // From this point on the server owns the turn. Do not restore
                // the user's draft if a later cleanup/lifecycle operation
                // fails or the turn is interrupted.
                submissionAccepted = true
                await MainActor.run {
                    stagedHostReferences.removeAll { staged in
                        hostReferences.contains(where: { $0.id == staged.id })
                    }
                    isComposerActionPending = false
                }
            } catch {
                await MainActor.run {
                    composerError = "Send failed — check the connection and try again."
                    isSending = false
                    isComposerActionPending = false
                    // Restore the typed text so nothing is lost; staged
                    // attachments remain staged for retry.
                    if draft.isEmpty,
                       M7ComposerPolicy.shouldRestoreDraftAfterSubmissionFailure(
                           submissionAccepted: submissionAccepted
                       ) {
                        draft = trimmed
                    }
                }
            }
        }
    }

    private func applyIncomingShare() {
        guard !incomingShareApplied, let incomingShare else { return }
        incomingShareApplied = true
        for attachment in incomingShare.attachments {
            stageAttachment(
                filename: attachment.filename,
                mimeType: attachment.mimeType,
                data: attachment.data
            )
        }
    }

    /// Policy-gated staging of a picked attachment (M6.3). Bytes stay in
    /// memory only; rejection reasons surface verbatim in the composer.
    private func stageAttachment(filename: String, mimeType: String?, data: Data) {
        guard !turnInFlight else {
            composerError = "Attachments are unavailable while steering an active turn."
            return
        }
        let sanitized = AttachmentPolicy.sanitizeDisplayName(filename)
        let candidate = StagedAttachment(
            id: UUID().uuidString,
            displayName: sanitized,
            mimeType: mimeType,
            sizeBytes: Int64(data.count)
        )
        // Duplicate rejection by content identity: same name + size.
        if stagedAttachments.contains(where: {
            $0.displayName == sanitized && $0.sizeBytes == candidate.sizeBytes
        }) {
            composerError = "\(sanitized) is already attached"
            return
        }
        switch AttachmentPolicy.checkAdd(existing: stagedAttachments, candidate: candidate) {
        case .accepted:
            stagedAttachments.append(candidate)
            stagedBytes[candidate.id] = data
            composerError = nil
        case .rejected(let reason):
            composerError = reason
        }
    }

    /// Stages only a reference produced from a server-returned HostFileEntry.
    /// Selection never reads bytes, uploads content, or sends a prompt.
    private func stageHostReference(_ entry: HostFileEntry) {
        guard !turnInFlight else {
            composerError = "Host references are unavailable while steering an active turn."
            return
        }
        do {
            let reference = try StagedHostReference(entry: entry)
            guard !stagedHostReferences.contains(where: { $0.id == reference.id }) else {
                composerError = "\(entry.name) is already referenced"
                return
            }
            stagedHostReferences.append(reference)
            composerError = nil
        } catch {
            composerError = "This server path cannot be referenced safely."
        }
    }

    private func interruptTurn() {
        guard !isStopping, let connection, let runtimeSessionID else { return }
        isStopping = true
        composerError = nil
        Task {
            do {
                let response = try await connection.interruptSession(runtimeSessionID: runtimeSessionID)
                if response.status != .ok && response.status != .interrupted {
                    await MainActor.run {
                        isStopping = false
                        composerError = "Could not stop the active response."
                    }
                }
            } catch {
                await MainActor.run {
                    isStopping = false
                    composerError = "Could not stop the active response."
                }
            }
        }
    }

    // MARK: Event handling

    @MainActor
    private func handleEvent(_ event: ChatEvent) {
        // Pure transcript mutation lives in the reducer.
        transcript.apply(event)

        // Best-effort live surfaces: notification delivery + Live Activity.
        // The notification coordinator dedupes and suppresses when this session
        // is the visible/foreground one; the run-activity coordinator drives
        // the Lock Screen / Dynamic Island. It is safe to feed every event.
        //
        // Re-key the event on the DURABLE session id so the notification
        // dedupe watermark, the background reconcile path, and the Live
        // Activity share state, and deep-link targets are sessions the app
        // can open.
        let notifyID = notificationSessionID
        if let notifyID {
            let notifyEvent = event.withSessionID(notifyID)
            let notifySessionTitle = titleText
            Task { await appModel.deliverLiveSurfaces(event: notifyEvent, sessionTitle: notifySessionTitle) }
        }

        // UI-only reactions preserved verbatim from the pre-extraction
        // handler: sending flag toggles, composer banner, sheet presentation,
        // connection-note clearing, and title adoption display.
        switch event {
        case .messageStart:
            isSending = true

        case .messageComplete:
            isSending = false
            isStopping = false
            Task { await cacheCurrentTranscript() }
            loadContext()

        case .error(_, let message):
            composerError = message
            isSending = false
            isStopping = false

        case .toolStart, .toolComplete:
            Task { await loadProcessRows() }

        case .approvalRequest, .clarifyRequest:
            pendingRequest = transcript.pendingRequest.map { request in
                switch request {
                case .approval(let approvalEvent): return .approval(approvalEvent)
                case .clarify(let clarifyEvent): return .clarify(clarifyEvent)
                }
            }

        case .approvalExpire, .clarifyExpire:
            if pendingRequest != nil { pendingRequest = nil }

        case .sessionTitle:
            if let adopted = transcript.adoptedTitle {
                titleText = adopted
            }

        case .sessionInfo(_, _, let model, let provider, let reasoningEffort, let fastMode, let title, _):
            if let model, let provider {
                currentModelSelection = ModelSelection(provider: provider, model: model)
            }
            if let reasoningEffort { currentReasoningEffort = reasoningEffort }
            if let fastMode { currentFastMode = fastMode }
            if let title, !title.isEmpty { titleText = title }

        case .statusUpdate:
            connectionNote = nil

        case .unsupportedBlockingRequest(_, let kind, let requestID, let prompt):
            switch kind {
            case .secret, .sudo:
                pendingSecure = SecureRequest(kind: kind, requestID: requestID, prompt: prompt)
            case .terminalRead, .previewRead, .windowRead:
                // Mercury owns none of Desktop's terminal/preview/window
                // surfaces. The released bridge contract defines an empty
                // response as "surface unavailable" (Android parity).
                Task { await answerSecure(kind: kind, requestID: requestID, value: "") }
            }

        case .unsupportedBlockingExpire(_, _, let requestID):
            if pendingSecure?.requestID == requestID { pendingSecure = nil }

        default:
            break
        }
    }

    // MARK: Sheet answers

    private func answerApproval(_ choice: String) async {
        guard let connection, let runtimeSessionID else { return }
        defer { pendingRequest = nil }
        do {
            _ = try await connection.respondToApproval(
                runtimeSessionID: runtimeSessionID,
                choice: choice
            )
        } catch {
            composerError = "Could not answer approval."
        }
    }

    private func answerClarify(_ answer: String) async {
        guard let connection, let requestID else { return }
        defer { pendingRequest = nil }
        do {
            _ = try await connection.respondToClarification(requestID: requestID, answer: answer)
        } catch {
            composerError = "Could not send clarification."
        }
    }

    /// Responds to a secure blocking prompt (secret/sudo) or auto-answers an
    /// unavailable read surface with the official empty value. The value is
    /// never logged or stored.
    private func answerSecure(kind: UnsupportedBlockingKind, requestID: String, value: String) async {
        guard let connection else { return }
        do {
            _ = try await connection.respondToBlockingPrompt(
                kind: kind,
                requestID: requestID,
                value: value
            )
        } catch {
            // Never include the value or kind detail in surfaced errors.
            composerError = "Could not send secure input."
        }
    }

    /// Managed MEDIA:/markdown image artifacts in a completed assistant
    /// message, rendered as authenticated inline images (M6.4). Only
    /// server-managed absolute paths render; remote URLs stay links.
    @ViewBuilder
    private func managedImages(in text: String) -> some View {
        let artifacts = MediaDirectiveExtractor.extract(text)
            .filter { $0.origin == .managedPath && $0.type == .image }
        ForEach(artifacts, id: \.stableIdentity) { artifact in
            RemoteManagedImage(path: artifact.source) { path in
                try await loadManagedImage(path: path)
            }
        }
    }

    /// Authenticated managed-image fetch: GET /api/files/download?path=…
    /// with the bearer token; image/* content type required; 10 MiB cap
    /// (Android downloadManagedImage parity).
    private func loadManagedImage(path: String) async throws -> Data {
        guard let origin = appModel.serverOrigin else {
            throw URLError(.userAuthenticationRequired)
        }
        let client = makeHTTPClient(origin: origin)
        let (data, response) = try await client.get(
            path: "/api/files/download",
            queryItems: [URLQueryItem(name: "path", value: path)]
        )
        guard (200..<300).contains(response.statusCode),
              (response.value(forHTTPHeaderField: "Content-Type") ?? "")
                  .lowercased().hasPrefix("image/"),
              data.count <= 10 * 1024 * 1024 else {
            throw URLError(.cannotDecodeContentData)
        }
        return data
    }

    /// The request id of the currently presented sheet, extracted from the event.
    private var requestID: String? {
        guard let pendingRequest else { return nil }
        switch pendingRequest {
        case .approval(let event):
            if case .approvalRequest(_, let id, _, _, _) = event { return id }
        case .clarify(let event):
            if case .clarifyRequest(_, let id, _, _, _) = event { return id }
        }
        return nil
    }

    // MARK: Helpers

    private func makeHTTPClient(origin: String) -> HermesHTTPClient {
        HermesHTTPClient.makeAuthenticated(origin: origin)
    }

    private func configureNativeVoice() async {
        if dictation == nil {
            let draftBinding = Binding(get: { draft }, set: { draft = $0 })
            dictation = ComposerDictationCoordinator(
                getDraft: { draftBinding.wrappedValue },
                setDraft: { draftBinding.wrappedValue = $0 }
            )
        }
        guard showMessagePlaybackControls else {
            readAloud?.stop()
            readAloud = nil
            return
        }
        if readAloud == nil,
           let origin = appModel.serverOrigin,
           let originURL = URL(string: origin) {
            let capabilityClient = HermesHTTPClient.makeAuthenticated(origin: origin)
            guard let (_, response) = try? await capabilityClient.get(
                path: "/api/audio/elevenlabs/voices",
                queryItems: [URLQueryItem(name: "profile", value: appModel.activeProfile)]
            ), (200..<300).contains(response.statusCode) else { return }
            let profile = appModel.activeProfile
            readAloud = ReadAloudController(synthesize: { text in
                let currentClient = HermesHTTPClient.makeAuthenticated(origin: origin)
                let (_, capabilityResponse) = try await currentClient.get(
                    path: "/api/audio/elevenlabs/voices",
                    queryItems: [URLQueryItem(name: "profile", value: profile)]
                )
                guard (200..<300).contains(capabilityResponse.statusCode) else {
                    throw SpeechSynthesisError.classify(statusCode: capabilityResponse.statusCode)
                }
                guard let currentToken = currentClient.bearerToken, !currentToken.isEmpty else {
                    throw SpeechSynthesisError.authenticationMissing
                }
                return try await RESTSpeechSynthesizer(
                    origin: originURL,
                    accessToken: currentToken,
                    profile: profile
                ).synthesize(text: text)
            })
        }
    }

    private func storedAccessToken(origin: String) -> String? {
        guard let pair = KeychainCredentialStore().tokens(for: origin),
              let token = String(data: pair.accessToken, encoding: .utf8),
              !token.isEmpty else { return nil }
        return token
    }
}

extension ApprovalSheet.Request: Identifiable {
    var id: String {
        switch self {
        case .approval(let event):
            guard case .approvalRequest(_, let requestID, let command, let description, let choices) = event else {
                return "approval:unknown"
            }
            return "approval:\(requestID ?? [command ?? "", description ?? "", choices.joined(separator: "\u{1F}")].joined(separator: "\u{1E}"))"
        case .clarify(let event):
            guard case .clarifyRequest(_, let requestID, _, _, _) = event else {
                return "clarify:unknown"
            }
            return "clarify:\(requestID)"
        }
    }
}
