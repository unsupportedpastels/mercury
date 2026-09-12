import SwiftUI

extension ChatView {
    // MARK: M7 model, context, and completion controls

    func openModelPicker() {
        guard state.modelFeatureSupported else {
            state.composerError = "Session model controls are not supported by this server."
            return
        }
        guard state.connection != nil, state.runtimeSessionID != nil else {
            state.composerError = "Not connected — reopen this session to choose a model."
            return
        }
        state.showModelPicker = true
        loadModelOptions()
    }

    func loadModelOptions() {
        guard state.modelFeatureSupported, let connection = state.connection,
              let runtimeSessionID = state.runtimeSessionID else { return }
        state.modelPickerLoading = true
        state.modelPickerError = nil
        Task {
            do {
                let loaded = try await connection.loadModelOptions(runtimeSessionID: runtimeSessionID)
                await MainActor.run {
                    guard state.connection === connection else { return }
                    state.applyModelOptions(loaded)
                    state.modelPickerLoading = false
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.modelFeatureSupported = false
                    state.modelPickerLoading = false
                    state.showModelPicker = false
                }
            } catch {
                await MainActor.run {
                    state.modelPickerLoading = false
                    state.modelPickerError = "Could not load models for this session."
                }
            }
        }
    }

    /// Hydrates the session catalog during the initial resume/create lifecycle.
    /// The caller supplies the already-admitted connection so this cannot open
    /// a second Relay channel or implicitly attach to another runtime.
    @MainActor
    func hydrateModelOptions(
        using connection: ChatConnection,
        runtimeSessionID: String
    ) async throws -> ModelOptions? {
        guard state.modelFeatureSupported else { return nil }
        do {
            // ChatConnection bounds this auxiliary RPC and removes its pending
            // continuation on timeout; metadata failure must not wedge chat.
            let loaded = try await connection.loadModelOptions(runtimeSessionID: runtimeSessionID)
            guard state.connection === connection else { return nil }
            state.applyModelOptions(loaded)
            return loaded
        } catch is ChatMethodNotFoundError {
            state.modelFeatureSupported = false
            return nil
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // Model metadata is auxiliary to the live chat. A transient catalog
            // failure leaves the session usable and the picker can retry later.
            return nil
        }
    }

