import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Holds a short, bounded background-execution window when Mercury is
/// backgrounded, repeatedly running the official REST reconcile so an in-flight
/// turn can deliver a notification before iOS suspends the app.
///
/// This is a best-effort *widening* of the "short task finishes and notifies"
/// window, NOT a guarantee: iOS grants only a bounded background window
/// (historically ~30s) and can end it at any time. It makes NO server changes
/// and opens NO new socket — it reuses `AppModel.performBackgroundReconciliation`
/// (official `GET /api/profiles/sessions`) and lets any still-open live socket
/// deliver on its own. The per-session watermark dedupe means a completion seen
/// by both paths notifies at most once.
///
/// The loop, poll cadence, and platform background-task begin/end are all
/// injectable so the timing logic is unit-testable without UIKit or real time.
@MainActor
final class BackgroundGraceRunner {
    private var loop: Task<Void, Never>?

    /// Set after init (the SwiftUI App can't capture its `@State` AppModel during
    /// `init` until all stored properties are assigned). Invoked each poll.
    var reconcile: () async -> Void = {}

    /// Invoked exactly once when the grace window ends WITHOUT the app having
    /// returned to the foreground (natural expiry of the last poll). Used to
    /// flip a still-live Live Activity to an honest stale presentation. An
    /// early `end()` (foreground return) does NOT fire this.
    var onExpire: () async -> Void = {}

    private let beginTask: () -> Void
    private let endTask: () -> Void
    private let sleep: (UInt64) async -> Void
    private let pollNanos: UInt64
    private let maxPolls: Int

    init(
        pollInterval: TimeInterval = 5,
        maxWindow: TimeInterval = 20,
        reconcile: @escaping () async -> Void = {},
        beginTask: @escaping () -> Void = {},
        endTask: @escaping () -> Void = {},
        sleep: @escaping (UInt64) async -> Void = { try? await Task.sleep(nanoseconds: $0) }
    ) {
        let safeInterval = max(pollInterval, 0.001)
        self.reconcile = reconcile
        self.beginTask = beginTask
        self.endTask = endTask
        self.sleep = sleep
        self.pollNanos = UInt64(safeInterval * 1_000_000_000)
        self.maxPolls = max(1, Int(maxWindow / safeInterval))
    }

    /// Begins the grace window. No-op if one is already running.
    func begin() {
        guard loop == nil else { return }
        beginTask()
        loop = Task { [weak self] in
            guard let self else { return }
            var polls = 0
            while !Task.isCancelled && polls < self.maxPolls {
                await self.reconcile()
                polls += 1
                if Task.isCancelled || polls >= self.maxPolls { break }
                await self.sleep(self.pollNanos)
            }
            // Natural expiry (not cancelled): the app is still backgrounded
            // and iOS will suspend us. Flip live surfaces to stale first.
            if !Task.isCancelled {
                await self.onExpire()
            }
            self.finish()
        }
    }

    /// Ends the window early (e.g. the app returned to the foreground).
    func end() {
        guard loop != nil else { return }
        loop?.cancel()
        loop = nil
        endTask()
    }

    /// Natural completion after the last poll. Guarded so a concurrent `end()`
    /// never double-ends the background task.
    private func finish() {
        guard loop != nil else { return }
        loop = nil
        endTask()
    }
}
