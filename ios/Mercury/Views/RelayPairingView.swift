import SwiftUI

/// Pairing sheet: scan (or paste) the QR the Mercury host plugin presents,
/// then display the short authentication string until the host operator
/// approves the device. The scanned payload is handed straight to the
/// coordinator and never shown, stored, or copied.
struct RelayPairingView: View {
    @Bindable var relay: RelayAppModel
    @Environment(\.dismiss) private var dismiss

    @State private var pastedCode = ""

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Pair Mercury Relay")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") {
                            relay.cancelPairing()
                            dismiss()
                        }
                    }
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch relay.pairingPhase {
        case .idle:
            scanContent
        case .pairing:
            VStack(spacing: 12) {
                ProgressView()
                Text("Pairing securely…")
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .awaitingApproval(let target):
            approvalContent(target: target)
        case .approved(let target):
            approvedContent(target: target)
        case .failed(let message):
            VStack(spacing: 16) {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.surfaceMid, in: RoundedRectangle(cornerRadius: 10))
                Button("Try again") { relay.resetPairingFailure() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
        }
    }

    private var scanContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("On the machine running Hermes, open the Mercury Relay dashboard tab or desktop app and choose “Pair device”, then scan the code.")
                .font(.subheadline)
                .foregroundStyle(Color.secondary)

            RelayQRScannerView { scanned in
                Task { await relay.beginPairing(scannedText: scanned) }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 320)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            HStack {
                TextField("…or paste the pairing code", text: $pastedCode)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(10)
                    .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
                Button("Pair") {
                    let code = pastedCode
                    pastedCode = ""
                    Task { await relay.beginPairing(scannedText: code) }
                }
                .buttonStyle(.bordered)
                .disabled(pastedCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Spacer()
        }
        .padding(20)
    }

    private func approvalContent(target: RelayPairedTarget) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Paired — waiting for approval", systemImage: "clock.arrow.circlepath")
                .font(.headline)

            Text("Confirm on the host that it shows exactly this code, then approve the device there:")
                .font(.subheadline)
                .foregroundStyle(Color.secondary)

            Text(formattedFingerprint(target.fingerprint))
                .font(.title2.monospaced().bold())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 12))

            Label(
                "If the codes differ, deny the device on the host — someone may be interfering.",
                systemImage: "exclamationmark.shield"
            )
            .font(.caption)
            .foregroundStyle(Color.secondary)

            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Checking for approval…")
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
            }
            Spacer()
        }
        .padding(20)
    }

    private func approvedContent(target: RelayPairedTarget) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 44))
                .foregroundStyle(Color.statusHealthy)
            Text("Device approved")
                .font(.headline)
            Text("You can now open sessions on \(target.displayLabel).")
                .font(.subheadline)
                .foregroundStyle(Color.secondary)
            Button("Done") {
                relay.cancelPairing()
                dismiss()
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }

    /// Grouped for comparison against the host display, e.g. "a1b2 c3d4 …".
    private func formattedFingerprint(_ fingerprint: String) -> String {
        stride(from: 0, to: fingerprint.count, by: 4).map { offset in
            let start = fingerprint.index(fingerprint.startIndex, offsetBy: offset)
            let end = fingerprint.index(
                start, offsetBy: 4, limitedBy: fingerprint.endIndex
            ) ?? fingerprint.endIndex
            return String(fingerprint[start..<end])
        }.joined(separator: " ")
    }
}
