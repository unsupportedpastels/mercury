import Foundation
import Security

/// An origin-scoped access/refresh token pair.
///
/// `expiresAt` (epoch seconds) and `provider` were added additively for native
/// token refresh. Older keychain payloads without these fields still decode:
/// the custom decoder defaults them to `0` / `""`.
struct TokenPair: Codable, Equatable {
    var accessToken: Data
    var refreshToken: Data?
    var expiresAt: Int64
    var provider: String

    init(accessToken: Data, refreshToken: Data? = nil, expiresAt: Int64 = 0, provider: String = "") {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresAt = expiresAt
        self.provider = provider
    }

    private enum CodingKeys: String, CodingKey {
        case accessToken
        case refreshToken
        case expiresAt
        case provider
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        accessToken = try container.decode(Data.self, forKey: .accessToken)
        refreshToken = try container.decodeIfPresent(Data.self, forKey: .refreshToken)
        expiresAt = try container.decodeIfPresent(Int64.self, forKey: .expiresAt) ?? 0
        provider = try container.decodeIfPresent(String.self, forKey: .provider) ?? ""
    }
}

/// Abstraction over credential persistence, scoped by normalized server origin.
/// Implementations must NEVER log token material.
protocol CredentialStoring {
    func tokens(for origin: String) -> TokenPair?
    func setTokens(_ tokens: TokenPair, for origin: String)
    func clearTokens(for origin: String)
}

/// Keychain-backed implementation using `kSecClassGenericPassword`.
///
/// Storage layout:
/// - service: "com.unsupportedpastels.mercury.tokens" (constant)
/// - account: normalized server origin (one item per backend)
/// - payload: JSON-encoded `TokenPair`
struct KeychainCredentialStore: CredentialStoring {

    private static let defaultService = "com.unsupportedpastels.mercury.tokens"

    private let normalize: (String) -> String?
    private let service: String

    init(
        normalize: @escaping (String) -> String? = { ServerOrigin.normalize($0) },
        service: String = Self.defaultService
    ) {
        self.normalize = normalize
        self.service = service
    }

    // MARK: - CredentialStoring

    func tokens(for origin: String) -> TokenPair? {
        guard let account = accountKey(for: origin) else { return nil }

        if let pair = readTokens(account: account) {
            return pair
        }

        // One-time migration: entries written before default-port elision were
        // keyed under the legacy canonical form (e.g. "https://host:443").
        // On a miss, look there once and re-file under the current key.
        let legacyAccounts = ([ServerOrigin.legacyNormalize(origin)].compactMap { $0 }
            + ServerOrigin.legacyCredentialAccountCandidates(for: account))
            .filter { $0 != account }
        for legacyAccount in Array(Set(legacyAccounts)).sorted() {
            if let pair = readTokens(account: legacyAccount) {
                setTokens(pair, for: origin)
                SecItemDelete(baseQuery(account: legacyAccount) as CFDictionary)
                return pair
            }
        }
        return nil
    }

    private func readTokens(account: String) -> TokenPair? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(TokenPair.self, from: data)
    }

    func setTokens(_ tokens: TokenPair, for origin: String) {
        guard let account = accountKey(for: origin),
              let payload = try? JSONEncoder().encode(tokens) else { return }

        var query = baseQuery(account: account)
        let attributesToUpdate: [String: Any] = [
            kSecValueData as String: payload,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        var status = SecItemUpdate(query as CFDictionary, attributesToUpdate as CFDictionary)
        if status == errSecItemNotFound {
            query[kSecValueData as String] = payload
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(query as CFDictionary, nil)
        }
        // Other statuses are swallowed deliberately: no token material or
        // keychain details ever reach logs.
    }

    func clearTokens(for origin: String) {
        var accounts = Set<String>()
        if let account = accountKey(for: origin) {
            accounts.insert(account)
            accounts.formUnion(ServerOrigin.legacyCredentialAccountCandidates(for: account))
        }
        if let legacyAccount = ServerOrigin.legacyNormalize(origin) {
            accounts.insert(legacyAccount)
            accounts.formUnion(ServerOrigin.legacyCredentialAccountCandidates(for: legacyAccount))
        }
        for account in accounts {
            SecItemDelete(baseQuery(account: account) as CFDictionary)
        }
    }

    // MARK: - Internals

    /// Normalized origin is required so credentials can never be scoped to a
    /// sloppy duplicate of an already-stored host.
    private func accountKey(for origin: String) -> String? {
        normalize(origin)
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
