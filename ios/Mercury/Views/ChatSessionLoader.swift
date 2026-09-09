import SwiftUI
import MercuryCore

extension ChatView {
    // MARK: Open / resume

    @MainActor
    func open() async {
        if isNewSession {
            // Brand-new chat: no history exists, so skip the REST transcript
            // load entirely and go straight to the live connection.
            await connectAndResume()
            return
        }
        await loadTranscript(allowStandaloneRelayRead: true)
        await connectAndResume()
    }

    @discardableResult
    @MainActor
    func loadTranscript(
        durableSessionID requestedID: String? = nil,
        preservingActiveTurn: Bool = false,
        using liveConnection: ChatConnection? = nil,
        allowStandaloneRelayRead: Bool = false
    ) async -> Bool {
        let isRelay = appModel.activeRelayTarget != nil
        guard isRelay || appModel.serverOrigin != nil else {
            state.loadError = "No server connected."
            return false
        }
        let transcriptID = requestedID ?? sessionID
        guard !transcriptID.isEmpty else { return false }
        let expectedProgressVersion = state.progress.observationVersion
        let requestedScope = backgroundTaskScope
        let requestedSelection = appModel.relaySelectionGeneration
        let turnWasActive = preservingActiveTurn && (state.isSending || state.transcript.hasStreamingAssistant)
        if !preservingActiveTurn, let origin = appModel.serverOrigin {
            let cached = await appModel.cachedTranscript(
                origin: origin,
                profile: appModel.activeProfile,
                sessionID: transcriptID
            )
            if !cached.isEmpty {
                state.transcript.loadTranscript(cached.map {
                    TranscriptState.RestoredMessage(
                        role: $0.role.rawValue,
                        content: $0.text,
                        reasoningText: $0.reasoningText
                    )
                })
                state.initialScrollDone = true
            }
        }
        do {
            let fetched: [TranscriptMessage]
            let recoveredProgress: MercuryCore.DurableProgress
            var relayPage: RelayTranscriptPage?
            if isRelay {
                // Reads ride the live chat connection when one exists (it
                // already carries this session's lease). A standalone read
                // over the pool's default channel serves only the initial
                // pre-connect load; reconnects refresh from the resume
                // snapshot anyway.
                let live = liveConnection ?? state.connection
                if live == nil && !allowStandaloneRelayRead { return false }
                let page = try await relayTranscriptMessages(
                    transcriptID: transcriptID, limit: 100, offset: 0, using: live
                )
                relayPage = page
                fetched = page.messages
                recoveredProgress = page.progress
            } else {
                guard let origin = appModel.serverOrigin else { return false }
                let client = makeHTTPClient(origin: origin)
                let sessions = SessionsClient(client: client, profile: appModel.activeProfile)
                let page = try await sessions.transcriptWithProgress(sessionID: transcriptID)
                fetched = page.messages
                recoveredProgress = page.progress
            }
            guard !Task.isCancelled, backgroundTaskScope == requestedScope,
                  appModel.relaySelectionGeneration == requestedSelection else { return false }
            state.progress = state.progress.recover(history: recoveredProgress, expectedVersion: expectedProgressVersion)
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
                state.isSending = state.transcript.reconcileForegroundTranscript(
                    restored,
                    turnWasActive: turnWasActive
                )
            } else {
                state.transcript.loadTranscript(restored)
            }
            state.loadedTranscriptCount = relayPage?.nextOffset ?? history.count
            state.hasMoreHistory = relayPage?.hasMore ?? TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: history.count)
            state.historyError = nil
            await cacheCurrentTranscript()
            state.initialScrollDone = !state.transcript.rows.isEmpty
            state.loadError = nil
            return true
        } catch {
            // Keep whatever rendered; surface a banner. No secret material here.
            state.loadError = "Could not load transcript for this session."
            return false
        }
    }

    /// Fetches one transcript page over the relay's in-process read
    /// (`relay.session.transcript`, same shape as the REST endpoint). The
    /// live chat connection carries the read on its own lease channel;
    /// before one exists, the pool's default channel serves it.
    func relayTranscriptMessages(
        transcriptID: String,
        limit: Int,
        offset: Int,
        using live: ChatConnection?
    ) async throws -> RelayTranscriptPage {
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
        if let live {
            result = try await live.relayRequest("relay.session.transcript", params: params)
        } else {
            let short = try await RelayConnectionPool.shared.acquire(
                target: target, profile: appModel.activeProfile
            )
            result = try await short.relayRequest("relay.session.transcript", params: params)
        }
        return try RelayTranscriptPage.decode(result, offset: offset, limit: limit)
    }

    /// SwiftUI keeps this view alive while iOS backgrounds the app, so its
    /// initial task does not run again on reopen. Refresh the visible transcript
    /// explicitly and reset a dead socket's foreground reconnect budget.
    @MainActor
    func catchUpAfterForeground() async {
        guard state.didOpen, !state.closedByUs else { return }
        appModel.setVisibleSession(notificationSessionID)
        if !isNewSession {
            _ = await loadTranscript(
                durableSessionID: state.durableID ?? sessionID,
                preservingActiveTurn: true
            )
        }
        if state.connection == nil {
            retryConnectionNow(automatic: true)
        }
    }

    /// Prepends one older window of server-side transcript history. The
    /// reducer keeps row identity stable so the scroll anchor survives the
    /// insertion.
    func loadEarlierHistory() async {
        guard !state.isLoadingHistory, !(state.hasMoreHistory == false && state.historyError == nil) else { return }
        let isRelay = appModel.activeRelayTarget != nil
        guard isRelay || appModel.serverOrigin != nil else { return }
        let transcriptID = state.durableID ?? sessionID
        guard !transcriptID.isEmpty else { return }
        state.isLoadingHistory = true
        defer { state.isLoadingHistory = false }
        do {
            let fetchedOlder: [TranscriptMessage]
            var relayPage: RelayTranscriptPage?
            if isRelay {
                // Backfill only rides the live chat connection, which owns
                // this session's lease and its recovery cursor.
                guard let live = state.connection else { return }
                let page = try await relayTranscriptMessages(
                    transcriptID: transcriptID,
                    limit: TranscriptHistoryPolicy.pageSize,
                    offset: TranscriptHistoryPolicy.nextOffset(loadedCount: state.loadedTranscriptCount),
                    using: live
                )
                relayPage = page
                fetchedOlder = page.messages
            } else {
                guard let origin = appModel.serverOrigin else { return }
                let client = makeHTTPClient(origin: origin)
                let sessions = SessionsClient(client: client, profile: appModel.activeProfile)
                fetchedOlder = try await sessions.olderTranscript(
                    sessionID: transcriptID,
                    offset: TranscriptHistoryPolicy.nextOffset(loadedCount: state.loadedTranscriptCount)
                )
            }
            let older = TranscriptPageOrdering.forDisplay(fetchedOlder)
            if let page = relayPage {
                state.loadedTranscriptCount = page.nextOffset
                state.hasMoreHistory = page.hasMore
            }
            guard !older.isEmpty else {
                if relayPage == nil { state.hasMoreHistory = false }
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
            state.transcript.prependHistory(restored)
            if relayPage == nil {
                state.loadedTranscriptCount += older.count
                state.hasMoreHistory = TranscriptHistoryPolicy.hasMoreHistory(fetchedCount: older.count)
            }
            state.historyError = nil
        } catch {
            state.historyError = "Could not load earlier messages. Tap to retry."
        }
    }

    func cacheCurrentTranscript() async {
        guard let origin = appModel.serverOrigin else { return }
        let durableSessionID = state.durableID ?? sessionID
        guard !durableSessionID.isEmpty else { return }
        let summary = appModel.sessions.first(where: { $0.id == durableSessionID })
            ?? SessionRow(
                id: durableSessionID,
                title: state.titleText,
                preview: state.transcript.rows.last?.text ?? "",
                profile: appModel.activeProfile
            )
        let messages = state.transcript.rows.compactMap { row -> OfflineCachedMessage? in
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

    @MainActor
    func loadProcessRows() async {
        guard let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else { return }
        let scope = backgroundTaskScope
        let client = OperationsClient(request: { method, params in
            try await connection.operationsRequest(method, params: params)
        })
        let rows = (try? await client.listProcesses(runtimeSessionID: runtimeSessionID)) ?? []
        guard state.connection === connection, backgroundTaskScope == scope else { return }
        state.processRows = rows
    }
}
