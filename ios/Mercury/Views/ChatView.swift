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
        _state = State(initialValue: ChatSessionState(
            sessionID: sessionID,
            title: title,
            isNewSession: isNewSession,
            incomingShare: incomingShare
        ))
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

    @Environment(AppModel.self) var appModel
    @Environment(\.scenePhase) var scenePhase
    @AppStorage(VoiceDisplayPreferences.playbackControlsKey)
    var showMessagePlaybackControls = false

    /// All mutable screen state (transcript, connection, sheets, composer).
    /// See ChatSessionState for the property inventory.
    @State var state: ChatSessionState

    var backgroundTaskScope: String {
        let origin = appModel.activeRelayTarget.map { "relay:\($0.relayOrigin)|\($0.id)" }
            ?? "direct:\(appModel.serverOrigin ?? "unconfigured")"
        return "\(origin)|\(appModel.activeProfile)|\(state.durableID ?? sessionID)"
    }
    var backgroundTasks: BackgroundTasks {
        appModel.backgroundTasksBySession[backgroundTaskScope] ?? BackgroundTasks()
    }
    func markBackgroundTasksUnavailable() {
        var tasks = backgroundTasks
        tasks.markUnavailable()
        if !tasks.rows.isEmpty { appModel.backgroundTasksBySession[backgroundTaskScope] = tasks }
    }

    let lastRowID = "transcript-end"
    let firstRowID = "transcript-start"

    /// The durable id used for notification keying and deep-links. Prefer the
    /// adopted durable id; fall back to the navigation session id (an existing
    /// session opened directly). A brand-new chat with neither yet is nil until
    /// `session.create` returns a stored id.
    var notificationSessionID: String? {
        if let durableID = state.durableID, !durableID.isEmpty { return durableID }
        if !sessionID.isEmpty { return sessionID }
        return nil
    }

    var body: some View {
        @Bindable var state = state
        VStack(spacing: 0) {
            if let connectionNote = state.connectionNote {
                Label(connectionNote, systemImage: "wifi.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .padding(.vertical, 4)
            }
            switch state.connectionState {
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
                Button(action: { retryConnectionNow() }) {
                    Label("Chat offline — tap to retry", systemImage: "wifi.exclamationmark")
                        .font(.footnote)
                        .foregroundStyle(Color.statusAlert)
                }
                .padding(.vertical, 4)
            case .live:
                EmptyView()
            }
            if let loadError = state.loadError {
                Label(loadError, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .padding(.vertical, 4)
            }

            transcriptList

            Divider().overlay(Color.separatorSubtle)

            SlashCompletionOverlay(items: state.slashItems) { item in
                state.draft = applySlashCompletion(state.draft, item: item, replaceFrom: state.slashReplaceFrom)
                state.slashItems = []
            }

            if let dictation = state.dictation, case .failed(let failure) = dictation.state {
                DictationFailureLabel(failure: failure)
                    .padding(.horizontal)
            }

            BackgroundTaskStrip(tasks: backgroundTasks)
                .padding(.horizontal)
            ComposerBar(
                draft: $state.draft,
                errorMessage: Binding(get: { state.composerError }, set: { state.composerError = $0 }),
                noticeMessage: state.composerNotice,
                isSending: state.composerIsBusy,
                onSend: {
                    sendDraft()
                },
                showStop: M7ComposerPolicy.shouldShowStopButton(
                    isSending: state.isSending,
                    turnActive: state.turnInFlight,
                    draft: state.draft
                ),
                isStopping: state.isStopping,
                onStop: interruptTurn,
                isSteering: state.turnInFlight && state.steerSupported,
                attachmentsEnabled: !state.turnInFlight,
                attachments: state.stagedAttachments,
                onAttachmentPicked: { filename, mimeType, data in
                    stageAttachment(filename: filename, mimeType: mimeType, data: data)
                },
                onRemoveAttachment: { id in
                    state.stagedAttachments.removeAll { $0.id == id }
                    state.stagedBytes[id] = nil
                },
                onAttachmentError: { message in
                    state.composerError = message
                },
                hostReferences: state.stagedHostReferences,
                onHostReferencePicked: stageHostReference,
                onRemoveHostReference: { id in
                    state.stagedHostReferences.removeAll { $0.id == id }
                },
                dictation: state.dictation,
                modelLabel: state.composerModelLabel,
                reasoningEffort: state.currentReasoningEffort,
                reasoningSupported: state.currentModelCapabilities?.reasoning == true
                    || state.currentReasoningEffort != nil,
                fastSupported: state.currentModelCapabilities?.fast == true,
                fastEnabled: state.currentFastMode == true,
                contextPercent: state.composerContextPercent,
                metadataControlsEnabled: !state.connectionStateIsNotLive && state.runtimeSessionID != nil,
                onOpenModelPicker: state.modelFeatureSupported ? openModelPicker : nil,
                onReasoningSelected: applyReasoning,
                onFastSelected: applyFast,
                onOpenContext: state.contextControlsSupported ? openContextSheet : nil
            )
        }
        .navigationTitle(state.titleText.isEmpty ? "Session" : state.titleText)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.canvas, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            if state.contextControlsSupported {
                ToolbarItem(placement: .topBarTrailing) {
                    ContextRingButton(
                        percent: state.composerContextPercent,
                        artifactCount: 0,
                        action: openContextSheet
                    )
                }
            }
        }
        .task {
            // Visibility can change while SwiftUI retains this view and restarts
            // its task. Restore it even when the connection is already owned.
            appModel.setVisibleSession(notificationSessionID)
            // SwiftUI may recreate the task while the view is still mounted.
            // Android's ViewModel refuses a second open for an already-owned
            // session; make the same admission decision before any await.
            guard !state.didOpen else { return }
            state.didOpen = true
            applyIncomingShare()
            if let notifyID = notificationSessionID {
                // Engaged scope: background reconciliation may notify about a
                // session only after the app has opened it (Android parity).
                appModel.markSessionEngaged(notifyID)
                Task { await appModel.clearNotifications(sessionID: notifyID) }
            }
            await configureNativeVoice()
            await open()
            state.didOpen = true
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await catchUpAfterForeground() }
        }
        .onChange(of: state.draft) {
            scheduleSlashCompletion(for: state.draft)
        }
        .onDisappear {
            // A transient SwiftUI disappearance must not interrupt an active
            // server-side turn. Android keeps its live controller in the
            // ViewModel; preserve the iOS connection while a turn is active so
            // the next foreground/open can resume the same runtime instead of
            // creating a second visible attempt.
            if ChatDisappearancePolicy.action(
                turnInFlight: state.turnInFlight,
                isSending: state.isSending
            ) == .preserveConnectionAndObserver {
                appModel.setVisibleSession(nil)
                return
            }
            // Deliberate teardown for an idle session: no reconnect may fire
            // after dismissal.
            state.closedByUs = true
            appModel.setVisibleSession(nil)
            state.reconnectTask?.cancel()
            state.reconnectTask = nil
            state.slashCompletionTask?.cancel()
            state.slashCompletionTask = nil
            state.dictation?.cancel()
            state.readAloud?.stop()
            state.eventTask?.cancel()
            state.eventTask = nil
            let closingConnection = state.connection
            markBackgroundTasksUnavailable()
            state.connectionOwnership.invalidate()
            state.connection = nil
            Task { if let closingConnection { await RelayConnectionPool.release(closingConnection) } }
        }
        .sheet(isPresented: $state.showModelPicker) {
            ModelPickerSheet(
                options: state.modelOptions,
                selection: state.currentModelSelection,
                isLoading: state.modelPickerLoading,
                isApplying: state.modelPickerApplying,
                errorMessage: state.modelPickerError,
                onRetry: loadModelOptions,
                onSelectModel: { applyModel($0, confirmed: false) }
            )
        }
        .sheet(isPresented: $state.showContextSheet) {
            ContextSheet(
                usage: state.sessionUsage,
                breakdown: state.contextBreakdown,
                isLoading: state.contextLoading,
                isBusy: state.contextBusy,
                isIdle: !state.turnInFlight,
                statusMessage: state.contextStatus,
                errorMessage: state.contextError,
                compressSupported: state.compressSupported,
                undoSupported: state.undoSupported,
                branchSupported: state.branchSupported,
                onRefresh: loadContext,
                onCompress: compressContext,
                onUndo: undoLastTurn,
                onBranch: branchSession
            )
        }
        .alert(item: $state.pendingModelConfirmation) { pending in
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
            get: { state.branchDestination != nil },
            set: { if !$0 { state.branchDestination = nil } }
        )) {
            if let destination = state.branchDestination {
                ChatView(sessionID: destination.durableID, title: destination.title)
            }
        }
        .sheet(item: $state.pendingRequest) { request in
            ApprovalSheet(
                request: request,
                isBusy: state.isSending,
                onApprovalChoice: { choice in
                    await answerApproval(choice)
                },
                onClarifyAnswer: { answer in
                    await answerClarify(answer)
                },
                onDismiss: { state.pendingRequest = nil },
                clarifyQuestion: state.currentClarifyQuestion,
                clarifyPosition: state.currentClarifyQuestion.map { current in
                    (index: (state.clarifyQuestions.firstIndex(of: current) ?? 0) + 1, total: state.clarifyQuestions.count)
                }
            )
        }
        .sheet(item: $state.pendingSecure) { secure in
            SecureInputSheet(
                kind: secure.kind,
                prompt: secure.prompt,
                onSubmit: { value in
                    let requestID = secure.requestID
                    let kind = secure.kind
                    state.pendingSecure = nil
                    Task { await answerSecure(kind: kind, requestID: requestID, value: value) }
                },
                onCancel: { state.pendingSecure = nil }
            )
        }
        .amoledScreen()
    }

    // MARK: Helpers

    func makeHTTPClient(origin: String) -> HermesHTTPClient {
        HermesHTTPClient.makeAuthenticated(origin: origin)
    }

    private func configureNativeVoice() async {
        if state.dictation == nil {
            let draftBinding = Binding(get: { state.draft }, set: { state.draft = $0 })
            state.dictation = ComposerDictationCoordinator(
                getDraft: { draftBinding.wrappedValue },
                setDraft: { draftBinding.wrappedValue = $0 }
            )
        }
        guard showMessagePlaybackControls else {
            state.readAloud?.stop()
            state.readAloud = nil
            return
        }
        if state.readAloud == nil,
           let origin = appModel.serverOrigin,
           let originURL = URL(string: origin) {
            let capabilityClient = HermesHTTPClient.makeAuthenticated(origin: origin)
            guard let (_, response) = try? await capabilityClient.get(
                path: "/api/audio/elevenlabs/voices",
                queryItems: [URLQueryItem(name: "profile", value: appModel.activeProfile)]
            ), (200..<300).contains(response.statusCode) else { return }
            let profile = appModel.activeProfile
            state.readAloud = ReadAloudController(synthesize: { text in
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

    func storedAccessToken(origin: String) -> String? {
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
            guard case .clarifyRequest(_, let requestID, _, _, _, _) = event else {
                return "clarify:unknown"
            }
            return "clarify:\(requestID)"
        }
    }
}
