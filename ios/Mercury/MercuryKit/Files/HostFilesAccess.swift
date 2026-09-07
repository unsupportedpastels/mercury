import Foundation

/// The host-files browser is a direct Hermes REST feature. Relay is a separate
/// transport and is usable here only if it advertises an equivalent contract;
/// this client has no relay filesystem route to call.
enum HostFilesCapability: Equatable, Sendable {
    case direct
    case relayUnsupported
    case unavailable
}

enum HostFilesAccessError: Error, Equatable {
    case relayUnsupported
    case directOriginUnavailable
}

/// Creates an origin-scoped managed-files client without making views snapshot
/// a bearer token. Refreshes read the current credential pair and persist the
/// rotated pair, matching the existing Hermes native refresh contract.
enum HostFilesAccess {
    static func capability(origin: String?, relayActive: Bool) -> HostFilesCapability {
        if relayActive { return .relayUnsupported }
        guard let origin, ServerOrigin.normalize(origin) != nil else { return .unavailable }
        return .direct
    }

    static func makeClient(
        origin: String?,
        relayActive: Bool,
        urlSession: URLSession = HermesURLSession.noRedirects,
        credentialStore: CredentialStoring = KeychainCredentialStore()
    ) throws -> HostFilesClient {
        if relayActive { throw HostFilesAccessError.relayUnsupported }
        guard let origin else { throw HostFilesAccessError.directOriginUnavailable }
        return try makeDirectClient(
            origin: origin,
            urlSession: urlSession,
            credentialStore: credentialStore
        )
    }

    static func makeDirectClient(
        origin rawOrigin: String,
        urlSession: URLSession = HermesURLSession.noRedirects,
        credentialStore: CredentialStoring = KeychainCredentialStore()
    ) throws -> HostFilesClient {
        guard let origin = ServerOrigin.normalize(rawOrigin) else {
            throw HostFilesClientError.invalidOrigin
        }

        // Reuse the app's authenticated-client factory rather than duplicating
        // token lookup/refresh here. It reads the current origin-scoped pair on
        // each 401, applies TokenRefreshPolicy, persists rotated credentials,
        // and keeps provider routing identical to the rest of the app.
        let authenticated = HermesHTTPClient.makeAuthenticated(
            origin: origin,
            urlSession: urlSession,
            credentialStore: credentialStore
        )

        guard let accessToken = authenticated.bearerToken,
              !accessToken.isEmpty else {
            // Basic/password sessions are cookie-authenticated. The shared
            // Hermes session carries the HttpOnly cookie and no stale bearer.
            return try HostFilesClient(
                cookieAuthenticatedOrigin: origin,
                session: urlSession
            )
        }

        return try HostFilesClient(
            origin: origin,
            bearerToken: accessToken,
            session: urlSession,
            refreshProvider: {
                guard let refreshProvider = authenticated.refreshTokenProvider else {
                    throw HermesAuthError.authRejected
                }
                guard let refreshed = await refreshProvider() else {
                    throw HermesAuthError.authRejected
                }
                return refreshed.accessToken
            }
        )
    }
}

/// Folder-picker-only projection of the server's managed-files metadata. It
/// never constructs paths: entries and parent navigation remain server-owned.
enum HostFilesFolderPickerPolicy {
    static func canSelect(_ listing: HostFileListing) -> Bool {
        validCanonicalHostFilePath(listing.path) != nil
    }

    static func directories(in listing: HostFileListing) -> [HostFileEntry] {
        listing.entries.filter(\.isDirectory)
    }

    static func parentPath(in listing: HostFileListing) -> String? {
        // can_change_path governs arbitrary path entry, not Up navigation.
        // Hermes omits parent at the locked root and supplies it for allowed
        // ancestors within that root. Do not invent a stricter client boundary.
        listing.parentPath.flatMap(validCanonicalHostFilePath)
    }
}
