import Foundation
import MercuryCore

// MARK: - Chat connection state machine
//
// Port of Android's `HermesChatConnection` (the core of
// gateway/HermesChatGateway.kt). Owns the read loop, JSON-RPC request/response
// correlation, and typed-event decoding over a `ChatSocketing`.
//
// Defensive decisions ported deliberately (do not "simplify"):
// - Frames with a non-"2.0" jsonrpc field are DROPPED silently.
// - A malformed JSON frame fails the whole connection: it may be the only
//   response to an outstanding RPC, so pending callers must not hang.
// - Message TEXT fields are bounded WITHOUT trimming: streaming tokenizers
//   attach the inter-word space to the FRONT of the next token ("HE",
//   " WORLD"), so trimming deltas jams words together.
// - Approval choices must be validated against the advertised set before any
//   approval.respond is sent.
// - Bounded frame sizes on both send and receive.

final class ChatConnection: @unchecked Sendable {

    // MARK: Configuration

    /// Capability discovery is auxiliary to chat admission. Keep its deadline
    /// explicit so a server that accepts the request but never replies cannot
    /// hold the connection in its establishing phase indefinitely.
    static let defaultModelOptionsTimeoutNanoseconds: UInt64 = 5_000_000_000

    private let socket: any ChatSocketing
    private let maxFrameBytes: Int
    private let modelOptionsTimeoutNanoseconds: UInt64

