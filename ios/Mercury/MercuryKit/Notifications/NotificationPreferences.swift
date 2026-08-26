import Foundation

/// Device-wide notification choices shared by all Mercury server origins.
struct MercuryNotificationPreferences: Codable, Equatable, Sendable {
    var notificationsEnabled: Bool
    var completionEnabled: Bool
    var attentionEnabled: Bool
    var failureAndCancellationEnabled: Bool
    var liveActivitiesEnabled: Bool
    var liveActivityResponseExcerptsEnabled: Bool

    init(
        notificationsEnabled: Bool = false,
        completionEnabled: Bool = false,
        attentionEnabled: Bool = false,
        failureAndCancellationEnabled: Bool = false,
        liveActivitiesEnabled: Bool = false,
        liveActivityResponseExcerptsEnabled: Bool = false
    ) {
        self.notificationsEnabled = notificationsEnabled
        self.completionEnabled = completionEnabled
        self.attentionEnabled = attentionEnabled
        self.failureAndCancellationEnabled = failureAndCancellationEnabled
        self.liveActivitiesEnabled = liveActivitiesEnabled
        self.liveActivityResponseExcerptsEnabled = liveActivityResponseExcerptsEnabled
    }

    /// Preferences for a new installation: no notification or activity data is
    /// emitted until the user opts in.
    static let newInstallDefaults = MercuryNotificationPreferences()

    /// The compatibility default used by the pre-preferences notification
    /// pipeline. App wiring replaces this with the persisted device-wide value.
    static let permissiveAll = MercuryNotificationPreferences(
        notificationsEnabled: true,
        completionEnabled: true,
        attentionEnabled: true,
        failureAndCancellationEnabled: true,
        liveActivitiesEnabled: true,
        liveActivityResponseExcerptsEnabled: true
    )

    /// Response excerpts are meaningful only when the enclosing Live Activity
    /// is enabled. Keep this invariant at every persistence boundary.
    func normalized() -> MercuryNotificationPreferences {
        var normalized = self
        if !normalized.liveActivitiesEnabled {
            normalized.liveActivityResponseExcerptsEnabled = false
        }
        return normalized
    }
}

enum MercuryNotificationAuthorizationStatus: Equatable, Sendable {
    case notDetermined
    case denied
    case authorized
    case provisional
    case ephemeral
    case unknown

    var countsAsAuthorized: Bool {
        switch self {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined, .denied, .unknown:
            return false
        }
    }
}

protocol NotificationPreferencesStoring: Sendable {
    func load() -> MercuryNotificationPreferences
    func save(_ preferences: MercuryNotificationPreferences)
    var hasStoredPreferences: Bool { get }
    func markMigrated()
}

/// UserDefaults-backed, device-wide notification preferences.
///
/// The payload is deliberately one small JSON blob. Invalid or unexpectedly
/// large data fails closed to the new-install defaults rather than attempting
/// partial recovery of notification choices.
final class NotificationPreferencesStore: NotificationPreferencesStoring, @unchecked Sendable {
    static let storageKey = "mercury.notif.preferences"
    private static let maximumPayloadBytes = 4 * 1024

    private let userDefaults: UserDefaults
    private let lock = NSLock()

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    var hasStoredPreferences: Bool {
        withLock {
            userDefaults.object(forKey: Self.storageKey) != nil
        }
    }

    func load() -> MercuryNotificationPreferences {
        withLock {
            loadUnlocked()
        }
    }

    func save(_ preferences: MercuryNotificationPreferences) {
        withLock {
            saveUnlocked(preferences)
        }
    }

    /// Marks the preference migration complete without introducing another
    /// preference payload. In normal use `save` has already created the blob;
    /// the fallback also makes this method safe for injected orchestrators.
    func markMigrated() {
        withLock {
            guard userDefaults.object(forKey: Self.storageKey) == nil else {
                return
            }
            saveUnlocked(.newInstallDefaults)
        }
    }

    /// Applies the one-time migration using the current system authorization.
    /// A stored (including corrupt) payload is never overwritten by migration.
    @discardableResult
    func migrateIfNeeded(
        systemStatus: MercuryNotificationAuthorizationStatus
    ) async -> MercuryNotificationPreferences {
        migrateIfNeededSynchronously(systemStatus: systemStatus)
    }

    /// Pure migration policy. Existing stored preferences always win, while a
    /// first install inherits only an already-authorized system permission.
    static func migratedPreferences(
        hasStored: Bool,
        systemStatus: MercuryNotificationAuthorizationStatus
    ) -> MercuryNotificationPreferences? {
        guard !hasStored else {
            return nil
        }

        guard systemStatus.countsAsAuthorized else {
            return .newInstallDefaults
        }

        return MercuryNotificationPreferences(
            notificationsEnabled: true,
            completionEnabled: true,
            attentionEnabled: true,
            failureAndCancellationEnabled: true,
            liveActivitiesEnabled: false,
            liveActivityResponseExcerptsEnabled: false
        )
    }

    private func migrateIfNeededSynchronously(
        systemStatus: MercuryNotificationAuthorizationStatus
    ) -> MercuryNotificationPreferences {
        withLock {
            guard let migrated = Self.migratedPreferences(
                hasStored: userDefaults.object(forKey: Self.storageKey) != nil,
                systemStatus: systemStatus
            ) else {
                return loadUnlocked()
            }

            saveUnlocked(migrated)
            markMigratedUnlocked()
            return migrated.normalized()
        }
    }

    private func loadUnlocked() -> MercuryNotificationPreferences {
        guard let data = userDefaults.data(forKey: Self.storageKey),
              data.count <= Self.maximumPayloadBytes,
              let preferences = try? JSONDecoder().decode(
                MercuryNotificationPreferences.self,
                from: data
              )
        else {
            return .newInstallDefaults
        }

        return preferences.normalized()
    }

    private func saveUnlocked(_ preferences: MercuryNotificationPreferences) {
        guard let data = try? JSONEncoder().encode(preferences.normalized()) else {
            return
        }
        userDefaults.set(data, forKey: Self.storageKey)
    }

    private func markMigratedUnlocked() {
        guard userDefaults.object(forKey: Self.storageKey) == nil else {
            return
        }
        saveUnlocked(.newInstallDefaults)
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
