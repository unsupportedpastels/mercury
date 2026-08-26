import CryptoKit
import Foundation
import Security

protocol OfflineCacheKeyProviding: Sendable {
    func loadOrCreateKey() throws -> Data
}

/// A random AES-256 key protected by the device Keychain. The cache files never
/// contain key material, and this service is separate from token credentials.
struct KeychainOfflineCacheKeyProvider: OfflineCacheKeyProviding {
    private static let service = "com.unsupportedpastels.mercury.offline-cache-key"
    private static let account = "aes-gcm-v1"

    func loadOrCreateKey() throws -> Data {
        var read = baseQuery
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &item)
        if status == errSecSuccess, let key = item as? Data, key.count == 32 { return key }
        guard status == errSecItemNotFound else { throw OfflineCacheError.keyUnavailable }

        var bytes = [UInt8](repeating: 0, count: 32)
        let randomStatus = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        guard randomStatus == errSecSuccess else {
            throw OfflineCacheError.keyUnavailable
        }
        let key = Data(bytes)
        var add = baseQuery
        add[kSecValueData as String] = key
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        if addStatus == errSecDuplicateItem {
            // Another caller won the race; fetch the canonical item.
            return try loadExistingKey()
        }
        guard addStatus == errSecSuccess else { throw OfflineCacheError.keyUnavailable }
        return key
    }

    private func loadExistingKey() throws -> Data {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let key = item as? Data, key.count == 32 else {
            throw OfflineCacheError.keyUnavailable
        }
        return key
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account
        ]
    }
}

struct AESGCMOfflineCacheCipher: OfflineCacheCrypting {
    private let keyProvider: OfflineCacheKeyProviding

    init(keyProvider: OfflineCacheKeyProviding = KeychainOfflineCacheKeyProvider()) {
        self.keyProvider = keyProvider
    }

    func seal(_ plaintext: Data, authenticating associatedData: Data) throws -> Data {
        let key = SymmetricKey(data: try keyProvider.loadOrCreateKey())
        let box = try AES.GCM.seal(plaintext, using: key, authenticating: associatedData)
        guard let combined = box.combined else { throw OfflineCacheError.persistenceFailed }
        return combined
    }

    func open(_ ciphertext: Data, authenticating associatedData: Data) throws -> Data {
        do {
            let key = SymmetricKey(data: try keyProvider.loadOrCreateKey())
            let box = try AES.GCM.SealedBox(combined: ciphertext)
            return try AES.GCM.open(box, using: key, authenticating: associatedData)
        } catch {
            throw OfflineCacheError.corruptRow
        }
    }
}

/// One authenticated row per protected file. Filenames are SHA-256 row IDs,
/// not origins, profiles, durable IDs, titles, or transcript text.
final class ProtectedFileOfflineCacheBackend: OfflineCacheBacking, @unchecked Sendable {
    private let directory: URL
    private let defaults: UserDefaults
    private let enabledKey: String
    private let fileManager: FileManager
    private let lock = NSLock()

    init(
        directory: URL? = nil,
        defaults: UserDefaults = .standard,
        enabledKey: String = "offline_cache_transcript_enabled",
        fileManager: FileManager = .default
    ) {
        self.fileManager = fileManager
        self.defaults = defaults
        self.enabledKey = enabledKey
        if let directory {
            self.directory = directory
        } else {
            let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            self.directory = base.appendingPathComponent("OfflineCache-v1", isDirectory: true)
        }
    }

    func listRowKeys(limit: Int) throws -> [String] {
        try locked {
            guard fileManager.fileExists(atPath: directory.path) else { return [] }
            let keys = try fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            )
            .map(\.lastPathComponent)
            .filter { $0.hasPrefix("row-") && $0.hasSuffix(".cache") }
            .sorted()
            return Array(keys.prefix(max(0, limit)))
        }
    }

    func readRow(key: String) throws -> Data? {
        try locked {
            guard isValidKey(key) else { throw OfflineCacheError.persistenceFailed }
            let url = directory.appendingPathComponent(key, isDirectory: false)
            guard fileManager.fileExists(atPath: url.path) else { return nil }
            let attributes = try fileManager.attributesOfItem(atPath: url.path)
            if let size = attributes[.size] as? NSNumber,
               size.intValue > OfflineCachePolicy.maxEncryptedRowBytes {
                try? fileManager.removeItem(at: url)
                return nil
            }
            return try Data(contentsOf: url, options: [.mappedIfSafe])
        }
    }

    func writeRow(_ data: Data, key: String) throws {
        guard data.count <= OfflineCachePolicy.maxEncryptedRowBytes else {
            throw OfflineCacheError.persistenceFailed
        }
        try locked {
            guard isValidKey(key) else { throw OfflineCacheError.persistenceFailed }
            try fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete]
            )
            let url = directory.appendingPathComponent(key, isDirectory: false)
            try data.write(to: url, options: [.atomic])
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: url.path
            )
        }
    }

    func deleteRow(key: String) throws {
        try locked {
            guard isValidKey(key) else { throw OfflineCacheError.persistenceFailed }
            let url = directory.appendingPathComponent(key, isDirectory: false)
            if fileManager.fileExists(atPath: url.path) { try fileManager.removeItem(at: url) }
        }
    }

    func readTranscriptCachingEnabled() -> Bool {
        locked { defaults.bool(forKey: enabledKey) }
    }

    func writeTranscriptCachingEnabled(_ enabled: Bool) throws {
        locked { defaults.set(enabled, forKey: enabledKey) }
    }

    private func isValidKey(_ key: String) -> Bool {
        key.range(of: #"^row-[0-9a-f]{64}\.cache$"#, options: .regularExpression) != nil
    }

    private func locked<T>(_ operation: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try operation()
    }
}
