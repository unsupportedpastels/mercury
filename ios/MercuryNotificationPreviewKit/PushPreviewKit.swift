import Foundation
import CryptoKit
import Security
#if canImport(Darwin)
import Darwin
#endif

public enum PushPreviewConstants {
    public static let version = 1
    public static let algorithm = "C20P"
    public static let domain = "mercury.push-preview.v1"
    public static let maxPlaintextBytes = 1280
    public static let maxTitleBytes = 160
    public static let maxBodyBytes = 640
    public static let maxRouteSessionBytes = 128
    public static let maxRouteProfileBytes = 64
    public static let keychainService = "com.unsupportedpastels.mercury.preview-keys"
    public static var accessGroup: String { (Bundle.main.object(forInfoDictionaryKey: "MercuryPreviewKeychainAccessGroup") as? String) ?? "" }
    public static let appGroup = "group.com.unsupportedpastels.mercury"
}

public enum PushPreviewFailure: String, Error { case malformedEnvelope, unavailableKey, authentication, malformedPlaintext, invalidTime, replay }
public final class PushPreviewCompletionGate {
    private let lock = NSLock(); private var completed = false
    public init() {}
    public func claim() -> Bool { lock.lock(); defer { lock.unlock() }; guard !completed else { return false }; completed = true; return true }
}
public struct PushPreviewEnvelope: Equatable {
    public let wake, event, keyID, nonce, ciphertext: String
    public init(userInfo: [AnyHashable: Any]) throws {
        guard Set(userInfo.keys.compactMap { $0 as? String }).isSuperset(of: ["mercury_wake", "mercury_event", "mercury_preview"]),
              let wake = userInfo["mercury_wake"] as? String, Self.canonical(wake, bytes: 32),
              let event = userInfo["mercury_event"] as? String, Self.canonical(event, bytes: 32),
              let object = userInfo["mercury_preview"] as? [String: Any],
              Set(object.keys) == ["v", "alg", "kid", "nonce", "ct"],
              let version = object["v"] as? NSNumber, CFGetTypeID(version) != CFBooleanGetTypeID(), version.doubleValue == 1,
              let alg = object["alg"] as? String, alg == PushPreviewConstants.algorithm,
              let keyID = object["kid"] as? String, Self.canonical(keyID, bytes: 16),
              let nonce = object["nonce"] as? String, Self.canonical(nonce, bytes: 12),
              let ciphertext = object["ct"] as? String,
              let decoded = Self.decode(ciphertext), decoded.count >= 16,
              decoded.count <= PushPreviewConstants.maxPlaintextBytes + 16
        else { throw PushPreviewFailure.malformedEnvelope }
        self.wake = wake; self.event = event; self.keyID = keyID; self.nonce = nonce; self.ciphertext = ciphertext
    }
    public static func decode(_ value: String) -> Data? {
        guard !value.isEmpty, value.utf8.count <= 1_728, !value.contains("="), value.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }) else { return nil }
        var standard = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        standard += String(repeating: "=", count: (4 - standard.count % 4) % 4)
        guard let data = Data(base64Encoded: standard), encode(data) == value else { return nil }
        return data
    }
    public static func encode(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    static func canonical(_ value: String, bytes: Int) -> Bool { decode(value)?.count == bytes }
}

public struct PushPreviewPlaintext: Equatable {
    public let kind: String; public let title, body, routeSessionID, routeProfile: String?; public let issuedAt, expiresAt: Int64
    private static func exactInt64(_ number: NSNumber) -> Int64? {
        guard CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite,
              number.doubleValue.rounded() == number.doubleValue,
              number.doubleValue >= Double(Int64.min), number.doubleValue <= Double(Int64.max) else { return nil }
        return number.int64Value
    }
    public static func parse(_ data: Data, now: Int64) throws -> Self {
        guard data.count <= PushPreviewConstants.maxPlaintextBytes,
              let text = String(data: data, encoding: .utf8), !JSONDuplicateKeys.containsDuplicate(in: text),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = object["v"] as? NSNumber, exactInt64(version) == 1,
              let kind = object["kind"] as? String, kind == "completion" || kind == "attention",
              let iatNumber = object["iat"] as? NSNumber, let iat = exactInt64(iatNumber),
              let expNumber = object["exp"] as? NSNumber, let exp = exactInt64(expNumber)
        else { throw PushPreviewFailure.malformedPlaintext }
        guard exp >= iat, exp - iat <= 300, iat <= now + 60, iat >= now - 300, now <= exp else { throw PushPreviewFailure.invalidTime }
        func bounded(_ name: String, max: Int) throws -> String? {
            guard let raw = object[name] else { return nil }
            guard let value = raw as? String, !value.isEmpty, value.utf8.count <= max,
                  !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw PushPreviewFailure.malformedPlaintext }
            return value
        }
        let title = try bounded("title", max: PushPreviewConstants.maxTitleBytes)
        let body = try bounded("body", max: PushPreviewConstants.maxBodyBytes)
        var routeSessionID: String?, routeProfile: String?
        if let rawRoute = object["route"] {
            guard let r = rawRoute as? [String: Any], Set(r.keys) == ["sid", "profile"],
                  let sid = r["sid"] as? String, (1...PushPreviewConstants.maxRouteSessionBytes).contains(sid.utf8.count),
                  let profile = r["profile"] as? String, (1...PushPreviewConstants.maxRouteProfileBytes).contains(profile.utf8.count),
                  !sid.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
                  !profile.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw PushPreviewFailure.malformedPlaintext }
            routeSessionID = sid; routeProfile = profile
        }
        return Self(kind: kind, title: title, body: body, routeSessionID: routeSessionID, routeProfile: routeProfile, issuedAt: iat, expiresAt: exp)
    }
}

