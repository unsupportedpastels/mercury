import SwiftUI

/// Secure-input sheet for secret/sudo blocking prompts.
///
/// Dumb view by design: no connection, no AppModel — the caller owns the RPC
/// round-trip via `onSubmit`/`onCancel`. The entered value lives only in
/// local `@State`: it is never persisted (no AppStorage/SceneStorage) and is
/// blanked on submit and on cancel/dismiss so it cannot linger in the sheet.
struct SecureInputSheet: View {
    let kind: UnsupportedBlockingKind
    let prompt: String?
    let onSubmit: (String) -> Void
    let onCancel: () -> Void

    @State private var value = ""
    /// Guards against double-callbacks: a swipe-dismiss after submit must not
    /// also fire `onCancel`, and vice versa.
    @State private var finished = false

    private var title: String {
        switch kind {
        case .sudo: return "Sudo password"
        default: return "Secure input"
        }
    }

    private var promptText: String {
        if let prompt, !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return prompt
        }
        return "Secure input is required to continue"
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(promptText)
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
                    .textSelection(.enabled)

                SecureField("Enter secure input", text: $value)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit(submit)
                    .padding(10)
                    .background(Color.surfaceLow)
                    .clipShape(RoundedRectangle(cornerRadius: 10))

                Spacer()
            }
            .padding()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: cancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Submit", action: submit)
                }
            }
        }
        .presentationDetents([.medium])
        .onDisappear(perform: cancelIfPending)
    }

    private func submit() {
        let submitted = value
        value = ""
        finished = true
        onSubmit(submitted)
    }

    private func cancel() {
        finished = true
        value = ""
        onCancel()
    }

    /// Swipe-to-dismiss path: only counts as a cancel when neither submit nor
    /// an explicit cancel already completed the interaction.
    private func cancelIfPending() {
        guard !finished else { return }
        cancel()
    }
}
