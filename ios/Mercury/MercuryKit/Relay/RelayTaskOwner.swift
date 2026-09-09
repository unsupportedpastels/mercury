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
    /// The runtime each durable session was bound to before its current live
    /// binding. Retained rows keep the old runtime id; the registry reconciler
    /// may move exact still-running children onto the replacement binding.
    private var previousRuntimes: [String: String] = [:]
    /// Observes every durable state this owner rewrites, keyed by durable
    /// session id. A Home surface reads it while no chat is subscribed to the
    /// pooled reader, so a child completion never strands a stale row.
    private var sink: (@Sendable (String, BackgroundTasks) -> Void)?

    func setSink(_ sink: (@Sendable (String, BackgroundTasks) -> Void)?) {
        lock.lock(); self.sink = sink; lock.unlock()
    }

    /// Delivers outside the lock so an observer may re-enter this owner.
    private func publish(_ changes: [(String, BackgroundTasks)]) {
        lock.lock(); let sink = self.sink; lock.unlock()
        guard let sink else { return }
        for (durable, state) in changes { sink(durable, state) }
    }

    func attach(snapshot: MercuryCore.RelayLeaseSnapshot, profile: String) -> UUID {
        lock.lock()
        generation = UUID()
        self.profile = profile
        bindings = Dictionary(uniqueKeysWithValues: snapshot.bindings
            .filter { $0.profile == profile }.map { ($0.runtimeId, $0) })
        var changes: [(String, BackgroundTasks)] = []
        for durable in Set(tasks.keys).union(bindings.values.map(\.durableId)) {
            var state = tasks[durable] ?? BackgroundTasks()
            state.recover(snapshot: snapshot, durableID: durable, profile: profile, runtime: nil)
            tasks[durable] = state
            if !state.rows.isEmpty { changes.append((durable, state)) }
        }
        let attached = generation
        lock.unlock()
        publish(changes)
        return attached
    }

    /// Only an authenticated, correlated create/resume response may add a live binding.
    func bind(generation: UUID, runtime: String, durable: String, profile: String) {
        lock.lock()
        guard self.generation == generation, self.profile == profile else { lock.unlock(); return }
        var changes: [(String, BackgroundTasks)] = []
        if bindings[runtime]?.durableId != durable || bindings[runtime]?.live != true {
            // A new runtime is not fresh activity evidence for the old worker.
            tasks[durable]?.markUnavailable()
            if let state = tasks[durable], !state.rows.isEmpty { changes.append((durable, state)) }
            let prior = bindings.values.filter { $0.durableId == durable && $0.runtimeId != runtime }
            if let old = prior.first(where: \.live) ?? prior.first { previousRuntimes[durable] = old.runtimeId }
        }
        for (id, binding) in bindings where binding.durableId == durable && id != runtime {
            bindings[id] = MercuryCore.RelayLeaseBinding(runtimeId: id, durableId: durable,
                                                         profile: profile, live: false)
        }
        bindings[runtime] = MercuryCore.RelayLeaseBinding(runtimeId: runtime, durableId: durable,
                                                         profile: profile, live: true)
        lock.unlock()
        publish(changes)
    }

    /// False means explicit rejection, never an application to the visible runtime.
    @discardableResult
    func apply(generation: UUID, runtime: String, evidence: BackgroundTaskEvidence) -> Bool {
        lock.lock()
        guard self.generation == generation,
              let binding = bindings[runtime], binding.live, binding.profile == profile else {
            lock.unlock()
            return false
        }
        var state = tasks[binding.durableId] ?? BackgroundTasks()
        state.apply(.backgroundTask(sessionID: runtime, evidence: evidence), runtime: runtime,
                    now: Int64(Date().timeIntervalSince1970 * 1000))
        let changed = state != tasks[binding.durableId]
        tasks[binding.durableId] = state
        lock.unlock()
        if changed { publish([(binding.durableId, state)]) }
        return true
    }

    func reconcile(generation: UUID, durable: String, runtime: String,
                   statuses: [String: String]) -> BackgroundTasks? {
        lock.lock()
        guard self.generation == generation, let binding = bindings[runtime],
              binding.live, binding.durableId == durable, binding.profile == profile else {
            lock.unlock()
            return nil
        }
        var state = tasks[durable] ?? BackgroundTasks()
        state.reconcile(statuses, runtime: runtime, previousRuntime: previousRuntimes[durable],
                        now: Int64(Date().timeIntervalSince1970 * 1000))
        let changed = state != tasks[durable]
        tasks[durable] = state
        lock.unlock()
        if changed { publish([(durable, state)]) }
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