public struct PreviewKeyRecord: Codable, Equatable {
    public let schema: Int; public let key, keyID, wake, environment: String; public let createdAt, retireAt: Int64?
    public init(key: Data, keyID: String, wake: String, environment: String, createdAt: Int64, retireAt: Int64? = nil) {
        schema = 1; self.key = PushPreviewEnvelope.encode(key); self.keyID = keyID; self.wake = wake; self.environment = environment; self.createdAt = createdAt; self.retireAt = retireAt
    }
    public var keyData: Data? { PushPreviewEnvelope.decode(key) }
}
public protocol PreviewKeyReading { func load(environment: String, wake: String, keyID: String, now: Int64) throws -> PreviewKeyRecord? }
public protocol PreviewReplayChecking { func claim(event: String, keyID: String, expiresAt: Int64, now: Int64) -> Bool }
public protocol PreviewRouteConsuming { func consume(event: String, wake: String, now: Int64) -> PreviewRouteRecord? }
public protocol PreviewRouteReading { func peek(event: String, wake: String, now: Int64) -> PreviewRouteRecord? }

public struct PreviewRouteRecord: Codable, Equatable {
    public let event, wake, sessionID, profile: String
    public let expiresAt: Int64
    public init(event: String, wake: String, sessionID: String, profile: String, expiresAt: Int64) {
        self.event = event; self.wake = wake; self.sessionID = sessionID; self.profile = profile; self.expiresAt = expiresAt
    }
}

