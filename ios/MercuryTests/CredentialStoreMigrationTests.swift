import Foundation
import Security
import XCTest
@testable import Mercury

final class CredentialStoreMigrationTests: XCTestCase {
    func testElidedDefaultPortMigratesTheLegacyKeychainAccount() throws {
        let service = "com.unsupportedpastels.mercury.tests.\(UUID().uuidString)"
        let legacyAccount = "https://legacy.example:443"
        let canonicalAccount = "https://legacy.example"
        let pair = TokenPair(
            accessToken: Data("inert-access".utf8),
            refreshToken: Data("inert-refresh".utf8),
            expiresAt: 42,
            provider: "test"
        )
        let payload = try JSONEncoder().encode(pair)
        let legacyQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: legacyAccount,
            kSecValueData as String: payload,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        XCTAssertEqual(SecItemAdd(legacyQuery as CFDictionary, nil), errSecSuccess)
        defer {
            SecItemDelete([
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
            ] as CFDictionary)
        }

        let store = KeychainCredentialStore(service: service)
        XCTAssertEqual(store.tokens(for: canonicalAccount), pair)
        XCTAssertEqual(keychainStatus(service: service, account: legacyAccount), errSecItemNotFound)
        XCTAssertEqual(keychainStatus(service: service, account: canonicalAccount), errSecSuccess)
    }

    func testClearRemovesPublicHTTPAccountRejectedByTheNewPolicy() throws {
        let service = "com.unsupportedpastels.mercury.tests.\(UUID().uuidString)"
        let legacyAccount = "http://public.example"
        try seed(TokenPair(accessToken: Data("inert".utf8)), service: service, account: legacyAccount)
        defer { clearService(service) }

        KeychainCredentialStore(service: service).clearTokens(for: legacyAccount)

        XCTAssertEqual(keychainStatus(service: service, account: legacyAccount), errSecItemNotFound)
    }

    func testClearRemovesRawUnicodeLegacyAccount() throws {
        let service = "com.unsupportedpastels.mercury.tests.\(UUID().uuidString)"
        let legacyAccount = "https://faß.de"
        try seed(TokenPair(accessToken: Data("inert".utf8)), service: service, account: legacyAccount)
        defer { clearService(service) }

        KeychainCredentialStore(service: service).clearTokens(for: legacyAccount)

        XCTAssertEqual(keychainStatus(service: service, account: legacyAccount), errSecItemNotFound)
    }

    private func seed(_ pair: TokenPair, service: String, account: String) throws {
        XCTAssertEqual(SecItemAdd([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: try JSONEncoder().encode(pair),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ] as CFDictionary, nil), errSecSuccess)
    }

    private func clearService(_ service: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ] as CFDictionary)
    }

    private func keychainStatus(service: String, account: String) -> OSStatus {
        SecItemCopyMatching([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] as CFDictionary, nil)
    }
}
