import Foundation

/// Sessions this install has actually opened/driven, scoped by normalized
/// server origin + profile. This is the iOS analog of Android's implicit
/// notification scope: Android only ever notifies for sessions its live event
/// collector is subscribed to (i.e. ones the app opened). iOS's background REST
/// reconciler has no such implicit scope, so without this it would notify for
/// EVERY session on the server. Persisted (UserDefaults) like ProjectPinStore
/// and the watermark store; mirrors their scoping key exactly.
struct EngagedSessionStore {
    private let defaults: UserDefaults
    /// Bound the persisted set so a power user who opens hundreds of sessions
    /// doesn't grow this unboundedly. Newest-engaged wins.
    private let maxTracked = 500

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private func key(origin: String, profile: String) -> String {
        "mercury.sessions.engaged.\(origin)\u{0}\(profile)"
    }

    func engagedIDs(origin: String, profile: String) -> Set<String> {
        Set(defaults.stringArray(forKey: key(origin: origin, profile: profile)) ?? [])
    }

    /// Records that the app opened this session. Most-recent entries are kept
    /// at the end so the bound trims the oldest first.
    func markEngaged(_ sessionID: String, origin: String, profile: String) {
        guard !sessionID.isEmpty else { return }
        let k = key(origin: origin, profile: profile)
        var ordered = defaults.stringArray(forKey: k) ?? []
        ordered.removeAll { $0 == sessionID }
        ordered.append(sessionID)
        if ordered.count > maxTracked {
            ordered = Array(ordered.suffix(maxTracked))
        }
        defaults.set(ordered, forKey: k)
    }
}