public final class PreviewKeychainRepository: PreviewKeyReading {
    public init() {}
    public static func account(environment: String, wake: String, keyID: String) -> String {
        PushPreviewEnvelope.encode(Data(SHA256.hash(data: Data("\(environment)\u{0}\(wake)\u{0}\(keyID)".utf8))))
    }
    public static func query(environment: String, wake: String, keyID: String) -> [String: Any] {[
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: PushPreviewConstants.keychainService,
        kSecAttrAccount as String: account(environment: environment, wake: wake, keyID: keyID),
        kSecAttrAccessGroup as String: PushPreviewConstants.accessGroup,
        kSecAttrSynchronizable as String: kCFBooleanFalse as Any
    ]}
    public static func addQuery(record: PreviewKeyRecord, data: Data) -> [String: Any] {
        var query = query(environment: record.environment, wake: record.wake, keyID: record.keyID)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return query
    }
    public func save(_ record: PreviewKeyRecord) throws {
        guard record.schema == 1, record.keyData?.count == 32 else { throw PushPreviewFailure.unavailableKey }
        let data = try JSONEncoder().encode(record)
        let q = Self.query(environment: record.environment, wake: record.wake, keyID: record.keyID)
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        var status = SecItemUpdate(q as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(Self.addQuery(record: record, data: data) as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw PushPreviewFailure.unavailableKey }
    }
    public func load(environment: String, wake: String, keyID: String, now: Int64) throws -> PreviewKeyRecord? {
        var q = Self.query(environment: environment, wake: wake, keyID: keyID); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound || status == errSecInteractionNotAllowed { return nil }
        guard status == errSecSuccess, let data = result as? Data, let record = try? JSONDecoder().decode(PreviewKeyRecord.self, from: data), record.schema == 1, record.keyID == keyID, record.wake == wake, record.environment == environment, record.keyData?.count == 32, record.retireAt.map({ now <= $0 }) ?? true else { throw PushPreviewFailure.unavailableKey }
        return record
    }
    public func delete(environment: String, wake: String, keyID: String) { SecItemDelete(Self.query(environment: environment, wake: wake, keyID: keyID) as CFDictionary) }
}

public final class PreviewReplayStore: PreviewReplayChecking {
    private struct Entry: Codable { let event, keyID: String; let exp: Int64 }
    private let url, lockURL: URL; private let lock = NSLock()
    public init?(appGroup: String = PushPreviewConstants.appGroup) {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return nil }
        url = root.appendingPathComponent("push-preview-replay-v1.json")
        lockURL = url.appendingPathExtension("lock")
    }
    public init(url: URL) { self.url = url; lockURL = url.appendingPathExtension("lock") }
    public func claim(event: String, keyID: String, expiresAt: Int64, now: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let descriptor = open(lockURL.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { return false }
        defer { close(descriptor) }
        guard flock(descriptor, LOCK_EX) == 0 else { return false }
        defer { flock(descriptor, LOCK_UN) }
        do {
            var rows: [Entry] = []
            if FileManager.default.fileExists(atPath: url.path) {
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                guard let size = values.fileSize, size <= 32_768 else { return false }
                rows = try JSONDecoder().decode([Entry].self, from: Data(contentsOf: url))
            }
            rows = rows.filter { $0.exp >= now }
            guard !rows.contains(where: { $0.event == event && $0.keyID == keyID }) else { return false }
            rows.append(Entry(event: event, keyID: keyID, exp: expiresAt)); rows = Array(rows.suffix(64))
            let data = try JSONEncoder().encode(rows)
            guard data.count <= 32_768 else { return false }
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }
}

/// Authenticated local routes cross the extension/app boundary through
/// protected App Group state. APNs userInfo is never route authority.
public final class PreviewRouteStore: PreviewRouteConsuming, PreviewRouteReading {
    /// Tap authority is retained for seven days, independently of the 300-second ciphertext window.
    public static let maxRetentionSeconds: Int64 = 7 * 24 * 60 * 60
    private let url, lockURL: URL
    private let lock = NSLock()
    public init?(appGroup: String = PushPreviewConstants.appGroup) {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return nil }
        url = root.appendingPathComponent("push-preview-routes-v1.json")
        lockURL = url.appendingPathExtension("lock")
    }
    public init(url: URL) { self.url = url; lockURL = url.appendingPathExtension("lock") }

    @discardableResult
    public func record(_ route: PreviewRouteRecord, now: Int64) -> Bool {
        let lifetime = route.expiresAt.subtractingReportingOverflow(now)
        guard valid(route), !lifetime.overflow,
              (0...Self.maxRetentionSeconds).contains(lifetime.partialValue) else { return false }
        return transaction {
            var rows = try load(now: now).filter { !($0.event == route.event && $0.wake == route.wake) }
            rows.append(route)
            try save(Array(rows.suffix(64)))
            return true
        } ?? false
    }

    /// Display decisions never consume the single-use tap receipt.
    public func peek(event: String, wake: String, now: Int64) -> PreviewRouteRecord? {
        guard canonical(event: event, wake: wake) else { return nil }
        return transaction { try load(now: now).first { $0.event == event && $0.wake == wake } } ?? nil
    }

    public func consume(event: String, wake: String, now: Int64) -> PreviewRouteRecord? {
        guard canonical(event: event, wake: wake) else { return nil }
        return transaction {
            var rows = try load(now: now)
            guard let index = rows.firstIndex(where: { $0.event == event && $0.wake == wake }) else { return nil }
            let route = rows.remove(at: index)
            // Returning authority requires committing its removal. A failed write
            // cannot masquerade as a successful single-use consume.
            try save(rows)
            return route
        } ?? nil
    }

    public func clear() {
        let _: Bool? = transaction {
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
            return true
        }
    }

    private func transaction<T>(_ body: () throws -> T) -> T? {
        lock.lock(); defer { lock.unlock() }
        // Lock a stable sidecar, never the atomically replaced data inode.
        let descriptor = open(lockURL.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { return nil }
        defer { close(descriptor) }
        guard flock(descriptor, LOCK_EX) == 0 else { return nil }
        defer { flock(descriptor, LOCK_UN) }
        return try? body()
    }

    private func canonical(event: String, wake: String) -> Bool {
        PushPreviewEnvelope.canonical(event, bytes: 32) && PushPreviewEnvelope.canonical(wake, bytes: 32)
    }
    private func valid(_ route: PreviewRouteRecord) -> Bool {
        canonical(event: route.event, wake: route.wake) &&
        (1...PushPreviewConstants.maxRouteSessionBytes).contains(route.sessionID.utf8.count) &&
        (1...PushPreviewConstants.maxRouteProfileBytes).contains(route.profile.utf8.count)
    }
    private func load(now: Int64) throws -> [PreviewRouteRecord] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        guard let size = values.fileSize, size <= 32_768 else { throw PushPreviewFailure.malformedPlaintext }
        let rows = try JSONDecoder().decode([PreviewRouteRecord].self, from: Data(contentsOf: url))
        guard rows.count <= 64, rows.allSatisfy(valid) else { throw PushPreviewFailure.malformedPlaintext }
        return rows.filter {
            let lifetime = $0.expiresAt.subtractingReportingOverflow(now)
            return !lifetime.overflow && (0...Self.maxRetentionSeconds).contains(lifetime.partialValue)
        }
    }
    private func save(_ rows: [PreviewRouteRecord]) throws {
        let data = try JSONEncoder().encode(rows)
        guard data.count <= 32_768 else { throw PushPreviewFailure.malformedPlaintext }
        try data.write(to: url, options: [.atomic, .completeFileProtection])
    }
}

public enum PushPreviewProcessor {
    public static func aad(environment: String, envelope: PushPreviewEnvelope) -> Data { var data = Data(); for value in [PushPreviewConstants.domain, "1", PushPreviewConstants.algorithm, environment, envelope.wake, envelope.event, envelope.keyID] { let bytes = Data(value.utf8), count = UInt32(bytes.count); data.append(contentsOf: [UInt8(count >> 24), UInt8(count >> 16), UInt8(count >> 8), UInt8(count)]); data.append(bytes) }; return data }
    public static func decrypt(userInfo: [AnyHashable: Any], environment: String, now: Int64, keys: PreviewKeyReading, replay: PreviewReplayChecking) throws -> PushPreviewPlaintext {
        let envelope = try PushPreviewEnvelope(userInfo: userInfo)
        guard let record = try keys.load(environment: environment, wake: envelope.wake, keyID: envelope.keyID, now: now), let key = record.keyData, let nonce = PushPreviewEnvelope.decode(envelope.nonce), let combined = PushPreviewEnvelope.decode(envelope.ciphertext) else { throw PushPreviewFailure.unavailableKey }
        let box = try ChaChaPoly.SealedBox(combined: nonce + combined)
        let plaintext: Data
        do { plaintext = try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad(environment: environment, envelope: envelope)) } catch { throw PushPreviewFailure.authentication }
        let decoded = try PushPreviewPlaintext.parse(plaintext, now: now)
        guard replay.claim(event: envelope.event, keyID: envelope.keyID, expiresAt: decoded.expiresAt, now: now) else { throw PushPreviewFailure.replay }
        return decoded
    }
}

