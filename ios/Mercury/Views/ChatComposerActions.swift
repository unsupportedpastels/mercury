import SwiftUI

extension ChatView {
    // MARK: Sending

    func sendDraft() {
        guard !state.isComposerActionPending else { return }
        let action = M7ComposerPolicy.route(
            draft: state.draft,
            turnActive: state.turnInFlight,
            hasAttachments: !state.stagedAttachments.isEmpty || !state.stagedHostReferences.isEmpty
        )
        state.composerNotice = nil

        switch action {
        case .openModelPicker:
            state.draft = ""
            clearSlashCompletion()
            openModelPicker()

        case .setReasoning(let effort):
            state.draft = ""
            clearSlashCompletion()
            applyReasoning(effort)

        case .steer(let text):
            steerActiveTurn(text)

        case .queue(let text):
            queueAfterActiveTurn(text)

        case .submit(let text):
            submitPrompt(text)

        case .reject(let rejection):
            switch rejection {
            case .blankPrompt:
                break
            case .blankSteer:
                state.composerError = "Enter guidance after /steer."
            case .noActiveTurnToSteer:
                state.composerError = "There is no active turn to steer."
            case .attachmentsUnavailableWhileSteering:
                state.composerError = "Attachments are unavailable while steering an active turn."
            }
        }
    }

    private func queueAfterActiveTurn(_ text: String) {
        guard let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else {
            state.composerError = "Not connected — reopen this session to queue a message."
            return
        }
        guard let attempt = state.queuedPromptSubmission.begin(draft: text) else { return }

        state.draft = ""
        clearSlashCompletion()
        state.composerError = nil
        state.isComposerActionPending = true
        Task {
            do {
                _ = try await connection.submitPrompt(
                    runtimeSessionID: runtimeSessionID,
                    text: text,
                    queued: true
                )
                await MainActor.run {
                    guard state.queuedPromptSubmission.resolve(attempt: attempt, accepted: true) != .stale else { return }
                    state.isComposerActionPending = false
                    state.composerNotice = "Message queued for after the active turn."
                }
            } catch {
                await MainActor.run {
                    let resolution = state.queuedPromptSubmission.resolve(attempt: attempt, accepted: false)
                    guard case .restoreDraft(let original) = resolution else { return }
                    state.isComposerActionPending = false
                    state.composerError = "Could not queue message — check the connection and try again."
                    if state.draft.isEmpty { state.draft = original }
                }
            }
        }
    }

