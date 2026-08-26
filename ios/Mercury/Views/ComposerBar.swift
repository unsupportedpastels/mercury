import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import Combine

/// Message composer: vertical-axis text field + send button.
///
/// Send policy: normal submission blocks until streaming begins, while an
/// active turn switches to a visibly distinct steering mode and remains
/// text-sendable. Attachments are unavailable in steering mode. Errors
/// surface through a bound banner string owned by the parent screen.
///
/// Attachment UI (M6.3): a paperclip menu offers Photo Library and Choose
/// File. The view is transport/policy-free — picked bytes are handed straight
/// to `onAttachmentPicked(filename, mimeType, data)`; staging, admission
/// checks, and upload remain the caller's job (mirroring the Android split
/// between the composer UI and `AttachmentPolicy`/`AttachmentStager`).
struct ComposerBar: View {
    @Binding var draft: String
    @Binding var errorMessage: String?
    var noticeMessage: String? = nil
    let isSending: Bool
    let onSend: () -> Void
    var showStop = false
    var isStopping = false
    var onStop: (() -> Void)? = nil
    /// Active assistant turn: text remains sendable and routes to session.steer.
    var isSteering: Bool = false
    /// Disabled while steering so bytes can never enter the active-turn path.
    var attachmentsEnabled: Bool = true

    // MARK: Attachment parameters — every one defaulted so existing call sites compile unchanged.

    /// Staged attachments to render above the text field.
    var attachments: [StagedAttachment] = []
    /// Called with (suggestedFilename, mimeTypeIfKnown, bytes) once a pick completes.
    var onAttachmentPicked: ((String, String?, Data) -> Void)? = nil
    /// Called with the attachment id when the user removes a chip.
    var onRemoveAttachment: ((String) -> Void)? = nil
    /// Called with a human-readable message when a pick fails to load.
    var onAttachmentError: ((String) -> Void)? = nil
    /// Canonical host references are metadata, never uploaded attachment bytes.
    var hostReferences: [StagedHostReference] = []
    var onHostReferencePicked: ((HostFileEntry) -> Void)? = nil
    var onRemoveHostReference: ((String) -> Void)? = nil
    var dictation: ComposerDictationCoordinator? = nil
    var modelLabel: String? = nil
    var reasoningEffort: String? = nil
    var reasoningSupported = false
    var fastSupported = false
    var fastEnabled = false
    var contextPercent: Double? = nil
    var metadataControlsEnabled = false
    var onOpenModelPicker: (() -> Void)? = nil
    var onReasoningSelected: ((String) -> Void)? = nil
    var onFastSelected: ((Bool) -> Void)? = nil
    var onOpenContext: (() -> Void)? = nil

    @State private var pendingPhotoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var showFileImporter = false
    @State private var showHostFiles = false
    @State private var dictationActive = false
    @FocusState private var composerFocused: Bool

    var body: some View {
        VStack(spacing: 6) {
            if let noticeMessage {
                Label(noticeMessage, systemImage: "checkmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.accentPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
            }

            if let errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
            }


            if !attachments.isEmpty && !isSteering {
                AttachmentChipRow(
                    attachments: attachments,
                    onRemove: onRemoveAttachment
                )
            }

            if !hostReferences.isEmpty && !isSteering {
                HostReferenceChipRow(
                    references: hostReferences,
                    onRemove: onRemoveHostReference
                )
            }

            HStack(alignment: .center, spacing: 4) {
                if onAttachmentPicked != nil && attachmentsEnabled && !isSteering {
                    attachmentMenu
                }

                TextField("Message Hermes", text: $draft, axis: .vertical)
                    .focused($composerFocused)
                    .lineLimit(1...5)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 10)
                    .submitLabel(.send)
                    // Keep the focused editor mounted and enabled while a
                    // submission transitions into streaming. Disabling a
                    // focused SwiftUI TextField resigns first responder, then
                    // restores it when steering becomes available, which makes
                    // the keyboard hide and reappear without user input. The
                    // send action remains gated by `canSend` below.
                    .onSubmit(send)

                if let dictation {
                    DictationButton(dictation: dictation, enabled: !isSteering)
                }

                Button(action: primaryAction) {
                    Image(systemName: showStop ? "xmark" : "arrow.up")
                        .font(.body.weight(.bold))
                        .frame(width: 40, height: 40)
                        .background(primaryActionBackground, in: Circle())
                        .foregroundStyle(primaryActionForeground)
                }
                .disabled(!primaryActionEnabled)
                .accessibilityLabel(showStop ? "Stop Hermes response" : (isSteering ? "Steer active turn" : "Send message"))
                .accessibilityValue(showStop ? (isStopping ? "Stopping" : "Ready to stop") : "")
            }
            .padding(6)
            .background(
                Color.composerSurface,
                in: RoundedRectangle(cornerRadius: 30, style: .continuous)
            )

            if showsMetadataStrip {
                metadataStrip
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .simultaneousGesture(
            DragGesture(minimumDistance: 12)
                .onChanged { value in
                    guard composerFocused,
                          value.translation.height > 16,
                          abs(value.translation.height) > abs(value.translation.width) else { return }
                    composerFocused = false
                }
        )
        .task(id: pendingPhotoItem) {
            await loadPendingPhoto()
        }
        .onReceive(dictationStatePublisher) { state in
            switch state {
            case .requestingPermission, .recording:
                dictationActive = true
            case .idle, .failed:
                dictationActive = false
            }
        }
        .onChange(of: isSending) {
            // The send button releases focus synchronously, but the parent
            // also changes isSending in the same transaction. On an
            // intermittent SwiftUI path that transaction can re-assert the
            // TextField's first responder after the button action returns.
            // Treat the transition into submission as a second idempotent
            // focus boundary while keeping the editor mounted and enabled.
            if isSending {
                composerFocused = false
            }
        }
        .photosPicker(
            isPresented: $showPhotoPicker,
            selection: $pendingPhotoItem,
            matching: .images
        )
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false,
            onCompletion: handleFileImport
        )
        .sheet(isPresented: $showHostFiles) {
            NavigationStack {
                HostFilesView(mode: .chatReference, onSelectReference: { entry in
                    onHostReferencePicked?(entry)
                })
            }
        }
    }