private enum JSONDuplicateKeys {
    static func containsDuplicate(in text: String) -> Bool {
        struct Context { let object: Bool; var keys = Set<String>(); var expectingKey: Bool }
        var stack: [Context] = [], i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            if c == "{" { stack.append(Context(object: true, expectingKey: true)); i = text.index(after: i); continue }
            if c == "[" { stack.append(Context(object: false, expectingKey: false)); i = text.index(after: i); continue }
            if c == "}" || c == "]" { if !stack.isEmpty { stack.removeLast() }; i = text.index(after: i); continue }
            if c == "," { if !stack.isEmpty && stack[stack.count - 1].object { stack[stack.count - 1].expectingKey = true }; i = text.index(after: i); continue }
            guard c == "\"" else { i = text.index(after: i); continue }
            let start = i
            i = text.index(after: i)
            var escaped = false
            while i < text.endIndex {
                let value = text[i]
                if escaped { escaped = false }
                else if value == "\\" { escaped = true }
                else if value == "\"" { break }
                i = text.index(after: i)
            }
            guard i < text.endIndex else { return false }
            let end = text.index(after: i)
            if !stack.isEmpty && stack[stack.count - 1].object && stack[stack.count - 1].expectingKey {
                let tail = text[end...].drop(while: { $0.isWhitespace })
                if tail.first == ":",
                   let bytes = "[\(text[start..<end])]".data(using: .utf8),
                   let decoded = (try? JSONSerialization.jsonObject(with: bytes)) as? [String],
                   let key = decoded.first {
                    if stack[stack.count - 1].keys.contains(key) { return true }
                    stack[stack.count - 1].keys.insert(key)
                    stack[stack.count - 1].expectingKey = false
                }
            }
            i = end
        }
        return false
    }
}