    var relaySocket: RelayChatSocket? { socket as? RelayChatSocket }
    var isClosed: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return closed
    }

    /// Test/diagnostic seam for proving timed-out RPCs do not remain retained.
    var pendingRequestCount: Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        return pendingRequests.count
    }

    // MARK: Lifecycle state

    private let stateLock = NSLock()
    private var closed = false
    private var nextRequestID: Int64 = 1
    private let requestNamespace: String?
    private var readerStarted = false

    /// id → response stream continuation for in-flight RPCs. Also mirrors method
    /// names so a -32601 failure can name the unsupported method.
    private typealias ResponseStream = AsyncThrowingStream<[String: Any], Error>
    private var pendingRequests: [String: ResponseStream.Continuation] = [:]
    private var pendingRequestMethods: [String: String] = [:]
    private var pendingBindingParams: [String: [String: Any]] = [:]
    private var pendingRequestTimeouts: [String: Task<Void, Never>] = [:]

    // MARK: Event stream

    /// DROP-OLDEST ring buffer semantics: a slow consumer sheds oldest events,
    /// never tears down the connection. Terminal events arrive last, so they
    /// are least likely to be dropped; resume reconciliation restores state.
    private let eventBuffer = EventRingBuffer(capacity: maxEventBuffer)
    private var continuations: [UUID: AsyncStream<ChatEvent>.Continuation] = [:]
    private var streamFinished = false

    // MARK: Pending approvals (Android interactionLock parity)

    private var pendingApprovals: [String: [PendingApproval]] = [:]

    private struct PendingApproval {
        var requestID: String?
        var command: String?
        var description: String?
        var choices: [String]
    }

    init(
        socket: any ChatSocketing,
        maxFrameBytes configured: Int? = nil,
        modelOptionsTimeoutNanoseconds: UInt64 = ChatConnection.defaultModelOptionsTimeoutNanoseconds
    ) throws {
        let resolvedLimit = try validatedMaxFrameBytes(configured ?? 36 * 1024 * 1024)
        self.socket = socket
        self.requestNamespace = socket is RelayChatSocket ? UUID().uuidString : nil
        self.maxFrameBytes = resolvedLimit
        self.modelOptionsTimeoutNanoseconds = max(1, modelOptionsTimeoutNanoseconds)
    }

    deinit {
        finishStreams()
    }

    // MARK: - Event consumption

    /// Starts the read loop. Returns a multiplexed event stream; multiple
    /// consumers each receive every event. Call exactly once per connection.
    func start(replayBuffered: Bool = true) -> AsyncStream<ChatEvent> {
        let id = UUID()
        let stream = AsyncStream<ChatEvent>(bufferingPolicy: .unbounded) { continuation in
            let lock = self.stateLock
            lock.lock()
            if self.streamFinished {
                lock.unlock()
                continuation.finish()
                return
            }
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.stateLock.lock()
                self.continuations.removeValue(forKey: id)
                self.stateLock.unlock()
            }
            self.continuations[id] = continuation
            // Replay anything already buffered so early events are not lost.
            for event in replayBuffered ? self.eventBuffer.snapshot() : [] {
                if self.relaySocket != nil {
                    guard case .backgroundTask(let session, var evidence) = event else { continue }
                    // A newly attached presentation is replaying local evidence,
                    // not observing fresh worker activity at this instant.
                    evidence.historical = true
                    continuation.yield(.backgroundTask(sessionID: session, evidence: evidence))
                } else {
                    continuation.yield(event)
                }
            }
            lock.unlock()
        }
        startReading()
        return stream
    }

    func startReading() {
        stateLock.lock()
        let shouldStart = !readerStarted && !closed
        readerStarted = true
        stateLock.unlock()
        if shouldStart { Task { await readLoop() } }
    }

    // MARK: - Public RPC surface (Android-parity subset)

    func resume(durableSessionID: String, profile: String?, automaticRecovery: Bool = false) async throws -> ResumedChatSession {
        if automaticRecovery {
            guard let relaySocket else {
                // Direct Hermes has no authoritative same-client ownership
                // proof. An implicit resume would therefore be an unsafe
                // takeover; the caller must use the explicit Retry path.
                throw ChatError.transport("Explicit retry required to resume this session")
            }
            guard await relaySocket.canAutomaticallyResume(durable: durableSessionID, profile: profile) else {
                throw ChatError.transport("Retained session unavailable")
            }
        }
        var params: [String: Any] = [
            "session_id": durableSessionID,
            "close_on_disconnect": false,
        ]
        if let profile { params["profile"] = profile }
        return try parseResumeResult(
            try await request("session.resume", params),
            requestedDurableSessionID: durableSessionID
        )
    }

    func createSession(profile: String?, workspacePath: String? = nil) async throws -> ResumedChatSession {
        var params: [String: Any] = ["close_on_disconnect": false]
        if let profile { params["profile"] = profile }
        // Android parity (HermesChatGateway.createSession): a validated
        // canonical workspace path rides as `cwd` so the runtime starts in the
        // project's folder; anything invalid is silently omitted and the
        // server applies its default working directory.
        if let cwd = validCanonicalHostFilePath(workspacePath) { params["cwd"] = cwd }
        let result = try await request("session.create", params)
        guard let runtimeSessionID = stringField("session_id", in: result),
              !runtimeSessionID.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw ChatError.protocolError("Create response was incomplete")
        }
        let stored = boundedOptionalField("stored_session_id", in: result, maxChars: maxEventNameChars)
            .flatMap { $0.isEmpty ? nil : $0 }
        return ResumedChatSession(
            runtimeSessionID: runtimeSessionID,
            durableSessionID: stored,
            resumed: false,
            messages: [],
            running: false,
            inflight: nil,
            model: nil,
            provider: nil,
            reasoningEffort: nil,
            fastMode: nil
        )
    }

    func submitPrompt(runtimeSessionID: String, text: String) async throws -> PromptSubmission {
        let boundedText = try boundedRPCInput(text, maxChars: maxMessageTextChars, label: "prompt text")
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let result = try await request("prompt.submit", [
            "session_id": sessionKey,
            "text": boundedText,
        ])
        guard let status = stringField("status", in: result) else {
            throw ChatError.protocolError("Prompt response was incomplete")
        }
        return PromptSubmission(status: status)
    }

    func respondToClarification(requestID: String, answer: String, questionID: String? = nil) async throws -> ChatResponse {
        let boundedRequestID = try boundedRPCInput(requestID, maxChars: maxEventIDChars, label: "request ID")
        let boundedAnswer = try boundedRPCInput(answer, maxChars: maxEventTextChars, label: "answer", allowBlank: true)
        var params: [String: Any] = [
            "request_id": boundedRequestID,
            "answer": boundedAnswer,
        ]
        // Batch clarify answers one question at a time by its qid.
        if let questionID {
            params["question_id"] = try boundedRPCInput(questionID, maxChars: maxEventIDChars, label: "question ID")
        }
        let result = try await request("clarify.respond", params)
        return try parseInteractionResponse(result)
    }

    func respondToApproval(
        runtimeSessionID: String,
        choice: String,
        all: Bool = false,
        requestID: String? = nil
    ) async throws -> ChatResponse {
        let boundedChoice = try boundedRPCInput(choice, maxChars: maxEventChoiceChars, label: "approval choice")
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let boundedRequestID = try requestID.map {
            try boundedRPCInput($0, maxChars: maxEventIDChars, label: "request ID")
        }

        // Validate against the advertised choices BEFORE sending (Android
        // interactionLock block). A choice the server never offered is a
        // client bug or stale UI; reject locally.
        stateLock.lock()
        let queue = pendingApprovals[sessionKey]
        let pending: PendingApproval?
        if let boundedRequestID {
            pending = queue?.first { $0.requestID == boundedRequestID } ?? queue?.last
        } else {
            pending = queue?.last
        }
        guard let pending, pending.choices.contains(boundedChoice) else {
            stateLock.unlock()
            throw ChatError.protocolError("Approval choice was not advertised")
        }
        stateLock.unlock()

        var params: [String: Any] = [
            "session_id": sessionKey,
            "choice": boundedChoice,
            "all": all,
        ]
        if let boundedRequestID { params["request_id"] = boundedRequestID }

        let response = try parseInteractionResponse(try await request("approval.respond", params))

        // Queue maintenance + next-approval surfacing, mirroring Android.
        stateLock.lock()
        defer { stateLock.unlock() }
        var nextApproval: ChatEvent?
        if response.status == .ok || response.status == .resolved || response.status == .expired {
            if var remaining = pendingApprovals[sessionKey] {
                if all {
                    remaining.removeAll()
                } else if let boundedRequestID {
                    remaining.removeAll { $0.requestID == boundedRequestID }
                } else if !remaining.isEmpty {
                    remaining.removeLast()
                }
                if remaining.isEmpty {
                    pendingApprovals.removeValue(forKey: sessionKey)
                } else {
                    pendingApprovals[sessionKey] = remaining
                    nextApproval = remaining.last.map { Self.approvalEvent(for: $0, sessionID: sessionKey) }
                }
            }
        } else if let queue, let last = queue.last {
            nextApproval = Self.approvalEvent(for: last, sessionID: sessionKey)
        }
        return ChatResponse(status: response.status, nextApproval: nextApproval)
    }

    /// Answers a blocking secret/sudo/terminal-read prompt (Android
    /// `secret.respond` / `sudo.respond` / `*.read.respond` parity). The value
    /// is bounded with allowBlank:true because the empty string is the
    /// official "surface unavailable" auto-answer. SECURITY: the value is
    /// never logged nor embedded in any thrown error text.
    func respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestID: String,
        value: String
    ) async throws -> ChatResponse {
        let method: String
        let valueKey: String
        switch kind {
        case .secret:
            method = "secret.respond"
            valueKey = "value"
        case .sudo:
            method = "sudo.respond"
            valueKey = "password"
        case .terminalRead:
            method = "terminal.read.respond"
            valueKey = "text"
        case .previewRead:
            method = "preview.read.respond"
            valueKey = "text"
        case .windowRead:
            method = "window.read.respond"
            valueKey = "text"
        }
        let boundedRequestID = try boundedRPCInput(requestID, maxChars: maxEventIDChars, label: "request ID")
        let boundedValue = try boundedRPCInput(
            value,
            maxChars: maxEventTextChars,
            label: "\(method) input",
            allowBlank: true
        )
        return try parseInteractionResponse(
            try await request(method, [
                "request_id": boundedRequestID,
                valueKey: boundedValue,
            ])
        )
    }

    func interruptSession(runtimeSessionID: String) async throws -> ChatResponse {
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        return try parseInteractionResponse(
            try await request("session.interrupt", ["session_id": sessionKey])
        )
    }

    /// Attaches a file to the runtime session (Android `file.attach` parity).
    /// Returns the server's non-blank `ref_text` reference string.
    func attachFile(
        runtimeSessionID: String,
        filename: String,
        mimeType: String,
        base64Content: String
    ) async throws -> String {
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let boundedFilename = try boundedRPCInput(filename, maxChars: maxEventTextChars, label: "attachment filename")
        let boundedMimeType = try boundedRPCInput(mimeType, maxChars: maxEventNameChars, label: "attachment MIME type")
        let boundedContent = try boundedRPCInput(base64Content, maxChars: maxFrameBytes, label: "attachment content")
        let result = try await request("file.attach", [
            "session_id": sessionKey,
            "path": boundedFilename,
            "name": boundedFilename,
            "data_url": "data:" + boundedMimeType + ";base64," + boundedContent,
        ])
        guard let refText = stringField("ref_text", in: result),
              !refText.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw ChatError.protocolError("File attach response was incomplete")
        }
        return refText
    }

    /// Attaches raw image bytes to the runtime session (Android
    /// `image.attach_bytes` parity). The result body carries no fields the
    /// client consumes, so it is ignored.
    func attachImageBytes(
        runtimeSessionID: String,
        filename: String,
        base64Content: String
    ) async throws {
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let boundedFilename = try boundedRPCInput(filename, maxChars: maxEventTextChars, label: "attachment filename")
        let boundedContent = try boundedRPCInput(base64Content, maxChars: maxFrameBytes, label: "attachment content")
        _ = try await request("image.attach_bytes", [
            "session_id": sessionKey,
            "filename": boundedFilename,
            "content_base64": boundedContent,
        ])
    }

    // MARK: M7 model and runtime-session RPCs

    func loadModelOptions(runtimeSessionID: String) async throws -> ModelOptions {
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let result = try await request("model.options", [
            "session_id": sessionKey,
            "explicit_only": true,
            "include_unconfigured": false,
        ], timeoutNanoseconds: modelOptionsTimeoutNanoseconds)
        return parseModelOptions(result)
    }

    func setModel(
        runtimeSessionID: String,
        provider: String,
        model: String,
        confirmExpensiveModel: Bool
    ) async throws -> ModelSwitchResult {
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let boundedProvider = try boundedModelInput(provider, maxChars: maxModelProviderChars, label: "model provider")
        let boundedModel = try boundedModelInput(model, maxChars: maxModelIDChars, label: "model ID")
        let value = "\(boundedModel) --provider \(boundedProvider) --session"
        let result = try await request("config.set", [
            "session_id": sessionKey,
            "key": "model",
            "value": value,
            "confirm_expensive_model": confirmExpensiveModel,
        ])
        try validateConfigResult(result, expectedKey: "model", operation: "model switch")
        let confirmationRequired = try optionalStrictBool("confirm_required", in: result) ?? false
        let deferred = try optionalStrictBool("deferred", in: result) ?? false
        return ModelSwitchResult(
            accepted: !confirmationRequired,
            deferred: deferred,
            confirmationRequired: confirmationRequired,
            confirmationMessage: boundedOptionalField("confirm_message", in: result, maxChars: 1_000)
        )
    }

    func setReasoning(runtimeSessionID: String, effort: String) async throws {
        guard let canonical = ReasoningEffort.canonical(effort) else {
            throw ChatError.protocolError("Reasoning effort is invalid")
        }
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let result = try await request("config.set", [
            "session_id": sessionKey,
            "key": "reasoning",
            "value": canonical,
        ])
        try validateConfigResult(
            result,
            expectedKey: "reasoning",
            operation: "reasoning switch"
        )
    }

    func setFast(runtimeSessionID: String, enabled: Bool) async throws {
        let sessionKey = try boundedRPCInput(
            runtimeSessionID,
            maxChars: maxEventIDChars,
            label: "runtime session ID"
        )
        let result = try await request("config.set", [
            "session_id": sessionKey,
            "key": "fast",
            "value": enabled ? "fast" : "normal",
        ])
        try validateConfigResult(result, expectedKey: "fast", operation: "fast switch")
    }

    func steerSession(runtimeSessionID: String, text: String) async throws -> SessionSteerResult {
        let sessionKey = try boundedRPCInput(runtimeSessionID, maxChars: maxEventIDChars, label: "runtime session ID")
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let boundedText = try boundedRPCInput(trimmed, maxChars: maxEventTextChars, label: "steer text")
        let result = try await request("session.steer", ["session_id": sessionKey, "text": boundedText])
        guard let rawStatus = result["status"] as? String,
              let status = SessionSteerResult.Status(rawValue: rawStatus) else {
            throw ChatError.protocolError("Steer response was incomplete")
        }
        if result["text"] != nil, !(result["text"] is String) {
            throw ChatError.protocolError("Steer response was incomplete")
        }
        return SessionSteerResult(
            status: status,
            text: boundedTextField("text", in: result, maxChars: maxEventTextChars)
        )
    }

    func loadSessionUsage(runtimeSessionID: String) async throws -> SessionUsage {
        let result = try await request("session.usage", sessionParams(runtimeSessionID))
        return parseSessionUsage(result)
    }

    func loadContextBreakdown(runtimeSessionID: String) async throws -> SessionContextBreakdown {
        let result = try await request("session.context_breakdown", sessionParams(runtimeSessionID))
        return parseContextBreakdown(result)
    }

    func compressSession(runtimeSessionID: String, focusTopic: String? = nil) async throws -> SessionCompressResult {
        var params = try sessionParams(runtimeSessionID)
        if let focusTopic {
            let trimmed = focusTopic.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                params["focus_topic"] = String(trimmed.prefix(maxEventTextChars))
            }
        }
        let result = try await request("session.compress", params)
        return parseCompressResult(result)
    }

    func undoSession(runtimeSessionID: String) async throws -> SessionUndoResult {
        let result = try await request("session.undo", sessionParams(runtimeSessionID))
        guard let removed = strictInt64Field("removed", in: result), removed >= 0, removed <= Int64(Int.max) else {
            throw ChatError.protocolError("Undo response was incomplete")
        }
        return SessionUndoResult(removed: Int(removed))
    }

    func branchSession(
        runtimeSessionID: String,
        count: Int? = nil,
        name: String? = nil
    ) async throws -> SessionBranchResult {
        var params = try sessionParams(runtimeSessionID)
        if let count { params["count"] = min(500, max(1, count)) }
        if let name {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { params["name"] = String(trimmed.prefix(maxSessionFieldChars)) }
        }
        return try parseBranchResult(try await request("session.branch", params))
    }

    func completeSlash(text: String) async throws -> SlashCompletionResult {
        let boundedText = try boundedRPCInput(text, maxChars: maxEventTextChars, label: "slash completion text", allowBlank: true)
        let result = try await request("complete.slash", ["text": boundedText])
        return try parseSlashCompletion(result, inputLength: boundedText.utf16.count)
    }

    // MARK: M8 project RPCs

    /// Observer-only inventory of currently live gateway runtimes. This does
    /// not resume, activate, or acquire controller access to any session.
    func loadActiveSessions() async throws -> [ActiveSessionRuntime] {
        let result = try await request("session.active_list", ["current_session_id": ""])
        var seenDurableIDs = Set<String>()
        var sessions: [ActiveSessionRuntime] = []
        for raw in ((result["sessions"] as? [Any]) ?? []).prefix(maxSessionResultRows) {
            guard let row = raw as? [String: Any],
                  let runtimeID = boundedRequiredField("id", in: row, maxChars: maxEventIDChars),
                  let durableID = boundedRequiredField("session_key", in: row, maxChars: maxSessionFieldChars),
                  let statusRaw = boundedRequiredField("status", in: row, maxChars: maxEventNameChars),
                  let status = ActiveSessionStatus(rawValue: statusRaw),
                  seenDurableIDs.insert(durableID).inserted else { continue }
            let epoch = finiteNumberField("last_active", in: row)
            sessions.append(ActiveSessionRuntime(
                runtimeSessionID: runtimeID,
                durableSessionID: durableID,
                title: boundedOptionalField("title", in: row, maxChars: maxSessionFieldChars) ?? "Untitled session",
                status: status,
                messageCount: nonnegativeInteger(in: row, aliases: ["message_count"]).flatMap(Int.init(exactly:)),
                model: boundedOptionalField("model", in: row, maxChars: maxEventNameChars),
                lastActive: epoch.map(Date.init(timeIntervalSince1970:))
            ))
        }
        return sessions
    }

    func loadProjectTree(
        profile: String,
        previewLimit: Int = ProjectModelBounds.maxPreviewSessions,
        sessionLimit: Int = ProjectModelBounds.maxScopedSessionIDs
    ) async throws -> ProjectTreeResult {
        let result = try await request("projects.tree", [
            "profile": try boundedProjectOpaqueInput(profile, label: "project profile"),
            "preview_limit": min(ProjectModelBounds.maxPreviewSessions, max(0, previewLimit)),
            "session_limit": min(ProjectModelBounds.maxScopedSessionIDs, max(0, sessionLimit)),
        ])
        return try parseProjectResult(result, operation: "project tree") {
            try ProjectModelsParser.parseTree($0)
        }
    }

    func loadProjectSessions(
        projectID: ProjectID,
        profile: String,
        sessionLimit: Int = ProjectModelBounds.sessionScanBudget
    ) async throws -> ProjectSessionsResult {
        let requestedID = try boundedProjectOpaqueInput(projectID.rawValue, label: "project ID")
        let result = try await request("projects.project_sessions", [
            "project_id": requestedID,
            "profile": try boundedProjectOpaqueInput(profile, label: "project profile"),
            "session_limit": min(ProjectModelBounds.sessionScanBudget, max(0, sessionLimit)),
        ])
        return try parseProjectResult(result, operation: "project sessions") {
            try ProjectModelsParser.parseProjectSessions($0, requestedProjectID: ProjectID(requestedID))
        }
    }

    func createProject(
        name: String,
        folders: [String],
        primaryPath: String,
        use: Bool,
        profile: String
    ) async throws -> ProjectCreateResult {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else {
            throw ChatError.protocolError("Hermes project name must not be blank")
        }
        guard trimmedName.count <= ProjectModelBounds.maxLabelCharacters else {
            throw ChatError.protocolError("Hermes project name is too long")
        }
        guard !trimmedName.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            throw ChatError.protocolError("Hermes project name is invalid")
        }

        var seenFolders = Set<String>()
        var canonicalFolders: [String] = []
        for folder in folders {
            guard let canonical = validCanonicalHostFilePath(folder), canonical == folder else {
                throw ChatError.protocolError("Hermes project folder path is invalid")
            }
            if seenFolders.insert(canonical).inserted { canonicalFolders.append(canonical) }
        }
        guard !canonicalFolders.isEmpty else {
            throw ChatError.protocolError("Hermes project requires at least one folder")
        }
        guard let canonicalPrimary = validCanonicalHostFilePath(primaryPath),
              canonicalPrimary == primaryPath,
              seenFolders.contains(canonicalPrimary) else {
            throw ChatError.protocolError("Hermes project primary path must be one of its folders")
        }

        let result = try await request("projects.create", [
            "name": trimmedName,
            "folders": canonicalFolders,
            "primary_path": canonicalPrimary,
            "use": use,
            "profile": try boundedProjectOpaqueInput(profile, label: "project profile"),
        ])
        return try parseProjectResult(result, operation: "project create") {
            try ProjectModelsParser.parseCreateResult($0)
        }
    }

    @discardableResult
    func setActiveProject(id: ProjectID, profile: String) async throws -> ProjectID {
        let requestedID = try boundedProjectOpaqueInput(id.rawValue, label: "project ID")
        let result = try await request("projects.set_active", [
            "id": requestedID,
            "profile": try boundedProjectOpaqueInput(profile, label: "project profile"),
        ])
        let activeID: ProjectID? = try parseProjectResult(result, operation: "set active project") {
            try ProjectModelsParser.parseActiveProjectID($0)
        }
        guard activeID == ProjectID(requestedID) else {
            throw ChatError.protocolError("Set active project response did not match the requested project")
        }
        return ProjectID(requestedID)
    }

    /// `projects.delete`: removes the project REGISTRATION from projects.db
    /// (cascade over its folder rows). Sessions and host files are untouched —
    /// the server regroups the sessions into auto/Home buckets on the next
    /// tree build. Result is the refreshed projects payload; success is the
    /// absence of an error (unknown id → server error 5062).
    func deleteProject(id: ProjectID, profile: String) async throws {
        let requestedID = try boundedProjectOpaqueInput(id.rawValue, label: "project ID")
        _ = try await request("projects.delete", [
            "id": requestedID,
            "profile": try boundedProjectOpaqueInput(profile, label: "project profile"),
        ])
    }

    /// Narrow integration seam for audited M9 operations. Other method names
    /// remain inaccessible so views cannot turn this into arbitrary JSON-RPC.
    func operationsRequest(_ method: String, params: [String: Any]) async throws -> [String: Any] {
        guard method == "cron.manage" || method == "process.list" else {
            throw ChatError.protocolError("Unsupported operations method")
        }
        return try await request(method, params)
    }

    /// Sends one correlated JSON-RPC request without the direct-mode
    /// operations allowlist, for the Mercury Relay v1 method set
    /// (`gateway.ping`, `session.*`, `prompt.submit`, …) and the `relay.*`
    /// in-process reads the host intercepts at the lease layer. The relay
    /// method policy is the authority over what is permitted on the wire.
    func relayRequest(_ method: String, params: [String: Any] = [:]) async throws -> [String: Any] {
        try await request(method, params)
    }

    /// Process-local registry: callers may only reconcile IDs already observed on their own runtime.
    func backgroundTaskStatuses() async throws -> [String: String] {
        let result = try await request("delegation.status", [:])
        var statuses: [String: String] = [:]
        for row in (result["active"] as? [[String: Any]] ?? []).prefix(64) {
            if let id = row["subagent_id"] as? String, !id.isEmpty, id.count <= 256,
               let status = row["status"] as? String, status.count <= 40 { statuses[id] = status }
        }
        return statuses
    }

    func close() async {
        stateLock.lock()
        if closed {
            stateLock.unlock()
            return
        }
        closed = true
        let pending = pendingRequests
        let timeoutTasks = Array(pendingRequestTimeouts.values)
        pendingRequests.removeAll()
        pendingRequestMethods.removeAll()
        pendingBindingParams.removeAll()
        pendingRequestTimeouts.removeAll()
        stateLock.unlock()

        for timeoutTask in timeoutTasks { timeoutTask.cancel() }
        let error = ChatError.transport("Hermes chat connection closed")
        for (_, continuation) in pending { continuation.finish(throwing: error) }
        await socket.close()
        finishStreams()
    }

    // MARK: - Request/response correlation

    private struct PendingRequest {
        let continuation: ResponseStream.Continuation
        let method: String?
        let bindingParams: [String: Any]?
    }

    private func takePendingRequest(_ id: String) -> PendingRequest? {
        stateLock.lock()
        guard let continuation = pendingRequests.removeValue(forKey: id) else {
            stateLock.unlock()
            return nil
        }
        let pending = PendingRequest(
            continuation: continuation,
            method: pendingRequestMethods.removeValue(forKey: id),
            bindingParams: pendingBindingParams.removeValue(forKey: id)
        )
        let timeoutTask = pendingRequestTimeouts.removeValue(forKey: id)
        stateLock.unlock()
        timeoutTask?.cancel()
        return pending
    }

    private func failPendingRequest(_ id: String, error: Error) {
        guard let pending = takePendingRequest(id) else { return }
        pending.continuation.finish(throwing: error)
    }

    private func request(
        _ method: String,
        _ params: [String: Any],
        timeoutNanoseconds: UInt64? = nil
    ) async throws -> [String: Any] {
        stateLock.lock()
        if closed {
            stateLock.unlock()
            throw ChatError.transport("Hermes chat connection is closed")
        }
        let number = nextRequestID
        let id = requestNamespace.map { "\($0):\(number)" } ?? String(number)
        nextRequestID += 1
        stateLock.unlock()

        let frame: [String: Any] = [
            "jsonrpc": "2.0",
            "id": requestNamespace == nil ? (number as Any) : (id as Any),
            "method": method,
            "params": params,
        ]
        let data: Data
        do {
            data = try JSONSerialization.data(withJSONObject: frame)
        } catch {
            throw ChatError.protocolError("Hermes chat request was not serializable")
        }
        let text = String(data: data, encoding: .utf8) ?? ""
        try ensureFrameSize(text)

        let (responseStream, responseContinuation) = ResponseStream.makeStream(of: [String: Any].self)
        return try await withTaskCancellationHandler(operation: {
            stateLock.lock()
            if closed {
                stateLock.unlock()
                responseContinuation.finish(throwing: ChatError.transport("Hermes chat connection is closed"))
                throw ChatError.transport("Hermes chat connection is closed")
            }
            pendingRequests[id] = responseContinuation
            pendingRequestMethods[id] = method
            if method == "session.resume" || method == "session.create" {
                pendingBindingParams[id] = params
            }
            stateLock.unlock()

            // The continuation is registered before the send so a fast response
            // cannot be dropped. Transmission itself stays in this structured
            // request task; no unstructured sender can outlive cancellation.
            if Task.isCancelled {
                failPendingRequest(id, error: CancellationError())
                throw CancellationError()
            }

            if let timeoutNanoseconds {
                let timeoutTask = Task { [weak self] in
                    do {
                        try await Task.sleep(nanoseconds: timeoutNanoseconds)
                        try Task.checkCancellation()
                    } catch {
                        return
                    }
                    self?.failPendingRequest(
                        id,
                        error: ChatError.transport("Hermes RPC request timed out")
                    )
                }
                stateLock.lock()
                if pendingRequests[id] != nil && !closed {
                    pendingRequestTimeouts[id] = timeoutTask
                    stateLock.unlock()
                } else {
                    stateLock.unlock()
                    timeoutTask.cancel()
                }
            }

            do {
                try Task.checkCancellation()
                try await socket.sendText(text)
            } catch is CancellationError {
                failPendingRequest(id, error: CancellationError())
            } catch {
                failPendingRequest(
                    id,
                    error: ChatError.transport("Could not send Hermes chat request")
                )
                throw ChatError.transport("Could not send Hermes chat request")
            }

            var iterator = responseStream.makeAsyncIterator()
            guard let result = try await iterator.next() else {
                if Task.isCancelled {
                    throw CancellationError()
                }
                throw ChatError.transport("Hermes response stream ended")
            }
            return result
        }, onCancel: {
            self.failPendingRequest(id, error: CancellationError())
        })
    }

    // MARK: - Read loop

    private func readLoop() async {
        var failure: Error?
        while true {
            stateLock.lock()
            let isClosed = closed
            stateLock.unlock()
            if isClosed { break }

            let frame: String?
            do {
                frame = try await socket.receiveText()
            } catch {
                failure = ChatError.transport("Hermes chat receive failed")
                break
            }
            guard let frame else {
                failure = ChatError.transport("Hermes chat connection closed by peer")
                break
            }
            do {
                try ensureFrameSize(frame)
                try await handleFrame(frame)
            } catch {
                failure = error
                break
            }
        }

        // Teardown: fail pending callers, mark closed, end streams.
        stateLock.lock()
        _ = closed
        closed = true
        let pending = pendingRequests
        let timeoutTasks = Array(pendingRequestTimeouts.values)
        pendingRequests.removeAll()
        pendingRequestMethods.removeAll()
        pendingBindingParams.removeAll()
        pendingRequestTimeouts.removeAll()
        stateLock.unlock()

        for timeoutTask in timeoutTasks { timeoutTask.cancel() }
        let error = failure ?? ChatError.transport("Hermes chat connection closed")
        for (_, continuation) in pending { continuation.finish(throwing: error) }
        await socket.close()
        finishStreams()
    }

    // MARK: - Frame handling

    private func handleFrame(_ frame: String) async throws {
        let raw: Any
        do {
            raw = try JSONSerialization.jsonObject(with: Data(frame.utf8))
        } catch {
            throw ChatError.protocolError("Hermes chat frame was invalid")
        }
        guard let message = raw as? [String: Any] else {
            throw ChatError.protocolError("Hermes chat frame was invalid")
        }

        guard let version = message["jsonrpc"] as? String, version == "2.0" else { return }

        if (message["method"] as? String) == "event" {
            handleSharedEvent(message)
            return
        }

        guard let id = (message["id"] as? String) ?? int64Field("id", in: message).map(String.init) else { return }
        guard let pending = takePendingRequest(id) else { return }
        let continuation = pending.continuation
        let method = pending.method
        let bindingParams = pending.bindingParams

        if let errorObject = message["error"] as? [String: Any] {
            let code = int64Field("code", in: errorObject)
            if code == -32601 {
                continuation.finish(throwing: ChatMethodNotFoundError(method: method ?? ""))
                return
            }
            if method == "relay.folders.list" || method == "relay.folders.create",
               let safeMessage = MercuryCore.RelayFoldersContract.shared.safeErrorMessage(
                   reason: errorObject["message"] as? String
               ) {
                continuation.finish(throwing: RelayFoldersError.hostRejected(safeMessage))
                return
            }
            let suffix = code.map { " (\($0))" } ?? ""
            continuation.finish(throwing: ChatError.protocolError("Hermes RPC request failed\(suffix)"))
            return
        }

        guard let result = message["result"] as? [String: Any] else {
            continuation.finish(throwing: ChatError.protocolError("Hermes response was incomplete"))
            return
        }
        // Install the authenticated correlated binding before reading the next
        // frame, not in the resumed caller (which races the pooled read loop).
        if let relaySocket, let bindingParams {
            if method == "session.resume", let durable = bindingParams["session_id"] as? String,
               let resumed = try? parseResumeResult(result, requestedDurableSessionID: durable) {
                await relaySocket.bindTaskRuntime(runtime: resumed.runtimeSessionID, durable: durable,
                                                   profile: bindingParams["profile"] as? String)
            } else if method == "session.create",
                      let runtime = boundedRequiredField("session_id", in: result, maxChars: maxEventIDChars),
                      let durable = boundedRequiredField("stored_session_id", in: result, maxChars: maxEventNameChars) {
                await relaySocket.bindTaskRuntime(runtime: runtime, durable: durable,
                                                   profile: bindingParams["profile"] as? String)
            }
        }
        continuation.yield(result)
        continuation.finish()
    }

    // MARK: - Event decoding

    private func handleSharedEvent(_ message: [String: Any]) {
        if let params = message["params"] as? [String: Any],
           let sessionID = boundedRequiredField("session_id", in: params, maxChars: maxEventIDChars),
           let type = params["type"] as? String,
           let payload = params["payload"] as? [String: Any],
           var evidence = BackgroundTaskEvidence.decode(type: type, payload: payload) {
            if relaySocket != nil {
                evidence.eventID = params["relay_event_id"] as? String
                evidence.historical = params["relay_replay"] as? Bool == true
            }
            emit(.backgroundTask(sessionID: sessionID, evidence: evidence))
            return
        }
        guard let params = message["params"] as? [String: Any],
              let sessionID = boundedRequiredField("session_id", in: params, maxChars: maxEventIDChars),
              let type = stringField("type", in: params),
              let payload = params["payload"] as? [String: Any],
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let payloadJSON = String(data: data, encoding: .utf8),
              let shared = MercuryCore.ChatEventDecoder.shared.decode(
                type: type,
                sessionId: sessionID,
                payloadJson: payloadJSON
              ),
              let decodedEvent = ChatEvent(shared: shared)
        else { return }

        let event: ChatEvent
        let historical = relaySocket != nil && params["relay_replay"] as? Bool == true
        switch decodedEvent {
        case .toolStart(let session, let id, let name, let context, _):
            event = .toolStart(sessionID: session, toolID: id, name: name, context: context, historical: historical)
        case .toolComplete(let session, let id, let name, let summary, _, _):
            event = .toolComplete(sessionID: session, toolID: id, name: name, summary: summary,
                progressSnapshot: MercuryCore.DurableProgressBridge.shared.liveSnapshotJson(payloadJson: payloadJSON),
                historical: historical)
        default:
            event = decodedEvent
        }
        switch event {
        case .approvalRequest(_, let requestID, let command, let description, let choices):
            stateLock.lock()
            pendingApprovals[sessionID, default: []].append(
                PendingApproval(
                    requestID: requestID,
                    command: command,
                    description: description,
                    choices: choices
                )
            )
            stateLock.unlock()
        case .approvalExpire(_, let requestID):
            stateLock.lock()
            if var queue = pendingApprovals[sessionID] {
                queue.removeAll { $0.requestID == requestID }
                if queue.isEmpty {
                    pendingApprovals.removeValue(forKey: sessionID)
                } else {
                    pendingApprovals[sessionID] = queue
                }
            }
            stateLock.unlock()
        default:
            break
        }
        emit(event)
    }


    private static func approvalEvent(for approval: PendingApproval, sessionID: String) -> ChatEvent {
        .approvalRequest(
            sessionID: sessionID,
            requestID: approval.requestID,
            command: approval.command,
            description: approval.description,
            choices: approval.choices
        )
    }

    // MARK: - Result parsing (shared decoder)
    //
    // Result decoding is a shared decision (`MercuryCore.RpcResultDecoder`).
    // The dictionary the transport produced is re-serialised once and handed
    // to the core; these functions only map its typed result onto Swift models.

    private func resultJSON(_ result: [String: Any]) throws -> String {
        guard JSONSerialization.isValidJSONObject(result),
              let data = try? JSONSerialization.data(withJSONObject: result),
              let text = String(data: data, encoding: .utf8) else {
            throw ChatError.protocolError("Hermes response could not be decoded")
        }
        return text
    }

    /// A Kotlin `RpcResultException` arrives as NSError; keep its safe message.
    private func sharedProtocolError(_ error: Error) -> Error {
        if let kotlin = (error as NSError).userInfo["KotlinException"] as? MercuryCore.RpcResultException {
            return ChatError.protocolError(kotlin.message ?? "Hermes response was incomplete")
        }
        return error
    }

    private func parseModelOptions(_ result: [String: Any]) -> ModelOptions {
        guard let json = try? resultJSON(result) else { return ModelOptions(current: nil, providers: []) }
        let decoded = MercuryCore.RpcResultDecoder.shared.modelOptions(resultJson: json)
        return ModelOptions(
            current: decoded.current.map { ModelSelection(provider: $0.provider, model: $0.model) },
            providers: decoded.providers.map { provider in
                ModelProviderOption(
                    slug: provider.slug,
                    name: provider.name,
                    models: provider.models,
                    capabilities: provider.capabilities.mapValues {
                        ModelCapabilities(fast: $0.fast?.boolValue, reasoning: $0.reasoning?.boolValue)
                    }
                )
            }
        )
    }

    private func validateConfigResult(
        _ result: [String: Any],
        expectedKey: String,
        operation: String
    ) throws {
        if let rawScope = result["scope"] {
            guard let scope = rawScope as? String, scope == "session" else {
                throw ChatError.protocolError("Hermes \(operation) returned an unsafe scope")
            }
        }
        if let rawKey = result["key"] {
            guard let key = rawKey as? String, key == expectedKey else {
                throw ChatError.protocolError("Hermes \(operation) returned the wrong key")
            }
        }
    }

    private func sessionUsage(_ decoded: MercuryCore.SessionUsageResult) -> SessionUsage {
        SessionUsage(
            inputTokens: decoded.inputTokens?.int64Value,
            outputTokens: decoded.outputTokens?.int64Value,
            totalTokens: decoded.totalTokens?.int64Value,
            contextUsedTokens: decoded.contextUsedTokens?.int64Value,
            contextMaxTokens: decoded.contextMaxTokens?.int64Value,
            contextPercent: decoded.contextPercent?.doubleValue,
            calls: decoded.calls?.int64Value,
            creditsLines: decoded.creditsLines,
            rawInfo: decoded.rawInfo
        )
    }

    private func parseSessionUsage(_ result: [String: Any]) -> SessionUsage {
        // An unserialisable result decodes like an empty object.
        let json = (try? resultJSON(result)) ?? "{}"
        return sessionUsage(MercuryCore.RpcResultDecoder.shared.sessionUsage(resultJson: json))
    }

    private func parseContextBreakdown(_ result: [String: Any]) -> SessionContextBreakdown {
        guard let json = try? resultJSON(result) else {
            return SessionContextBreakdown(categories: [], usedTokens: nil, maxTokens: nil, percent: nil)
        }
        let decoded = MercuryCore.RpcResultDecoder.shared.contextBreakdown(resultJson: json)
        return SessionContextBreakdown(
            categories: decoded.categories.map {
                ContextBreakdownCategory(name: $0.name, tokens: $0.tokens?.int64Value, percent: $0.percent?.doubleValue)
            },
            usedTokens: decoded.usedTokens?.int64Value,
            maxTokens: decoded.maxTokens?.int64Value,
            percent: decoded.percent?.doubleValue
        )
    }

    private func messageRows(_ rows: [String]) -> [[String: Any]] {
        rows.compactMap { row in
            (try? JSONSerialization.jsonObject(with: Data(row.utf8))) as? [String: Any]
        }
    }

    private func parseCompressResult(_ result: [String: Any]) -> SessionCompressResult {
        guard let json = try? resultJSON(result) else {
            return SessionCompressResult(status: nil, aborted: false, messages: [], usage: nil)
        }
        let decoded = MercuryCore.RpcResultDecoder.shared.compress(resultJson: json)
        return SessionCompressResult(
            status: decoded.status,
            aborted: decoded.aborted,
            messages: messageRows(decoded.messagesJson),
            usage: decoded.usage.map(sessionUsage)
        )
    }

    private func parseBranchResult(_ result: [String: Any]) throws -> SessionBranchResult {
        do {
            let decoded = try MercuryCore.RpcResultDecoder.shared.branch(resultJson: resultJSON(result))
            return SessionBranchResult(
                runtimeSessionID: decoded.runtimeSessionId,
                durableSessionID: decoded.durableSessionId,
                title: decoded.title,
                messages: messageRows(decoded.messagesJson)
            )
        } catch {
            throw sharedProtocolError(error)
        }
    }

    private func parseSlashCompletion(_ result: [String: Any], inputLength: Int) throws -> SlashCompletionResult {
        do {
            let decoded = try MercuryCore.RpcResultDecoder.shared.slashCompletion(
                resultJson: resultJSON(result), inputLength: Int32(inputLength)
            )
            return SlashCompletionResult(
                items: decoded.items.map { SlashCompletionItem(text: $0.text, display: $0.display, meta: $0.meta) },
                replaceFrom: Int(decoded.replaceFrom)
            )
        } catch {
            throw sharedProtocolError(error)
        }
    }

    private func parseResumeResult(_ result: [String: Any], requestedDurableSessionID: String) throws -> ResumedChatSession {
        do {
            let decoded = try MercuryCore.RpcResultDecoder.shared.resume(
                resultJson: resultJSON(result), requestedDurableSessionId: requestedDurableSessionID
            )
            return ResumedChatSession(
                runtimeSessionID: decoded.runtimeSessionId,
                durableSessionID: decoded.durableSessionId,
                resumed: decoded.resumed,
                messages: messageRows(decoded.messagesJson),
                running: decoded.running,
                inflight: decoded.hasInflight
                    ? InflightPrompt(
                        user: decoded.inflightUser,
                        assistant: decoded.inflightAssistant,
                        streaming: decoded.inflightStreaming
                    )
                    : nil,
                model: decoded.model,
                provider: decoded.provider,
                reasoningEffort: decoded.reasoningEffort,
                fastMode: decoded.fastMode?.boolValue
            )
        } catch {
            throw sharedProtocolError(error)
        }
    }

    private func parseInteractionResponse(_ result: [String: Any]) throws -> ChatResponse {
        do {
            let status = try MercuryCore.RpcResultDecoder.shared.interactionResponse(resultJson: resultJSON(result))
            return ChatResponse(status: ChatResponse.Status.fromWire(status.name), nextApproval: nil)
        } catch {
            throw sharedProtocolError(error)
        }
    }

    // MARK: - Field access helpers (JSONSerialization-tolerant)

    private func stringField(_ name: String, in object: [String: Any]) -> String? {
        object[name] as? String
    }


    private func int64Field(_ name: String, in object: [String: Any]) -> Int64? {
        switch object[name] {
        case let number as Int: return Int64(number)
        case let number as Int64: return number
        case let number as Double:
            return number == number.rounded() && abs(number) < 9.2e18 ? Int64(number) : nil
        case let number as NSNumber: return number.int64Value
        default: return nil
        }
    }

    private func boolField(_ name: String, in object: [String: Any]) -> Bool? {
        switch object[name] {
        case let flag as Bool: return flag
        case let number as NSNumber: return number.boolValue
        default: return nil
        }
    }

    private func strictJSONBool(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber,
              String(cString: number.objCType) == "c" else { return nil }
        return number.boolValue
    }

    private func optionalStrictBool(_ name: String, in object: [String: Any]) throws -> Bool? {
        guard let raw = object[name] else { return nil }
        guard let value = strictJSONBool(raw) else {
            throw ChatError.protocolError("Hermes response contained a malformed boolean")
        }
        return value
    }

    private func sessionParams(_ runtimeSessionID: String) throws -> [String: Any] {
        [
            "session_id": try boundedRPCInput(
                runtimeSessionID,
                maxChars: maxEventIDChars,
                label: "runtime session ID"
            ),
        ]
    }

    private func strictInt64Field(_ name: String, in object: [String: Any]) -> Int64? {
        guard let raw = object[name], strictJSONBool(raw) == nil else { return nil }
        switch raw {
        case let value as Int: return Int64(value)
        case let value as Int64: return value
        case let value as Double:
            guard value.isFinite, value == value.rounded(), abs(value) < 9.2e18 else { return nil }
            return Int64(value)
        case let value as NSNumber:
            let number = value.doubleValue
            guard number.isFinite, number == number.rounded(), abs(number) < 9.2e18 else { return nil }
            return Int64(number)
        default: return nil
        }
    }

    private func finiteNumberField(_ name: String, in object: [String: Any]) -> Double? {
        guard let raw = object[name], strictJSONBool(raw) == nil else { return nil }
        let value: Double?
        switch raw {
        case let number as Int: value = Double(number)
        case let number as Int64: value = Double(number)
        case let number as Double: value = number
        case let number as NSNumber: value = number.doubleValue
        default: value = nil
        }
        return value?.isFinite == true ? value : nil
    }

    private func nonnegativeInteger(in object: [String: Any], aliases: [String]) -> Int64? {
        for alias in aliases {
            if let value = strictInt64Field(alias, in: object) { return max(0, value) }
        }
        return nil
    }

    private func validModelValue(_ value: String, maxChars: Int) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              trimmed.count <= maxChars,
              !trimmed.hasPrefix("-"),
              !trimmed.contains(where: { $0.isWhitespace }),
              trimmed.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else {
            return nil
        }
        return trimmed
    }

    private func boundedModelInput(_ value: String, maxChars: Int, label: String) throws -> String {
        guard let valid = validModelValue(value, maxChars: maxChars) else {
            throw ChatError.protocolError("Hermes \(label) is invalid")
        }
        return valid
    }

    /// Metadata-field bounded read: trim then cap (Android boundedOptional).
    private func boundedOptionalField(_ name: String, in object: [String: Any], maxChars: Int) -> String? {
        guard let value = object[name] as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(maxChars))
    }

    /// Required metadata-field read: present, non-blank after trimming, within
    /// the bound (Android boundedRequired).
    private func boundedRequiredField(_ name: String, in object: [String: Any], maxChars: Int) -> String? {
        guard let value = object[name] as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= maxChars else { return nil }
        return trimmed
    }

    /// Message TEXT bounded read — NEVER trims (Android boundedText).
    /// Streaming tokenizers attach leading spaces to deltas; trimming here
    /// destroys word boundaries across the whole stream.
    private func boundedTextField(_ name: String, in object: [String: Any], maxChars: Int) -> String? {
        guard let value = object[name] as? String, !value.isEmpty else { return nil }
        return String(value.prefix(maxChars))
    }


    // MARK: - Outbound input guards (boundedRpcInput parity)

    private func boundedRPCInput(_ value: String, maxChars: Int, label: String, allowBlank: Bool = false) throws -> String {
        if value.count > maxChars {
            throw ChatError.protocolError("Hermes \(label) is too long")
        }
        if !allowBlank && value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ChatError.protocolError("Hermes \(label) must not be blank")
        }
        return value
    }

    /// Project/profile IDs are opaque server values. Validate only their
    /// safety/bounds and preserve the exact value; never normalize or derive.
    private func boundedProjectOpaqueInput(_ value: String, label: String) throws -> String {
        guard value.count <= ProjectModelBounds.maxIDCharacters else {
            throw ChatError.protocolError("Hermes \(label) is too long")
        }
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            throw ChatError.protocolError("Hermes \(label) is invalid")
        }
        return value
    }

    private func parseProjectResult<T>(
        _ result: [String: Any],
        operation: String,
        parser: (Data) throws -> T
    ) throws -> T {
        do {
            let data = try JSONSerialization.data(withJSONObject: result)
            return try parser(data)
        } catch {
            throw ChatError.protocolError("Hermes \(operation) response was malformed")
        }
    }

    private func ensureFrameSize(_ frame: String) throws {
        guard frame.utf8.count <= maxFrameBytes else {
            throw ChatError.protocolError("Hermes chat frame exceeds the size limit")
        }
    }

    // MARK: - Event fan-out

    private func emit(_ event: ChatEvent) {
        stateLock.lock()
        eventBuffer.append(event)
        let sinks = Array(continuations.values)
        stateLock.unlock()
        for sink in sinks { sink.yield(event) }
    }

    private func finishStreams() {
        stateLock.lock()
        guard !streamFinished else {
            stateLock.unlock()
            return
        }
        streamFinished = true
        let sinks = Array(continuations.values)
        continuations.removeAll()
        stateLock.unlock()
        for sink in sinks {
            sink.finish()
        }
    }
}

/// Fixed-capacity FIFO with drop-oldest overflow, mirroring Android's
/// Channel(DROP_OLDEST) buffering for late-subscribing consumers.
private final class EventRingBuffer: @unchecked Sendable {
    private let capacity: Int
    private var items: [ChatEvent] = []

    init(capacity: Int) {
        self.capacity = max(1, capacity)
    }

    func append(_ event: ChatEvent) {
        items.append(event)
        if items.count > capacity {
            items.removeFirst(items.count - capacity)
        }
    }

    func snapshot() -> [ChatEvent] { items }
}