    private func steerActiveTurn(_ text: String) {
        guard state.steerSupported, let connection = state.connection,
              let runtimeSessionID = state.runtimeSessionID else {
            state.composerError = "Not connected — reopen this session to steer."
            return
        }
        let originalDraft = state.draft
        state.draft = ""
        clearSlashCompletion()
        state.composerError = nil
        state.isComposerActionPending = true
        Task {
            do {
                let result = try await connection.steerSession(runtimeSessionID: runtimeSessionID, text: text)
                await MainActor.run {
                    state.isComposerActionPending = false
                    switch result.status {
                    case .queued:
                        state.composerNotice = "Guidance queued for the active turn."
                    case .rejected:
                        state.composerError = "Could not steer active turn."
                        if state.draft.isEmpty { state.draft = originalDraft }
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.steerSupported = false
                    state.isComposerActionPending = false
                    state.composerError = "Active-turn steering is not supported by this server."
                    if state.draft.isEmpty { state.draft = originalDraft }
                }
            } catch {
                await MainActor.run {
                    state.isComposerActionPending = false
                    state.composerError = "Could not steer active turn."
                    if state.draft.isEmpty { state.draft = originalDraft }
                }
            }
        }
    }

    func clearStagedHostReferences(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        let idSet = Set(ids)
        state.stagedHostReferences.removeAll { idSet.contains($0.id) }
    }

    private func submitPrompt(_ trimmed: String) {
        guard let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else {
            state.composerError = "Not connected — reopen this session to chat."
            return
        }

        let hostReferences = state.stagedHostReferences
        guard let attempt = state.promptSubmission.begin(
            draft: trimmed,
            hostReferenceIDs: hostReferences.map(\.id)
        ) else { return }

        let progressBeforeTurn = state.progress
        state.progress = state.progress.beginTurn(atEpochMillis: Int64(Date().timeIntervalSince1970 * 1000))
        let resetVersion = state.progress.observationVersion
        state.latestStatusKind = nil
        let attachments = state.stagedAttachments
        let bytes = state.stagedBytes
        state.draft = ""
        clearSlashCompletion()
        state.composerError = nil
        state.isSending = true
        state.isComposerActionPending = true
        state.followBottom = true
        Task {
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
                        let resolution = state.promptSubmission.resolve(attempt: attempt, accepted: false)
                        guard resolution != .stale else { return }
                        state.isSending = false
                        state.isComposerActionPending = false
                        if case .restoreDraft(let original) = resolution {
                            state.progress = state.progress.restoreUnstartedTurn(previous: progressBeforeTurn, resetVersion: resetVersion)
                            if state.draft.isEmpty { state.draft = original }
                        }
                    }
                    return
                }
                await MainActor.run {
                    state.transcript.appendUserMessage(prompt)
                    state.stagedAttachments = []
                    state.stagedBytes = [:]
                }
                _ = try await connection.submitPrompt(runtimeSessionID: runtimeSessionID, text: prompt)
                // A successful acknowledgement clears only references owned
                // by this attempt. A stale callback has no newer UI effect.
                await MainActor.run {
                    let resolution = state.promptSubmission.resolve(attempt: attempt, accepted: true)
                    if case .accepted(let referenceIDs) = resolution {
                        clearStagedHostReferences(referenceIDs)
                    }
                    guard resolution != .stale else { return }
                    state.isComposerActionPending = false
                }
            } catch {
                await MainActor.run {
                    let resolution = state.promptSubmission.resolve(attempt: attempt, accepted: false)
                    guard resolution != .stale else { return }
                    switch resolution {
                    case .restoreDraft(let original):
                        state.progress = state.progress.restoreUnstartedTurn(previous: progressBeforeTurn, resetVersion: resetVersion)
                        state.composerError = "Send failed — check the connection and try again."
                        state.isSending = false
                        state.isComposerActionPending = false
                        // Do not overwrite text the user entered for a newer
                        // action while this attempt was awaiting its ACK.
                        if state.draft.isEmpty { state.draft = original }
                    case .authoritativeTerminal:
                        // The terminal event already settled the turn. A late
                        // RPC failure is not permission to restore the draft or
                        // surface a second, stale send error.
                        state.isComposerActionPending = false
                    case .accepted:
                        state.isComposerActionPending = false
                    case .stale:
                        break
                    }
                }
            }
        }
    }

    func applyIncomingShare() {
        guard !state.incomingShareApplied, let incomingShare else { return }
        state.incomingShareApplied = true
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
    func stageAttachment(filename: String, mimeType: String?, data: Data) {
        guard !state.turnInFlight else {
            state.composerError = "Attachments are unavailable while steering an active turn."
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
        if state.stagedAttachments.contains(where: {
            $0.displayName == sanitized && $0.sizeBytes == candidate.sizeBytes
        }) {
            state.composerError = "\(sanitized) is already attached"
            return
        }
        switch AttachmentPolicy.checkAdd(existing: state.stagedAttachments, candidate: candidate) {
        case .accepted:
            state.stagedAttachments.append(candidate)
            state.stagedBytes[candidate.id] = data
            state.composerError = nil
        case .rejected(let reason):
            state.composerError = reason
        }
    }

    /// Stages only a reference produced from a server-returned HostFileEntry.
    /// Selection never reads bytes, uploads content, or sends a prompt.
    func stageHostReference(_ entry: HostFileEntry) {
        guard !state.turnInFlight else {
            state.composerError = "Host references are unavailable while steering an active turn."
            return
        }
        do {
            let reference = try StagedHostReference(entry: entry)
            guard !state.stagedHostReferences.contains(where: { $0.id == reference.id }) else {
                state.composerError = "\(entry.name) is already referenced"
                return
            }
            state.stagedHostReferences.append(reference)
            state.composerError = nil
        } catch {
            state.composerError = "This server path cannot be referenced safely."
        }
    }

    func interruptTurn() {
        guard !state.isStopping, let connection = state.connection,
              let runtimeSessionID = state.runtimeSessionID else { return }
        state.isStopping = true
        state.composerError = nil
        Task {
            do {
                let response = try await connection.interruptSession(runtimeSessionID: runtimeSessionID)
                if response.status != .ok && response.status != .interrupted {
                    await MainActor.run {
                        state.isStopping = false
                        state.composerError = "Could not stop the active response."
                    }
                }
            } catch {
                await MainActor.run {
                    state.isStopping = false
                    state.composerError = "Could not stop the active response."
                }
            }
        }
    }
}
