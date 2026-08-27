import Foundation

protocol WatermarkStoring {
    func load(origin: String) -> [String: SessionWatermark]
    func save(_ watermarks: [String: SessionWatermark], origin: String)
    func clear(origin: String)
}

struct UserDefaultsWatermarkStore: WatermarkStoring {
    private static let keyPrefix = "mercury.notif.watermarks."

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    func load(origin: String) -> [String: SessionWatermark] {
        guard let key = key(for: origin),
              let data = userDefaults.data(forKey: key)
        else {
            return [:]
        }

        return (try? JSONDecoder().decode([String: SessionWatermark].self, from: data)) ?? [:]
    }

    func save(_ watermarks: [String: SessionWatermark], origin: String) {
        guard let key = key(for: origin),
              let data = try? JSONEncoder().encode(watermarks)
        else {
            return
        }

        userDefaults.set(data, forKey: key)
    }

    func clear(origin: String) {
        guard let key = key(for: origin) else {
            return
        }

        userDefaults.removeObject(forKey: key)
    }

    private func key(for origin: String) -> String? {
        guard let normalized = ServerOrigin.normalize(origin) else {
            return nil
        }
        return Self.keyPrefix + normalized
    }
}