    // MARK: - Attachment menu

    private var attachmentMenu: some View {
        Menu {
            Button {
                showPhotoPicker = true
            } label: {
                Label("Photo Library", systemImage: "photo.on.rectangle")
            }
            Button {
                showFileImporter = true
            } label: {
                Label("Choose File", systemImage: "folder")
            }
            if onHostReferencePicked != nil {
                Button {
                    showHostFiles = true
                } label: {
                    Label("Reference Host File", systemImage: "externaldrive")
                }
            }
        } label: {
            Image(systemName: "plus")
                .font(.title3)
                .foregroundStyle(Color.composerSecondaryContent)
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel("Attach files")
    }

    private var showsMetadataStrip: Bool {
        onOpenModelPicker != nil || onReasoningSelected != nil || onFastSelected != nil || onOpenContext != nil
    }

    private var metadataStrip: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                if let onOpenModelPicker {
                    Button(action: onOpenModelPicker) {
                        HStack(spacing: 5) {
                            Text(modelLabel ?? "Model")
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Image(systemName: "chevron.down")
                                .font(.caption2)
                        }
                        .composerMetadataChip()
                    }
                    .disabled(!metadataControlsEnabled)
                    .accessibilityLabel("Change session model")
                }

                if reasoningSupported, let onReasoningSelected {
                    Menu {
                        ForEach(ReasoningEffort.allCases, id: \.rawValue) { effort in
                            Button {
                                onReasoningSelected(effort.rawValue)
                            } label: {
                                if effort.rawValue == reasoningEffort {
                                    Label(effort.rawValue.capitalized, systemImage: "checkmark")
                                } else {
                                    Text(effort.rawValue.capitalized)
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 5) {
                            Text(reasoningEffort?.capitalized ?? "Reasoning")
                            Image(systemName: "chevron.down")
                                .font(.caption2)
                        }
                        .composerMetadataChip()
                    }
                    .disabled(!metadataControlsEnabled)
                    .accessibilityLabel("Change reasoning effort")
                }

                if fastSupported, let onFastSelected {
                    Menu {
                        Button {
                            onFastSelected(true)
                        } label: {
                            if fastEnabled {
                                Label("Fast", systemImage: "checkmark")
                            } else {
                                Text("Fast")
                            }
                        }
                        Button {
                            onFastSelected(false)
                        } label: {
                            if !fastEnabled {
                                Label("Normal", systemImage: "checkmark")
                            } else {
                                Text("Normal")
                            }
                        }
                    } label: {
                        Image(systemName: "speedometer")
                            .foregroundStyle(fastEnabled ? Color.accentPrimary : Color.secondary)
                            .frame(width: 36, height: 36)
                    }
                    .disabled(!metadataControlsEnabled)
                    .accessibilityLabel("Change fast mode")
                    .accessibilityValue(fastEnabled ? "Fast" : "Normal")
                }

                }
            }

            if let onOpenContext {
                ContextUsageRing(percent: contextPercent, action: onOpenContext)
            }
        }
    }

    // MARK: - Photo Library loading (async)

    /// Runs whenever `pendingPhotoItem` changes. The picker item's transferable
    /// load is async, so it happens here rather than in a button action.
    private func loadPendingPhoto() async {
        guard let item = pendingPhotoItem else { return }
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                onAttachmentError?("Could not read the selected photo.")
                return
            }
            onAttachmentPicked?(
                suggestedPhotoName(for: item),
                photoMimeType(for: item),
                data
            )
        } catch is CancellationError {
            // Selection replaced/cancelled mid-load; not an error to surface.
        } catch {
            onAttachmentError?(error.localizedDescription)
        }
        // Reset so picking the identical photo again re-fires `.task(id:)`.
        // Safe to mutate here: this task has already finished its await.
        pendingPhotoItem = nil
    }

    private func suggestedPhotoName(for item: PhotosPickerItem) -> String {
        let ext = item.supportedContentTypes.first?.preferredFilenameExtension
        return ext.map { "photo.\($0)" } ?? "photo.jpg"
    }

    private func photoMimeType(for item: PhotosPickerItem) -> String? {
        item.supportedContentTypes.first?.preferredMIMEType
    }

    // MARK: - File importer

    private func handleFileImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            // User-cancel arrives as an NSCocoa user-cancelled error here
            // (not a Swift CancellationError) — never surface it as failure.
            let nsError = error as NSError
            let isUserCancel = nsError.domain == NSCocoaErrorDomain
                && nsError.code == NSUserCancelledError
            if !isUserCancel {
                onAttachmentError?(error.localizedDescription)
            }
        case .success(let urls):
            guard let url = urls.first else { return }
            let secured = url.startAccessingSecurityScopedResource()
            defer { if secured { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let mime = fileMimeType(at: url)
                onAttachmentPicked?(url.lastPathComponent, mime, data)
            } catch {
                onAttachmentError?(error.localizedDescription)
            }
        }
    }

    private func fileMimeType(at url: URL) -> String? {
        let resourceType = try? url.resourceValues(forKeys: [.contentTypeKey]).contentType
        return resourceType?.preferredMIMEType
            ?? UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
    }

    // MARK: - Send

    private var canSend: Bool {
        ComposerSendPolicy.canSend(
            draft: draft,
            isSending: isSending,
            dictationActive: dictationActive,
            isSteering: isSteering,
            hasAttachments: !attachments.isEmpty,
            hasHostReferences: !hostReferences.isEmpty
        )
    }

    private var primaryActionEnabled: Bool {
        showStop ? !isStopping && onStop != nil : canSend
    }

    private var primaryActionBackground: Color {
        guard primaryActionEnabled else { return Color.white.opacity(0.10) }
        return showStop ? .composerActive : .composerPrimary
    }

    private var primaryActionForeground: Color {
        guard primaryActionEnabled else { return Color.white.opacity(0.38) }
        return showStop ? .composerOnActive : .composerOnPrimary
    }

    private var dictationStatePublisher: AnyPublisher<DictationState, Never> {
        if let dictation { return dictation.$state.eraseToAnyPublisher() }
        return Just(DictationState.idle).eraseToAnyPublisher()
    }

    private func send() {
        guard canSend else { return }
        // Submission is an explicit user boundary: release first responder
        // before the parent starts changing transcript/layout state. Keeping
        // this in the stable composer view avoids a keyboard-dismiss/re-focus
        // race when isSending changes during the first streamed event.
        composerFocused = false
        withAnimation { errorMessage = nil }
        onSend()
    }

    private func primaryAction() {
        if showStop {
            guard !isStopping else { return }
            onStop?()
        } else {
            send()
        }
    }
}

