import Foundation
import MercuryCore

/// Memory-only checkpoint owner. The shared immutable state decides generation,
/// lease binding and monotonic advancement; this lock only serializes callbacks.
final class RelayRecoveryCheckpoint: @unchecked Sendable {
    private let lock = NSLock()
    private var core = MercuryCore.RelayLeaseCheckpointState(leaseId: nil, cursor: 0, generation: 0)

    func begin() -> (generation: Int64, cursor: Int64) {
        lock.lock()
        defer { lock.unlock() }
        core = core.begin()
        return (core.generation, core.cursor)
    }
    func bind(generation: Int64, leaseID: String) {
        lock.lock()
        defer { lock.unlock() }
        core = core.bind(owner: generation, id: leaseID)
    }
    func applied(generation: Int64, leaseID: String, sequence: Int64) {
        lock.lock()
        defer { lock.unlock() }
        core = core.applied(owner: generation, id: leaseID, seq: sequence)
    }
    var cursor: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return core.cursor
    }
}
