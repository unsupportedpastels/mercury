import Foundation

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

    private let socket: any ChatSocketing
    private let maxFrameBytes: Int

    // MARK: Lifecycle state

    private let stateLock = NSLock()
    private var closed = false
    private var nextRequestID: Int64 = 1

    /// id → continuation for in-flight RPCs. Also mirrors method names so a
    /// -32601 failure can name the unsupported method.
    private var pendingRequests: [Int64: CheckedContinuation<[String: Any], Error>] = [:]
    private var pendingRequestMethods: [Int64: String] = [:]

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

    init(socket: any ChatSocketing, maxFrameBytes configured: Int? = nil) throws {
        let resolvedLimit = try validatedMaxFrameBytes(configured ?? 36 * 1024 * 1024)
        self.socket = socket
        self.maxFrameBytes = resolvedLimit
    }

    deinit {
        finishStreams()
    }

    // MARK: - Event consumption

    /// Starts the read loop. Returns a multiplexed event stream; multiple
    /// consumers each receive every event. Call exactly once per connection.
    func start() -> AsyncStream<ChatEvent> {
        let id = UUID()
        let stream = AsyncStream<ChatEvent>(bufferingPolicy: .unbounded) { continuation in
            let lock = self.stateLock
            lock.lock()
            if self.streamFinished {
                lock.unlock()
                continuation.finish()
                return
            }
            self.continuations[id] = continuation
            // Replay anything already buffered so early events are not lost.
            for event in self.eventBuffer.snapshot() {
                continuation.yield(event)
            }
            lock.unlock()
        }
        Task { await readLoop() }
        return stream
    }

    // MARK: - Public RPC surface (Android-parity subset)

    func resume(durableSessionID: String, profile: String?) async throws -> ResumedChatSession {
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

    func respondToClarification(requestID: String, answer: String) async throws -> ChatResponse {
        let boundedRequestID = try boundedRPCInput(requestID, maxChars: maxEventIDChars, label: "request ID")
        let boundedAnswer = try boundedRPCInput(answer, maxChars: maxEventTextChars, label: "answer", allowBlank: true)
        let result = try await request("clarify.respond", [
            "request_id": boundedRequestID,
            "answer": boundedAnswer,
        ])
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
        ])
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

    func close() async {
        stateLock.lock()
        if closed {
            stateLock.unlock()
            return
        }
        closed = true
        let pending = pendingRequests
        pendingRequests.removeAll()
        pendingRequestMethods.removeAll()
        stateLock.unlock()

        let error = ChatError.transport("Hermes chat connection closed")
        for (_, continuation) in pending { continuation.resume(throwing: error) }
        await socket.close()
        finishStreams()
    }

    // MARK: - Request/response correlation

    private func request(_ method: String, _ params: [String: Any]) async throws -> [String: Any] {
        stateLock.lock()
        if closed {
            stateLock.unlock()
            throw ChatError.transport("Hermes chat connection is closed")
        }
        let id = nextRequestID
        nextRequestID += 1
        stateLock.unlock()

        let frame: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
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

        return try await withCheckedThrowingContinuation { continuation in
            stateLock.lock()
            if closed {
                stateLock.unlock()
                continuation.resume(throwing: ChatError.transport("Hermes chat connection is closed"))
                return
            }
            pendingRequests[id] = continuation
            pendingRequestMethods[id] = method
            stateLock.unlock()

            Task {
                do {
                    try await self.socket.sendText(text)
                } catch {
                    self.stateLock.lock()
                    let registered = self.pendingRequests.removeValue(forKey: id)
                    self.pendingRequestMethods.removeValue(forKey: id)
                    self.stateLock.unlock()
                    if let registered {
                        registered.resume(throwing: ChatError.transport("Could not send Hermes chat request"))
                    }
                }
            }
        }
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
                try handleFrame(frame)
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
        pendingRequests.removeAll()
        pendingRequestMethods.removeAll()
        stateLock.unlock()

        let error = failure ?? ChatError.transport("Hermes chat connection closed")
        for (_, continuation) in pending { continuation.resume(throwing: error) }
        await socket.close()
        finishStreams()
    }

    // MARK: - Frame handling

    private func handleFrame(_ frame: String) throws {
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
            handleEvent(message)
            return
        }

        guard let id = int64Field("id", in: message) else { return }
        stateLock.lock()
        let continuation = pendingRequests.removeValue(forKey: id)
        let method = pendingRequestMethods.removeValue(forKey: id)
        stateLock.unlock()
        guard let continuation else { return }

        if let errorObject = message["error"] as? [String: Any] {
            let code = int64Field("code", in: errorObject)
            if code == -32601 {
                continuation.resume(throwing: ChatMethodNotFoundError(method: method ?? ""))
                return
            }
            let suffix = code.map { " (\($0))" } ?? ""
            continuation.resume(throwing: ChatError.protocolError("Hermes RPC request failed\(suffix)"))
            return
        }

        guard let result = message["result"] as? [String: Any] else {
            continuation.resume(throwing: ChatError.protocolError("Hermes response was incomplete"))
            return
        }
        continuation.resume(returning: result)
    }

    // MARK: - Event decoding

    /// Known event types, copied verbatim from Android's knownTypes list.
    /// Types outside this set are ignored (forward compatibility).
    private static let knownEventTypes: Set<String> = [
        "message.start", "message.delta", "message.complete", "error",
        "tool.start", "tool.complete", "tool.generating", "status.update",
        "clarify.request", "clarify.expire", "approval.request", "approval.expire",
        "secret.request", "secret.expire", "sudo.request", "sudo.expire",
        "terminal.read.request", "terminal.read.expire",
        "preview.read.request", "preview.read.expire",
        "window.read.request", "window.read.expire",
        "session.info", "session.title", "reasoning.delta", "reasoning.available",
        "message.interim",
        // Intentionally ignored (no mobile surface): gateway.ready, skin.changed,
        // sessions.changed, cron.changed, pet.changed, thinking.delta, reaction,
        // moa.*, voice.*, wake.detected, browser.progress, terminal.close,
        // notification.clear, preview.restart.progress.
    ]

    private func handleEvent(_ message: [String: Any]) {
        guard let params = message["params"] as? [String: Any],
              let sessionIDRaw = boundedRequiredField("session_id", in: params, maxChars: maxEventIDChars),
              !sessionIDRaw.isEmpty,
              let type = stringField("type", in: params),
              Self.knownEventTypes.contains(type),
              let payload = params["payload"] as? [String: Any]
        else { return }

        let event: ChatEvent?
        switch type {
        case "message.start":
            event = .messageStart(
                sessionID: sessionIDRaw,
                text: boundedTextField("text", in: payload, maxChars: maxMessageTextChars)
            )

        case "message.delta":
            if let text = boundedTextField("text", in: payload, maxChars: maxMessageTextChars) {
                event = .messageDelta(sessionID: sessionIDRaw, text: text)
            } else {
                event = nil
            }

        case "message.complete":
            let billing = (payload["billing"] as? [String: Any]).map { billing in
                BillingInfo(
                    provider: boundedOptionalField("provider", in: billing, maxChars: maxEventNameChars),
                    billingURL: boundedOptionalField("billing_url", in: billing, maxChars: maxEventTextChars),
                    isNous: boolField("is_nous", in: billing) ?? false,
                    message: boundedOptionalField("message", in: billing, maxChars: maxEventTextChars)
                )
            }
            event = .messageComplete(
                sessionID: sessionIDRaw,
                text: boundedTextField("text", in: payload, maxChars: maxMessageTextChars),
                status: boundedOptionalField("status", in: payload, maxChars: maxEventNameChars),
                error: boundedOptionalField("error", in: payload, maxChars: maxEventTextChars),
                reasoning: boundedTextField("reasoning", in: payload, maxChars: maxMessageTextChars),
                warning: boundedOptionalField("warning", in: payload, maxChars: maxEventTextChars),
                failureReason: boundedOptionalField("failure_reason", in: payload, maxChars: maxEventTextChars),
                recoverable: boolField("recoverable", in: payload) ?? false,
                billing: billing
            )

        case "reasoning.delta", "reasoning.available":
            if let text = boundedTextField("text", in: payload, maxChars: maxMessageTextChars) {
                event = .reasoningDelta(
                    sessionID: sessionIDRaw,
                    text: text,
                    replace: type == "reasoning.available"
                )
            } else {
                event = nil
            }

        case "message.interim":
            if let text = boundedTextField("text", in: payload, maxChars: maxMessageTextChars) {
                event = .messageInterim(
                    sessionID: sessionIDRaw,
                    text: text,
                    alreadyStreamed: boolField("already_streamed", in: payload) ?? false
                )
            } else {
                event = nil
            }

        case "tool.generating":
            if let name = boundedRequiredField("name", in: payload, maxChars: maxEventNameChars) {
                event = .toolGenerating(sessionID: sessionIDRaw, name: name)
            } else {
                event = nil
            }

        case "session.title":
            if let title = boundedRequiredField("title", in: payload, maxChars: maxEventNameChars) {
                event = .sessionTitle(sessionID: sessionIDRaw, title: title)
            } else {
                event = nil
            }

        case "session.info":
            event = .sessionInfo(
                sessionID: sessionIDRaw,
                storedSessionID: boundedOptionalField("stored_session_id", in: payload, maxChars: maxEventNameChars),
                model: boundedOptionalField("model", in: payload, maxChars: maxEventNameChars),
                provider: boundedOptionalField("provider", in: payload, maxChars: maxEventNameChars),
                reasoningEffort: boundedOptionalField("reasoning_effort", in: payload, maxChars: maxEventNameChars),
                fastMode: boolField("fast", in: payload),
                title: boundedOptionalField("title", in: payload, maxChars: maxEventNameChars),
                running: boolField("running", in: payload)
            )

        case "error":
            if let text = boundedOptionalField("message", in: payload, maxChars: maxEventTextChars) {
                event = .error(sessionID: sessionIDRaw, message: text)
            } else {
                event = nil
            }

        case "tool.start":
            guard let toolID = boundedRequiredField("tool_id", in: payload, maxChars: maxEventIDChars),
                  let name = boundedRequiredField("name", in: payload, maxChars: maxEventNameChars) else {
                event = nil
                break
            }
            event = .toolStart(
                sessionID: sessionIDRaw,
                toolID: toolID,
                name: name,
                context: boundedOptionalField("context", in: payload, maxChars: maxEventContextChars)
            )

        case "tool.complete":
            guard let toolID = boundedRequiredField("tool_id", in: payload, maxChars: maxEventIDChars),
                  let name = boundedRequiredField("name", in: payload, maxChars: maxEventNameChars) else {
                event = nil
                break
            }
            event = .toolComplete(
                sessionID: sessionIDRaw,
                toolID: toolID,
                name: name,
                summary: boundedOptionalField("summary", in: payload, maxChars: maxEventTextChars)
            )

        case "status.update":
            guard let kind = boundedRequiredField("kind", in: payload, maxChars: maxEventNameChars),
                  let text = boundedRequiredField("text", in: payload, maxChars: maxEventTextChars) else {
                event = nil
                break
            }
            event = .statusUpdate(sessionID: sessionIDRaw, kind: kind, text: text)

        case "clarify.request":
            guard let requestID = boundedRequiredField("request_id", in: payload, maxChars: maxEventIDChars),
                  let question = boundedRequiredField("question", in: payload, maxChars: maxEventTextChars) else {
                event = nil
                break
            }
            event = .clarifyRequest(
                sessionID: sessionIDRaw,
                requestID: requestID,
                question: question,
                choices: boundedChoices(in: payload),
                multiSelect: boolField("multi_select", in: payload) ?? false
            )

        case "clarify.expire":
            if let requestID = boundedRequiredField("request_id", in: payload, maxChars: maxEventIDChars) {
                event = .clarifyExpire(sessionID: sessionIDRaw, requestID: requestID)
            } else {
                event = nil
            }

        case "approval.request":
            let choices = boundedChoices(in: payload)
            if choices.isEmpty {
                event = nil
                break
            }
            let approval = PendingApproval(
                requestID: optionalStringField("request_id", in: payload),
                command: boundedOptionalField("command", in: payload, maxChars: maxEventTextChars),
                description: boundedOptionalField("description", in: payload, maxChars: maxEventTextChars),
                choices: choices
            )
            stateLock.lock()
            pendingApprovals[sessionIDRaw, default: []].append(approval)
            stateLock.unlock()
            event = Self.approvalEvent(for: approval, sessionID: sessionIDRaw)

        case "approval.expire":
            if let requestID = boundedRequiredField("request_id", in: payload, maxChars: maxEventIDChars) {
                stateLock.lock()
                if var queue = pendingApprovals[sessionIDRaw] {
                    queue.removeAll { $0.requestID == requestID }
                    if queue.isEmpty {
                        pendingApprovals.removeValue(forKey: sessionIDRaw)
                    } else {
                        pendingApprovals[sessionIDRaw] = queue
                    }
                }
                stateLock.unlock()
                event = .approvalExpire(sessionID: sessionIDRaw, requestID: requestID)
            } else {
                event = nil
            }

        case "secret.request", "sudo.request", "terminal.read.request",
             "preview.read.request", "window.read.request":
            let kind: UnsupportedBlockingKind
            switch type {
            case "secret.request": kind = .secret
            case "sudo.request": kind = .sudo
            case "preview.read.request": kind = .previewRead
            case "window.read.request": kind = .windowRead
            default: kind = .terminalRead
            }
            if let requestID = boundedRequiredField("request_id", in: payload, maxChars: maxEventIDChars) {
                event = .unsupportedBlockingRequest(
                    sessionID: sessionIDRaw,
                    kind: kind,
                    requestID: requestID,
                    prompt: boundedOptionalField("prompt", in: payload, maxChars: maxEventTextChars)
                )
            } else {
                event = nil
            }

        case "secret.expire", "sudo.expire", "terminal.read.expire",
             "preview.read.expire", "window.read.expire":
            let kind: UnsupportedBlockingKind
            switch type {
            case "secret.expire": kind = .secret
            case "sudo.expire": kind = .sudo
            case "preview.read.expire": kind = .previewRead
            case "window.read.expire": kind = .windowRead
            default: kind = .terminalRead
            }
            if let requestID = boundedRequiredField("request_id", in: payload, maxChars: maxEventIDChars) {
                event = .unsupportedBlockingExpire(sessionID: sessionIDRaw, kind: kind, requestID: requestID)
            } else {
                event = nil
            }

        default:
            event = nil
        }

        if let event { emit(event) }
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

    // MARK: - Result parsing

    private func parseModelOptions(_ result: [String: Any]) -> ModelOptions {
        let rawProviders = (result["providers"] as? [Any]) ?? []
        var seenProviders = Set<String>()
        var providers: [ModelProviderOption] = []

        for element in rawProviders.prefix(maxModelProviders) {
            guard let row = element as? [String: Any] else { continue }
            // Only an explicit JSON false means unauthenticated. Missing and
            // future/mistyped additive fields do not hide an otherwise valid row.
            if let authenticated = strictJSONBool(row["authenticated"]), !authenticated { continue }
            guard let slug = validModelField("slug", in: row, maxChars: maxModelProviderChars),
                  seenProviders.insert(slug).inserted else { continue }
            let name = boundedOptionalField("name", in: row, maxChars: maxModelProviderChars) ?? slug

            var seenModels = Set<String>()
            var models: [String] = []
            for rawModel in ((row["models"] as? [Any]) ?? []).prefix(maxModelsPerProvider) {
                guard let value = rawModel as? String,
                      let model = validModelValue(value, maxChars: maxModelIDChars),
                      seenModels.insert(model).inserted else { continue }
                models.append(model)
            }
            guard !models.isEmpty else { continue }

            var capabilities: [String: ModelCapabilities] = [:]
            if let rawCapabilities = row["capabilities"] as? [String: Any] {
                for (rawModel, rawValue) in rawCapabilities.prefix(maxModelsPerProvider) {
                    guard let model = validModelValue(rawModel, maxChars: maxModelIDChars),
                          let object = rawValue as? [String: Any] else { continue }
                    let fast = strictJSONBool(object["fast"])
                    let reasoning = strictJSONBool(object["reasoning"])
                    capabilities[model] = ModelCapabilities(fast: fast, reasoning: reasoning)
                }
            }
            providers.append(ModelProviderOption(slug: slug, name: name, models: models, capabilities: capabilities))
        }

        let current: ModelSelection?
        if let provider = validModelField("provider", in: result, maxChars: maxModelProviderChars),
           let model = validModelField("model", in: result, maxChars: maxModelIDChars) {
            current = ModelSelection(provider: provider, model: model)
        } else {
            current = nil
        }
        return ModelOptions(current: current, providers: providers)
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

    private func parseSessionUsage(_ result: [String: Any]) -> SessionUsage {
        SessionUsage(
            inputTokens: nonnegativeInteger(in: result, aliases: ["input_tokens", "input", "prompt_tokens"]),
            outputTokens: nonnegativeInteger(in: result, aliases: ["output_tokens", "output", "completion_tokens"]),
            totalTokens: nonnegativeInteger(in: result, aliases: ["total_tokens", "total"]),
            contextUsedTokens: nonnegativeInteger(
                in: result, aliases: ["context_used_tokens", "context_used", "used_tokens"]
            ),
            contextMaxTokens: nonnegativeInteger(
                in: result, aliases: ["context_max_tokens", "context_max", "max_tokens"]
            ),
            contextPercent: boundedPercent(
                in: result, aliases: ["context_percent", "context_percentage", "percent"]
            ),
            calls: nonnegativeInteger(in: result, aliases: ["calls", "request_count", "requests"]),
            creditsLines: boundedStringArray(result["credits_lines"], maxRows: maxSessionResultRows),
            rawInfo: boundedOptionalField("info", in: result, maxChars: maxSessionFieldChars)
        )
    }

    private func parseContextBreakdown(_ result: [String: Any]) -> SessionContextBreakdown {
        let rawRows = (result["categories"] as? [Any]) ?? (result["breakdown"] as? [Any]) ?? []
        var seen = Set<String>()
        var categories: [ContextBreakdownCategory] = []
        for element in rawRows.prefix(maxContextCategories) {
            guard let row = element as? [String: Any],
                  let name = firstBoundedText(in: row, aliases: ["name", "category", "label"]),
                  seen.insert(name).inserted else { continue }
            categories.append(ContextBreakdownCategory(
                name: name,
                tokens: nonnegativeInteger(in: row, aliases: ["tokens", "token_count", "count"]),
                percent: boundedPercent(in: row, aliases: ["percent", "percentage"])
            ))
        }
        return SessionContextBreakdown(
            categories: categories,
            usedTokens: nonnegativeInteger(
                in: result, aliases: ["used_tokens", "context_used_tokens", "context_used"]
            ),
            maxTokens: nonnegativeInteger(
                in: result, aliases: ["max_tokens", "context_max_tokens", "context_max"]
            ),
            percent: boundedPercent(in: result, aliases: ["percent", "context_percent"])
        )
    }

    private func parseCompressResult(_ result: [String: Any]) -> SessionCompressResult {
        let status = boundedOptionalField("status", in: result, maxChars: maxSessionFieldChars)
        let normalizedStatus = status?.lowercased()
        let aborted = (result["aborted"] as? Bool) == true ||
            ["aborted", "cancelled", "canceled"].contains(normalizedStatus ?? "")
        let messages = ((result["messages"] as? [Any]) ?? [])
            .prefix(maxSessionResultRows)
            .compactMap { $0 as? [String: Any] }
        let usage = (result["usage"] as? [String: Any]).map(parseSessionUsage)
        return SessionCompressResult(status: status, aborted: aborted, messages: messages, usage: usage)
    }

    private func parseBranchResult(_ result: [String: Any]) throws -> SessionBranchResult {
        guard let durable = boundedRequiredField("stored_session_id", in: result, maxChars: maxSessionFieldChars)
            ?? boundedRequiredField("durable_session_id", in: result, maxChars: maxSessionFieldChars) else {
            throw ChatError.protocolError("Branch response was incomplete")
        }
        let runtime = boundedOptionalField("session_id", in: result, maxChars: maxEventIDChars)
        let title = boundedOptionalField("title", in: result, maxChars: maxSessionFieldChars)
        let messages = ((result["messages"] as? [Any]) ?? [])
            .prefix(maxSessionResultRows)
            .compactMap { $0 as? [String: Any] }
        return SessionBranchResult(
            runtimeSessionID: runtime,
            durableSessionID: durable,
            title: title,
            messages: messages
        )
    }

    private func parseSlashCompletion(_ result: [String: Any], inputLength: Int) throws -> SlashCompletionResult {
        var items: [SlashCompletionItem] = []
        for element in ((result["items"] as? [Any]) ?? []).prefix(maxSessionResultRows) {
            guard let row = element as? [String: Any],
                  let text = boundedRequiredField("text", in: row, maxChars: maxSessionFieldChars) else { continue }
            let display = boundedOptionalField("display", in: row, maxChars: maxSessionFieldChars)
            let meta = boundedOptionalField("meta", in: row, maxChars: maxSessionFieldChars)
            items.append(SlashCompletionItem(text: text, display: display, meta: meta))
        }
        let replaceFrom: Int
        if result["replace_from"] == nil {
            replaceFrom = 0
        } else if let raw = strictInt64Field("replace_from", in: result), raw >= 0, raw <= Int64(Int.max) {
            replaceFrom = min(Int(raw), inputLength)
        } else {
            throw ChatError.protocolError("Slash completion response was incomplete")
        }
        return SlashCompletionResult(items: items, replaceFrom: replaceFrom)
    }

    private func parseResumeResult(_ result: [String: Any], requestedDurableSessionID: String) throws -> ResumedChatSession {
        guard let runtimeSessionID = stringField("session_id", in: result),
              !runtimeSessionID.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw ChatError.protocolError("Resume response was incomplete")
        }
        let durableSessionID = stringField("session_key", in: result)
            .flatMap { $0.isEmpty ? nil : $0 }
        if let durableSessionID, durableSessionID != requestedDurableSessionID {
            throw ChatError.protocolError("Resume response referenced a different durable session")
        }
        let messages = (result["messages"] as? [[String: Any]]) ?? []
        let inflight = (result["inflight"] as? [String: Any]).map { value in
            InflightPrompt(
                user: value["user"] as? String,
                assistant: value["assistant"] as? String,
                streaming: value["streaming"] as? Bool ?? false
            )
        }
        let info = result["info"] as? [String: Any]
        return ResumedChatSession(
            runtimeSessionID: runtimeSessionID,
            durableSessionID: durableSessionID,
            resumed: (result["resumed"] as? Bool) ?? false,
            messages: messages,
            running: (result["running"] as? Bool) ?? false,
            inflight: inflight,
            model: info.flatMap { boundedOptionalField("model", in: $0, maxChars: maxEventNameChars) },
            provider: info.flatMap { boundedOptionalField("provider", in: $0, maxChars: maxEventNameChars) },
            reasoningEffort: info.flatMap { boundedOptionalField("reasoning_effort", in: $0, maxChars: maxEventNameChars) },
            fastMode: info.flatMap { boolField("fast", in: $0) }
        )
    }

    private func parseInteractionResponse(_ result: [String: Any]) throws -> ChatResponse {
        let wireStatus: String?
        if let status = stringField("status", in: result) {
            wireStatus = status
        } else if let resolved = boolField("resolved", in: result) {
            wireStatus = resolved ? "ok" : "expired"
        } else if let resolved = int64Field("resolved", in: result) {
            wireStatus = resolved > 0 ? "ok" : "expired"
        } else {
            wireStatus = nil
        }
        guard let wireStatus else {
            throw ChatError.protocolError("Hermes interaction response was incomplete")
        }
        return ChatResponse(status: ChatResponse.Status.fromWire(wireStatus), nextApproval: nil)
    }

    // MARK: - Field access helpers (JSONSerialization-tolerant)

    private func stringField(_ name: String, in object: [String: Any]) -> String? {
        object[name] as? String
    }

    private func optionalStringField(_ name: String, in object: [String: Any]) -> String? {
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

    private func boundedPercent(in object: [String: Any], aliases: [String]) -> Double? {
        for alias in aliases {
            if let value = finiteNumberField(alias, in: object) { return min(100, max(0, value)) }
        }
        return nil
    }

    private func boundedStringArray(_ raw: Any?, maxRows: Int) -> [String] {
        guard let rows = raw as? [Any] else { return [] }
        return rows.prefix(maxRows).compactMap { element in
            guard let value = element as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : String(trimmed.prefix(maxSessionFieldChars))
        }
    }

    private func firstBoundedText(in object: [String: Any], aliases: [String]) -> String? {
        for alias in aliases {
            if let value = boundedOptionalField(alias, in: object, maxChars: maxSessionFieldChars) {
                return value
            }
        }
        return nil
    }

    private func validModelField(_ name: String, in object: [String: Any], maxChars: Int) -> String? {
        guard let value = object[name] as? String else { return nil }
        return validModelValue(value, maxChars: maxChars)
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

    private func boundedChoices(in payload: [String: Any]) -> [String] {
        guard let raw = payload["choices"] as? [Any] else { return [] }
        var seen = Set<String>()
        var result: [String] = []
        for element in raw {
            guard let choice = element as? String else { continue }
            let trimmed = choice.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, trimmed.count <= maxEventChoiceChars, seen.insert(trimmed).inserted else { continue }
            result.append(trimmed)
            if result.count >= maxEventChoiceCount { break }
        }
        return result
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