private extension View {
    func composerMetadataChip() -> some View {
        self
            .font(.subheadline.weight(.medium))
            .foregroundStyle(Color.primary)
            .padding(.horizontal, 12)
            .frame(height: 36)
            .background(Color.surfaceHigh, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct ContextUsageRing: View {
    let percent: Double?
    let action: () -> Void

    private var fraction: Double {
        min(1, max(0, (percent ?? 0) / 100))
    }

    private var ringColor: Color {
        guard let percent else { return .secondary }
        return percent >= 90 ? .statusAlert : .accentPrimary
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .stroke(Color.surfaceHigh, lineWidth: 3)
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(ringColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(percent.map { String(Int(min(99, max(0, $0)))) } ?? "–")
                    .font(.system(size: 9, weight: .medium, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(Color.primary)
            }
            .frame(width: 32, height: 32)
            .frame(width: 40, height: 40)
        }
        .accessibilityLabel("Open session details")
        .accessibilityValue(
            percent.map { "Context \(Int(max(0, $0))) percent used" }
                ?? "Context usage unknown"
        )
    }
}

#if DEBUG
struct ComposerBarPreview: View {
    @State private var draft = ""
    @State private var error: String?

    var body: some View {
        VStack {
            Spacer()
            ComposerBar(draft: $draft, errorMessage: $error, isSending: false) {
                draft = ""
            }
        }
    }
}

struct ComposerBarAttachmentsPreview: View {
    @State private var draft = ""

    var body: some View {
        VStack {
            Spacer()
            ComposerBar(
                draft: $draft,
                errorMessage: .constant(nil),
                isSending: false,
                onSend: { draft = "" },
                attachments: [
                    StagedAttachment(id: "a", displayName: "diagram-flow.png", mimeType: "image/png", sizeBytes: 245_760),
                    StagedAttachment(id: "b", displayName: "quarterly-report-final-v2.pdf", mimeType: "application/pdf", sizeBytes: 1_887_436),
                ],
                onAttachmentPicked: { _, _, _ in },
                onRemoveAttachment: { _ in }
            )
        }
    }
}

#Preview {
    ComposerBarPreview().amoledScreen()
}

#Preview("Attachments") {
    ComposerBarAttachmentsPreview().amoledScreen()
}
#endif