    func applyModel(_ selection: ModelSelection, confirmed: Bool) {
        guard let options = state.modelOptions,
              options.providers.contains(where: { $0.slug == selection.provider && $0.models.contains(selection.model) }),
              let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else {
            state.modelPickerError = "That model is not in the server's advertised catalog."
            return
        }
        state.modelPickerApplying = true
        state.modelPickerError = nil
        Task {
            do {
                let result = try await connection.setModel(
                    runtimeSessionID: runtimeSessionID,
                    provider: selection.provider,
                    model: selection.model,
                    confirmExpensiveModel: confirmed
                )
                await MainActor.run {
                    state.modelPickerApplying = false
                    if result.confirmationRequired {
                        state.pendingModelConfirmation = PendingModelConfirmation(
                            selection: selection,
                            message: result.confirmationMessage ?? "The server requires confirmation for this model."
                        )
                        state.showModelPicker = false
                    } else if result.accepted {
                        state.applyModelSelection(selection)
                        state.showModelPicker = false
                        state.composerNotice = result.deferred
                            ? "Model will change after the active turn."
                            : "Session model updated."
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.modelFeatureSupported = false
                    state.modelPickerApplying = false
                    state.showModelPicker = false
                }
            } catch {
                await MainActor.run {
                    state.modelPickerApplying = false
                    state.modelPickerError = "Could not change the session model."
                }
            }
        }
    }

    func applyReasoning(_ effort: String) {
        guard let canonical = ReasoningEffort.canonical(effort),
              state.modelFeatureSupported,
              let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else {
            state.composerError = "Reasoning could not be changed."
            return
        }
        state.isComposerActionPending = true
        state.modelPickerApplying = true
        Task {
            do {
                let catalog: ModelOptions
                if let existing = state.modelOptions {
                    catalog = existing
                } else {
                    catalog = try await connection.loadModelOptions(runtimeSessionID: runtimeSessionID)
                }
                guard SessionModelPickerPolicy.capabilities(
                    in: catalog,
                    for: state.currentModelSelection ?? catalog.current
                )?.reasoning == true else {
                    await MainActor.run {
                        state.applyModelOptions(catalog)
                        state.isComposerActionPending = false
                        state.modelPickerApplying = false
                        state.composerError = "The selected model does not explicitly advertise reasoning support."
                    }
                    return
                }
                try await connection.setReasoning(runtimeSessionID: runtimeSessionID, effort: canonical)
                await MainActor.run {
                    state.applyModelOptions(catalog)
                    state.currentReasoningEffort = canonical
                    state.isComposerActionPending = false
                    state.modelPickerApplying = false
                    state.composerError = nil
                    state.composerNotice = "Reasoning set to \(canonical)."
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.modelFeatureSupported = false
                    state.showModelPicker = false
                    state.isComposerActionPending = false
                    state.modelPickerApplying = false
                    state.composerError = "Session model controls are not supported by this server."
                }
            } catch {
                await MainActor.run {
                    state.isComposerActionPending = false
                    state.modelPickerApplying = false
                    state.composerError = "Could not change reasoning effort."
                }
            }
        }
    }

    func applyFast(_ enabled: Bool) {
        guard state.currentModelCapabilities?.fast == true,
              let connection = state.connection,
              let runtimeSessionID = state.runtimeSessionID else {
            state.composerError = "Fast mode is unavailable for this model."
            return
        }
        state.isComposerActionPending = true
        Task {
            do {
                try await connection.setFast(runtimeSessionID: runtimeSessionID, enabled: enabled)
                await MainActor.run {
                    guard state.connection === connection else { return }
                    state.currentFastMode = enabled
                    state.isComposerActionPending = false
                    state.composerNotice = enabled ? "Fast mode enabled." : "Normal mode enabled."
                }
            } catch {
                await MainActor.run {
                    state.isComposerActionPending = false
                    state.composerError = "Could not change fast mode."
                }
            }
        }
    }

    func openContextSheet() {
        // Saved activity is available even without a live controller. Only the
        // usage/maintenance requests below require a connected runtime.
        state.showContextSheet = true
        state.contextStatus = nil
        loadContext()
    }

    /// Explicit-only sequential usage → breakdown loading for this exact live runtime.
    func loadContext() {
        guard let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else { return }
        state.contextGeneration += 1
        let generation = state.contextGeneration
        state.contextLoading = true
        state.contextError = nil
        Task {
            var loadedUsage: SessionUsage?
            var loadedBreakdown: SessionContextBreakdown?
            var errorMessage: String?

            if state.usageSupported {
                do {
                    loadedUsage = try await connection.loadSessionUsage(runtimeSessionID: runtimeSessionID)
                } catch is ChatMethodNotFoundError {
                    await MainActor.run { state.usageSupported = false }
                } catch {
                    errorMessage = "Could not load session usage."
                }
            }
            if state.breakdownSupported {
                do {
                    loadedBreakdown = try await connection.loadContextBreakdown(runtimeSessionID: runtimeSessionID)
                } catch is ChatMethodNotFoundError {
                    await MainActor.run { state.breakdownSupported = false }
                } catch {
                    if errorMessage == nil { errorMessage = "Could not load context breakdown." }
                }
            }

            await MainActor.run {
                guard state.connection === connection, generation == state.contextGeneration else { return }
                if let loadedUsage { state.sessionUsage = loadedUsage }
                if let loadedBreakdown { state.contextBreakdown = loadedBreakdown }
                state.contextError = errorMessage
                state.contextLoading = false
            }
        }
    }

    func compressContext() {
        guard !state.turnInFlight, state.compressSupported, let connection = state.connection,
              let runtimeSessionID = state.runtimeSessionID else { return }
        state.contextBusy = true
        state.contextError = nil
        state.contextStatus = nil
        Task {
            do {
                let result = try await connection.compressSession(runtimeSessionID: runtimeSessionID)
                await MainActor.run {
                    state.contextBusy = false
                    if result.status == "compressed" {
                        state.transcript.loadTranscript(transcriptRows(from: result.messages))
                        if let usage = result.usage { state.sessionUsage = usage }
                        state.contextStatus = "Context compressed."
                    } else {
                        state.contextError = "Context was not compressed."
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.compressSupported = false
                    state.contextBusy = false
                }
            } catch {
                await MainActor.run {
                    state.contextBusy = false
                    state.contextError = "Could not compress context."
                }
            }
        }
    }

    func undoLastTurn() {
        guard !state.turnInFlight, state.undoSupported, let connection = state.connection,
              let runtimeSessionID = state.runtimeSessionID else { return }
        let transcriptID = state.durableID ?? sessionID
        guard !transcriptID.isEmpty else {
            state.contextError = "This session has not been stored yet."
            return
        }
        state.contextBusy = true
        state.contextError = nil
        state.contextStatus = nil
        Task {
            do {
                _ = try await connection.undoSession(runtimeSessionID: runtimeSessionID)
                let reloaded = await loadTranscript(durableSessionID: transcriptID)
                await MainActor.run {
                    state.contextBusy = false
                    if reloaded {
                        state.contextStatus = "Last turn undone."
                    } else {
                        state.contextError = "The turn was undone, but the transcript could not be reloaded."
                    }
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.undoSupported = false
                    state.contextBusy = false
                }
            } catch {
                await MainActor.run {
                    state.contextBusy = false
                    state.contextError = "Could not undo the last turn."
                }
            }
        }
    }

    func branchSession(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !state.turnInFlight, state.branchSupported, !trimmed.isEmpty,
              let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else { return }
        state.contextBusy = true
        state.contextError = nil
        state.contextStatus = nil
        Task {
            do {
                let result = try await connection.branchSession(
                    runtimeSessionID: runtimeSessionID,
                    count: nil,
                    name: trimmed
                )
                await MainActor.run {
                    state.contextBusy = false
                    let branchTitle = result.title ?? trimmed
                    state.branchDestination = BranchDestination(
                        durableID: result.durableSessionID,
                        title: branchTitle
                    )
                    state.showContextSheet = false
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.branchSupported = false
                    state.contextBusy = false
                }
            } catch {
                await MainActor.run {
                    state.contextBusy = false
                    state.contextError = "Could not branch this session."
                }
            }
        }
    }

    func clearSlashCompletion() {
        state.slashCompletionTask?.cancel()
        state.slashCompletionTask = nil
        state.slashGeneration += 1
        state.slashItems = []
    }

    func scheduleSlashCompletion(for text: String) {
        state.slashCompletionTask?.cancel()
        state.slashGeneration += 1
        let generation = state.slashGeneration
        guard state.slashCompletionSupported,
              M7ComposerPolicy.shouldRequestSlashCompletion(
                text: text,
                connectionIsLive: !state.connectionStateIsNotLive && state.connection != nil
              ),
              let connection = state.connection else {
            state.slashItems = []
            return
        }

        state.slashCompletionTask = Task {
            do {
                try await Task.sleep(nanoseconds: 60_000_000)
                try Task.checkCancellation()
                let result = try await connection.completeSlash(text: text)
                try Task.checkCancellation()
                await MainActor.run {
                    guard state.connection === connection,
                          M7ComposerPolicy.mayPublishSlashCompletion(
                            responseGeneration: generation,
                            currentGeneration: state.slashGeneration
                          ) else { return }
                    state.slashItems = result.items
                    state.slashReplaceFrom = result.replaceFrom
                }
            } catch is ChatMethodNotFoundError {
                await MainActor.run {
                    state.slashCompletionSupported = false
                    state.slashItems = []
                }
            } catch {
                await MainActor.run {
                    if generation == state.slashGeneration { state.slashItems = [] }
                }
            }
        }
    }

    func transcriptRows(from messages: [[String: Any]]) -> [TranscriptState.RestoredMessage] {
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
                reasoningText: reasoning, displayKind: message["display_kind"] as? String
            )
        }
    }
}
