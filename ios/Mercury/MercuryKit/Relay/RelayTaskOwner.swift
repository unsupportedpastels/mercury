import Foundation
import MercuryCore

/// Memory-only custody owned by one selected pool scope, not a visible chat.
/// The socket serializes delivery; the lock fences replacement attachments.
final class RelayTaskOwner: @unchecked Sendable {
    private let lock = NSLock()
    private var generation = UUID()
    private var profile = ""
    private var bindings: [String: MercuryCore.RelayLeaseBinding] = [:]
    private var tasks: [String: BackgroundTasks] = [:]

    func attach(snapshot: MercuryCore.RelayLeaseSnapshot, profile: String) -> UUID {
        lock.lock(); defer { lock.unlock() }
        generation = UUID()
        self.profile = profile
        bindings = Dictionary(uniqueKeysWithValues: snapshot.bindings
            .filter { $0.profile == profile }.map { ($0.runtimeId, $0) })
        for durable in Set(tasks.keys).union(bindings.values.map(\.durableId)) {
            var state = tasks[durable] ?? BackgroundTasks()
            state.recover(snapshot: snapshot, durableID: durable, profile: profile, runtime: nil)
            tasks[durable] = state
        }
        return generation
    }

    /// Only an authenticated, correlated create/resume response may add a live binding.
    func bind(generation: UUID, runtime: String, durable: String, profile: String) {
        lock.lock(); defer { lock.unlock() }
        guard self.generation == generation, self.profile == profile else { return }
        if bindings[runtime]?.durableId != durable || bindings[runtime]?.live != true {
            // A new runtime is not fresh activity evidence for the old worker.
            tasks[durable]?.markUnavailable()
        }
        for (id, binding) in bindings where binding.durableId == durable && id != runtime {
            bindings[id] = MercuryCore.RelayLeaseBinding(runtimeId: id, durableId: durable,
                                                         profile: profile, live: false)
        }
        bindings[runtime] = MercuryCore.RelayLeaseBinding(runtimeId: runtime, durableId: durable,
                                                         profile: profile, live: true)
    }

    /// False means explicit rejection, never an application to the visible runtime.
    @discardableResult
    func apply(generation: UUID, runtime: String, evidence: BackgroundTaskEvidence) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard self.generation == generation,
              let binding = bindings[runtime], binding.live, binding.profile == profile else { return false }
        var state = tasks[binding.durableId] ?? BackgroundTasks()
        state.apply(.backgroundTask(sessionID: runtime, evidence: evidence), runtime: runtime,
                    now: Int64(Date().timeIntervalSince1970 * 1000))
        tasks[binding.durableId] = state
        return true
    }

    func reconcile(generation: UUID, durable: String, runtime: String,
                   statuses: [String: String]) -> BackgroundTasks? {
        lock.lock(); defer { lock.unlock() }
        guard self.generation == generation, let binding = bindings[runtime],
              binding.live, binding.durableId == durable, binding.profile == profile else { return nil }
        var state = tasks[durable] ?? BackgroundTasks()
        state.reconcile(statuses, runtime: runtime)
        tasks[durable] = state
        return state
    }

    func retained(generation: UUID, durable: String, runtime: String?) -> BackgroundTasks? {
        lock.lock(); defer { lock.unlock() }
        guard self.generation == generation else { return nil }
        if let runtime {
            guard let binding = bindings[runtime], binding.durableId == durable,
                  binding.profile == profile, binding.live else { return nil }
        }
        return tasks[durable] ?? BackgroundTasks()
    }
}
