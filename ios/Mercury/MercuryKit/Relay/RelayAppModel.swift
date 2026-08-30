import Foundation
import Observation

/// UI-facing state for Mercury Relay pairing and paired-target management.
///
/// Deliberately separate from `AppModel`/`ConnectionController`: relay mode
/// never touches the direct-server catalog, credentials, or connection phase,
/// so direct self-hosted and Hermes Cloud behavior is preserved unchanged
/// (plan Task 8 isolation requirement).
@MainActor
@Observable
final class RelayAppModel {
    enum PairingPhase: Equatable {
        case idle
        /// Scanning finished; the handshake is in flight.
        case pairing
        /// Paired and pending: show the fingerprint until the host operator
        /// approves; `probeApproval` polls with fresh handshakes.
        case awaitingApproval(RelayPairedTarget)
        case approved(RelayPairedTarget)
        case failed(String)
    }

    private(set) var targets: [RelayPairedTarget] = []
    private(set) var pairingPhase: PairingPhase = .idle
    private(set) var targetsError: String?

    private let store: RelayTargetStore
    private let coordinator: RelayPairingCoordinator
    private var approvalTask: Task<Void, Never>?

    init(
        store: RelayTargetStore = RelayTargetStore(),
        socketFactory: any RelayBinarySocketFactorying = URLSessionRelaySocketFactory()
    ) {
        self.store = store
        coordinator = RelayPairingCoordinator(socketFactory: socketFactory, store: store)
    }

    func loadTargets() async {
        do {
            targets = try await store.load()
            targetsError = nil
        } catch {
            // Fail closed without discarding key material (the store never
            // overwrites on read); the user sees a stable message.
            targets = []
            targetsError = "Saved relay pairings could not be read."
        }
    }

    func beginPairing(scannedText: String) async {
        guard pairingPhase == .idle || isFailure(pairingPhase) else { return }
        pairingPhase = .pairing
        do {
            let target = try await coordinator.pair(scannedText: scannedText)
            await loadTargets()
            pairingPhase = .awaitingApproval(target)
            startApprovalPolling(target: target)
        } catch {
            pairingPhase = .failed(Self.pairingMessage(for: error))
        }
    }

    func cancelPairing() {
        approvalTask?.cancel()
        approvalTask = nil
        pairingPhase = .idle
    }

    func resetPairingFailure() {
        if isFailure(pairingPhase) { pairingPhase = .idle }
    }

    /// Removes local key material. Host-side revocation is a management
    /// action on the host/dashboard; the confirmation UI states that
    /// honestly rather than pretending an offline host was notified.
    func removeTarget(_ target: RelayPairedTarget) async {
        try? await store.remove(id: target.id)
        await loadTargets()
    }

    func renameTarget(_ target: RelayPairedTarget, label: String) async {
        try? await store.updateLabel(id: target.id, label: label)
        await loadTargets()
    }

    private func startApprovalPolling(target: RelayPairedTarget) {
        approvalTask?.cancel()
        approvalTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if await self.coordinator.probeApproval(target: target, profile: "default") {
                    await self.loadTargets()
                    if case .awaitingApproval(let pending) = self.pairingPhase,
                       pending.id == target.id {
                        self.pairingPhase = .approved(target)
                    }
                    return
                }
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    private func isFailure(_ phase: PairingPhase) -> Bool {
        if case .failed = phase { return true }
        return false
    }

    static func pairingMessage(for error: Error) -> String {
        switch error as? RelayPairingError {
        case .malformedQR:
            return "That code isn't a Mercury Relay pairing QR."
        case .unsupportedVersion:
            return "This pairing code needs a newer version of Mercury."
        case .missingRelayOrigin:
            return "The host has no relay address configured. Set relay_origin on the host and create a new code."
        case .expiredOffer:
            return "That pairing code has expired. Create a fresh one on the host."
        case .offerRejected:
            return "The host refused this pairing code — it may already be used. Create a fresh one."
        case .offline:
            return "The relay could not be reached. Check your connection and that the host is online."
        case .protocolViolation:
            return "Pairing failed a security check and was stopped."
        case .storageFailed:
            return "The pairing could not be saved securely on this device."
        case .targetLimitReached:
            return "You've reached the limit of saved relay pairings. Remove one first."
        case nil:
            return "Pairing failed. Try again."
        }
    }
}
