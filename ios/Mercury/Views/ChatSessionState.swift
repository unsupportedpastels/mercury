import SwiftUI

extension ChatView {
    /// Secret/sudo prompt awaiting user input. terminalRead/previewRead/
    /// windowRead never surface UI — they are auto-answered empty (Android
    /// parity: the released bridge contract defines an empty response as
    /// "surface unavailable").
    struct SecureRequest: Identifiable {
        let kind: UnsupportedBlockingKind
        let requestID: String
        let prompt: String?
        var id: String { requestID }
    }

    struct PendingModelConfirmation: Identifiable {
        let selection: ModelSelection
        let message: String
        var id: String { selection.provider + "\u{0}" + selection.model }
    }

    struct BranchDestination: Hashable {
        let durableID: String
        let title: String
    }

    enum ConnectionState {
        case connecting
        case live
        case reconnecting(attempt: Int)
        case offline
    }
}

/// Mutable per-screen state for one `ChatView`.
///
/// Every value here used to be an individual `@State` on the view. The view
/// owns exactly one instance (`@State private var state`), so SwiftUI keeps
/// the object alive across body re-evaluations exactly as it kept the
/// individual `@State` storages alive; property names, initial values, and
/// the derived computations are unchanged.
@Observable
@MainActor
final class ChatSessionState {
    // MARK: Transcript state
    //
    // Row modeling and event mutation live in TranscriptState (pure value
    // type, MercuryKit/Chat/TranscriptReducer.swift). The view only renders
    // from it and keeps the UI-only concerns: scroll/follow-bottom intent,
    // reconnect policy, and sheet presentation triggers.

    var transcript: TranscriptState
    var loadError: String?
    var draft: String
    var composerError: String?
    var dictation: ComposerDictationCoordinator?
    var readAloud: ReadAloudController?
    var incomingShareApplied = false
    var processRows: [ActivityProcess] = []
    var composerNotice: String?
    var isSending = false
    var isStopping = false
    var isComposerActionPending = false
    var userMessageScrollGeneration = 0
    var connectionNote: String?

    // MARK: M7 session controls

    var showModelPicker = false
    var modelOptions: ModelOptions?
    var currentModelSelection: ModelSelection?
    var currentReasoningEffort: String?
    var currentFastMode: Bool?
    var modelPickerLoading = false
    var modelPickerApplying = false
    var modelPickerError: String?
    var modelFeatureSupported = true
    var steerSupported = true
    var pendingModelConfirmation: ChatView.PendingModelConfirmation?

    var showContextSheet = false
    var sessionUsage: SessionUsage?
    var contextBreakdown: SessionContextBreakdown?
    var contextLoading = false
    var contextBusy = false
    var contextError: String?
    var contextStatus: String?
    var usageSupported = true
    var breakdownSupported = true
    var compressSupported = true
    var undoSupported = true
    var branchSupported = true
    var contextGeneration = 0
    var branchDestination: ChatView.BranchDestination?

    var slashItems: [SlashCompletionItem] = []
    var slashReplaceFrom = 0
    var slashGeneration = 0
    var slashCompletionTask: Task<Void, Never>?
    var slashCompletionSupported = true

    // MARK: Attachments (M6.3)
    //
    // Staged metadata rides StagedAttachment (policy-admitted); the raw bytes
    // live only in this transient dictionary until send, never persisted.

    var stagedAttachments: [StagedAttachment] = []
    var stagedBytes: [String: Data] = [:]
    var stagedHostReferences: [StagedHostReference] = []

    // MARK: Secure blocking input (M5.4)

    var pendingSecure: ChatView.SecureRequest?

    // MARK: Live connection

    var connection: ChatConnection?
    var establishing = false
    var connectionOwnership = ChatConnectionOwnership()
    var runtimeSessionID: String?
    /// Durable session id adopted from `session.create`'s stored_session_id
    /// once the gateway persists the new runtime session.
    var durableID: String?
    var eventTask: Task<Void, Never>?
    var pendingRequest: ApprovalSheet.Request?
    var didOpen = false

    // MARK: Reconnect policy (Android recoverChat parity)

    var connectionState: ChatView.ConnectionState = .connecting
    /// Scheduled reconnect attempt; cancelled on disappear so a pending
    /// timer never fires after the screen is gone.
    var reconnectTask: Task<Void, Never>?
    var reconnectID = UUID()
    /// Set when close() was deliberate — peer drops trigger recovery, our
    /// own teardown must not.
    var closedByUs = false

    /// Displayed title; seeded from `title`, updated live when a
    /// .sessionTitle event renames our (new) session.
    var titleText: String

    // MARK: Follow-scroll intent

    var followBottom = true
    var initialScrollDone = false
    var loadedTranscriptCount = 0
    var hasMoreHistory = false
    var isLoadingHistory = false
    var historyError: String?

    init(
        sessionID: String,
        title: String,
        isNewSession: Bool,
        incomingShare: IncomingShareDraft?
    ) {
        titleText = title
        draft = incomingShare?.text ?? ""
        composerNotice = incomingShare?.notice
        var initialTranscript = TranscriptState(isNewSession: isNewSession)
        // Mirrors pre-extraction isOurSession(_:): an empty navigation id
        // never matches anything.
        if !sessionID.isEmpty {
            initialTranscript.ownSessionIDs.insert(sessionID)
        }
        transcript = initialTranscript
    }

    // MARK: Derived state

    /// Composer stays disabled while the live connection is down.
    var isConnectionDown: Bool {
        switch connectionState {
        case .reconnecting, .offline: return true
        default: return false
        }
    }

    var connectionStateIsNotLive: Bool {
        if case .live = connectionState { return false }
        return true
    }

    var currentModelCapabilities: ModelCapabilities? {
        modelOptions?.capabilities(for: currentModelSelection ?? modelOptions?.current)
    }

    var composerModelLabel: String? {
        currentModelSelection?.model.split(separator: "/").last.map(String.init)
    }

    var contextControlsSupported: Bool {
        usageSupported || breakdownSupported || compressSupported || undoSupported || branchSupported
    }

    var composerContextPercent: Double? {
        if let percent = sessionUsage?.contextPercent { return percent }
        if let used = sessionUsage?.contextUsedTokens,
           let maximum = sessionUsage?.contextMaxTokens,
           maximum > 0 {
            return Double(used) * 100 / Double(maximum)
        }
        return contextBreakdown?.percent
    }

    var turnInFlight: Bool {
        transcript.hasStreamingAssistant
    }

    /// A running turn is intentionally not busy: its composer steers. Only a
    /// transport outage, a local RPC, or pre-stream prompt submission blocks it.
    var composerIsBusy: Bool {
        isConnectionDown || isComposerActionPending || (isSending && (!turnInFlight || !steerSupported))
    }

    /// The request id of the currently presented sheet, extracted from the event.
    var requestID: String? {
        guard let pendingRequest else { return nil }
        switch pendingRequest {
        case .approval(let event):
            if case .approvalRequest(_, let id, _, _, _) = event { return id }
        case .clarify(let event):
            if case .clarifyRequest(_, let id, _, _, _) = event { return id }
        }
        return nil
    }
}
