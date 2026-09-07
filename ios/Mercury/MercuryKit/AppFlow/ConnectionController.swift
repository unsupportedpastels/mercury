import Foundation
import MercuryCore
#if canImport(UIKit)
import UIKit
#endif

/// Abstraction over the native PKCE sign-in flow so `ConnectionController`
/// can be driven by fakes in unit tests without binding real loopback
/// listeners.
protocol SelfHostedSignInFlowing {
    func begin() async throws -> URL
    func awaitCallback() async throws -> CallbackResult
    /// Runs the authorization URL in the app-owned browser session and
    /// returns the callback. Test fakes inherit the loopback fallback below.
    func authorizeInSystemBrowser(url: URL) async throws -> CallbackResult
    func exchange(code: String) async throws -> TokenOutcome
    func cancel()
}

extension SelfHostedSignInFlowing {
    /// Backward-compatible fallback for hermetic fakes that already provide a
    /// deterministic callback without opening a real browser.
    func authorizeInSystemBrowser(url: URL) async throws -> CallbackResult {
        try await awaitCallback()
    }
}

extension NativePKCEFlow: SelfHostedSignInFlowing {}

/// Server-backed rows are never local drafts. Conforming `SessionRow` to
/// `MergeableSession` lets refresh route through `SessionListMerge`; iOS has
/// no draft rows yet, so with an empty `pendingDraftIDs` the server list
/// lands verbatim.
extension SessionRow: MergeableSession {
    var isLocalDraft: Bool { false }
}

/// Start of the Portal cloud sign-in: a device code plus the URL the user
/// must visit to authorize it.
struct PortalStart: Equatable {
    let deviceCode: DeviceCode
    let verificationURL: URL
}

/// Real orchestration for Mercury's connection lifecycle.
///
/// Owns every network hop (status probe, auth-provider discovery, native
/// PKCE sign-in, session listing, Portal device flow) and writes results
/// into `AppModel` via its controller-facing setters. All network work
/// happens off the main actor via async calls; only state writes land on
/// the main actor.
///
/// Logging policy: this type never logs tokens, authorization codes,
/// cookies, or full URLs carrying query strings.
@MainActor
final class ConnectionController {

    // MARK: - Dependencies

    private unowned let appModel: AppModel

    /// Session used for all Hermes + Portal HTTP traffic. Tests inject an
    /// ephemeral session whose `protocolClasses` include a mock URLProtocol.
    private let urlSession: URLSession
    /// Session for Hermes-origin traffic (password login, bearer clients). It
    /// refuses redirects; `urlSession` stays on the system default for the
    /// Nous cloud clients only.
    private let hermesURLSession: URLSession

    private let credentialStore: CredentialStoring

    /// Builds the sign-in flow for a given origin. Injectable for tests.
    private let signInFlowFactory: @MainActor (String) -> SelfHostedSignInFlowing

    /// Optional profile seams. Production uses the official direct REST
    /// endpoint and the existing relay pool read channel; tests inject real
    /// `ProfilesClient` instances or deterministic fakes.
    private let directProfilesClientFactory: (@MainActor (String) -> any ProfilesListing)?
    private let relayProfilesClientFactory: (@MainActor (RelayPairedTarget, String) async throws -> any ProfilesListing)?
    private let relaySessionsPageLoader: (@MainActor (RelayPairedTarget, String, Int, Int) async throws -> SessionPage)?
    private let sessionPageLoader: (@MainActor (String, String, Int, Int) async throws -> SessionPage)?

    /// Monotonic controller identity. Cancellation is an optimization; these
    /// generations are the publication fence for non-cooperative responses.
    private var connectionGeneration: UInt64 = 0
    private var sessionRequestGeneration: UInt64 = 0

    private struct SessionRequestScope: Equatable {
        let origin: String?
        let relayTargetID: UUID?
        let relaySelectionGeneration: UInt64
        let profile: String
        let connectionGeneration: UInt64
    }

    /// Scope of the rows currently known to be an authoritative server page.
    /// Cached/startup rows are intentionally unscoped until a refresh succeeds;
    /// this prevents pagination from appending a new profile/host to stale rows.
    private var publishedSessionScope: SessionRequestScope?

    /// Opens a URL in the user's browser. Injectable so tests never launch
    /// Safari. Default routes through `UIApplication.shared.open`.
    var openExternalURL: @MainActor (URL) async -> Void = ConnectionController.defaultURLOpener

    #if canImport(UIKit)
    private static let defaultURLOpener: @MainActor (URL) async -> Void = { url in
        _ = await UIApplication.shared.open(url)
    }
    #else
    private static let defaultURLOpener: @MainActor (URL) async -> Void = { _ in }
    #endif

    init(
        appModel: AppModel,
        urlSession: URLSession = .shared,
        credentialStore: CredentialStoring = KeychainCredentialStore(),
        signInFlowFactory: @escaping @MainActor (String) -> SelfHostedSignInFlowing = { NativePKCEFlow(origin: $0) },
        directProfilesClientFactory: (@MainActor (String) -> any ProfilesListing)? = nil,
        relayProfilesClientFactory: (@MainActor (RelayPairedTarget, String) async throws -> any ProfilesListing)? = nil,
        relaySessionsPageLoader: (@MainActor (RelayPairedTarget, String, Int, Int) async throws -> SessionPage)? = nil,
        sessionPageLoader: (@MainActor (String, String, Int, Int) async throws -> SessionPage)? = nil
    ) {
        self.appModel = appModel
        self.urlSession = urlSession
        // An injected session (tests, mocks) carries every request; the
        // production default must never let a Hermes bearer follow a 3xx.
        self.hermesURLSession = urlSession === URLSession.shared ? HermesURLSession.noRedirects : urlSession
        self.credentialStore = credentialStore
        self.signInFlowFactory = signInFlowFactory
        self.directProfilesClientFactory = directProfilesClientFactory
        self.relayProfilesClientFactory = relayProfilesClientFactory
        self.relaySessionsPageLoader = relaySessionsPageLoader
        self.sessionPageLoader = sessionPageLoader
    }

    // MARK: - Self-hosted probe

    /// Validates the entered origin and probes `GET /api/status`.
    ///
    /// Phase outcomes:
    /// - invalid origin → `.failed("Enter a valid server address…")`
    /// - reachable, no auth required → `.connected`
    /// - reachable, auth required + "nous" provider → `.signInRequired`
    /// - reachable, auth required without "nous" provider → `.failed(…)`
    /// - 401/403 → `.failed("Server rejected the connection")`
    /// - 5xx / transport failure → reachability failure message
    func probeSelfHosted(origin rawOrigin: String) async {
        guard let origin = ServerOrigin.normalize(rawOrigin) else {
            appModel.setPhase(.failed("Enter a valid server address, e.g. hermes.example.com"))
            return
        }

        let generation = beginDirectConnectionBoundary(origin: origin)
        appModel.setServerOrigin(origin)
        appModel.setActiveRelayTarget(nil)
        appModel.setPhase(.probing)
        appModel.setAuthProviders([])
        appModel.setAuthenticationError(nil)

        let client = makeHTTPClient(origin: origin)
        let probe = StatusProbe(client: client)

        let status: HermesStatus
        do {
            status = try await probe.probe()
        } catch {
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setPhase(.failed(Self.failureMessage(for: error, origin: origin)))
            return
        }

        guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
        await appModel.rememberServer(origin: origin)
        guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }

        appModel.setHermesVersion(status.version.isEmpty ? nil : status.version)

        guard status.authRequired else {
            appModel.setPhase(.connected)
            await discoverDirectProfiles(origin: origin, generation: generation)
            return
        }

        if credentialStore.tokens(for: origin) != nil || hasSessionCookie(for: origin) {
            do {
                let (_, response) = try await client.get(path: "/api/auth/me")
                guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
                if (200..<300).contains(response.statusCode) {
                    appModel.setPhase(.connected)
                    await discoverDirectProfiles(origin: origin, generation: generation)
                    return
                }
                if response.statusCode != 401 && response.statusCode != 403 {
                    appModel.setPhase(.failed("Hermes authentication is temporarily unavailable."))
                    return
                }
            } catch {
                guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
                appModel.setPhase(.failed(Self.failureMessage(for: error, origin: origin)))
                return
            }
        }

        do {
            let providers = try await probe.authProviders()
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setAuthProviders(providers.providers)
            if providers.providers.contains(where: {
                $0.name.lowercased() == "nous" || $0.supportsPassword
            }) {
                appModel.setPhase(.signInRequired)
            } else {
                appModel.setPhase(.failed(
                    "This server requires an authentication method Mercury does not support."
                ))
            }
        } catch {
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setPhase(.failed(Self.failureMessage(for: error, origin: origin)))
        }
    }

    // MARK: - Self-hosted sign-in

    /// Runs the native PKCE sign-in end to end: builds the authorize URL,
    /// opens it in the browser, awaits the loopback callback, exchanges the
    /// code, and persists the result.
    ///
    /// Persistence:
    /// - JSON token outcome → `TokenPair` in the Keychain scoped to the origin.
    /// - Cookie outcome → cookies merged into `HTTPCookieStorage.shared` for
    ///   the origin's domain (the shared store replays them automatically).
    ///
    /// On any failure the phase becomes `.failed("Sign-in failed")`; no token
    /// material ever appears in error strings or logs.
    func startSelfHostedSignIn() async {
        guard let origin = appModel.serverOrigin else {
            appModel.setPhase(.failed("Connect to a server before signing in."))
            return
        }

        let generation = beginDirectConnectionBoundary(origin: origin)
        appModel.setSigningIn(true)
        appModel.setAuthenticationError(nil)
        defer { appModel.setSigningIn(false) }

        let flow = signInFlowFactory(origin)
        do {
            let authorizeURL = try await flow.begin()
            authDebug("begin-complete")
            // UI-test hook: expose the authorize URL (contains no secret —
            // it carries only the PKCE challenge and state) so a host-side
            // browser can complete the Portal hop during automated sign-in.
            if ProcessInfo.processInfo.arguments.contains("-uitest-expose-auth-url") {
                FileHandle.standardError.write(Data("AUTHPARAMS \(authorizeURL.absoluteString)\n".utf8))
            }

            // Do not hand the URL to standalone Safari. iOS can suspend the
            // Mercury process in that mode, making its loopback listener
            // unreachable exactly when the Portal redirects back. The native
            // ASWebAuthenticationSession keeps the transaction app-owned and
            // delivers the callback directly to this process.
            let callback = try await flow.authorizeInSystemBrowser(url: authorizeURL)
            authDebug("callback-received")
            let outcome = try await flow.exchange(code: callback.code)
            authDebug("token-exchange-complete")

            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            persist(outcome: outcome, origin: origin)
            await appModel.rememberServer(origin: origin)
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setPhase(.connected)
            await discoverDirectProfiles(origin: origin, generation: generation)
            authDebug("connected")
        } catch {
            authDebug("failed")
            flow.cancel()
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            if case FlowError.stateMismatch = error {
                appModel.setPhase(.failed("Sign-in failed"))
            } else {
                // Portal authentication can require a second browser hop or
                // return a transient rejection. Keep the user on the sign-in
                // screen with a retryable state instead of requiring an app
                // restart to clear the in-flight button state.
                appModel.setAuthenticationError("Sign-in did not complete. Try again.")
                appModel.setPhase(.signInRequired)
            }
        }
    }

    /// Android-parity cookie-backed username/password sign-in. The server must
    /// explicitly advertise a provider with `supports_password`; credentials
    /// are bounded before the request, never persisted, and never logged.
    func startPasswordSignIn(username: String, password: String) async {
        guard case .signInRequired = appModel.connectionPhase,
              let origin = appModel.serverOrigin,
              let provider = appModel.authProviders.first(where: { $0.supportsPassword }) else {
            return
        }

        let generation = beginDirectConnectionBoundary(origin: origin)
        appModel.setSigningIn(true)
        appModel.setAuthenticationError(nil)
        defer { appModel.setSigningIn(false) }

        do {
            try await PasswordLoginClient(session: hermesURLSession).signIn(
                origin: origin,
                provider: provider.name,
                username: username,
                password: password
            )
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }

            // Android stores the password flow's intentionally-empty access
            // token before validation. Clearing the origin-scoped bearer is
            // the iOS equivalent: Hermes middleware gives Authorization
            // precedence over cookies, so a stale OAuth token would otherwise
            // override the fresh basic-auth cookie and produce a false 401.
            credentialStore.clearTokens(for: origin)

            // Match Android's post-login `authenticate(...)` check: a 2xx
            // login page is not success until the issued cookie opens an
            // authenticated API endpoint.
            let (_, response) = try await makeHTTPClient(origin: origin).get(path: "/api/auth/me")
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            guard (200..<300).contains(response.statusCode) else {
                if response.statusCode == 401 || response.statusCode == 403 {
                    throw PasswordLoginError.invalidCredentials
                }
                throw PasswordLoginError.transient(response.statusCode)
            }

            await appModel.rememberServer(origin: origin)
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setAuthenticationError(nil)
            appModel.setPhase(.connected)
            await discoverDirectProfiles(origin: origin, generation: generation)
        } catch is CancellationError {
            return
        } catch PasswordLoginError.invalidCredentials {
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setAuthenticationError("Invalid username or password.")
            appModel.setPhase(.signInRequired)
        } catch {
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.setAuthenticationError("Password sign-in is temporarily unavailable.")
            appModel.setPhase(.signInRequired)
        }
    }

    private func authDebug(_ message: String) {
        guard ProcessInfo.processInfo.arguments.contains("-uitest-auth-debug") else { return }
        FileHandle.standardError.write(Data("AUTHDEBUG \(message)\n".utf8))
    }

    // MARK: - Mercury Relay

    /// Connects through a paired relay target: proves admission with one
    /// in-process read, seeds the session list, and enters `.connected` with
    /// no server origin (origin-scoped REST features stand down on nil).
    func connectRelay(target: RelayPairedTarget) async {
        let generation = beginRelayConnectionBoundary(target: target)
        let selectionGeneration = appModel.relaySelectionGeneration
        let profile = appModel.activeProfile
        await RelayConnectionPool.shared.select(target: target, profile: profile)
        guard isCurrentRelayConnection(
            target: target,
            profile: profile,
            generation: generation,
            selectionGeneration: selectionGeneration
        ) else { return }
        appModel.setActiveRelayTarget(nil)
        appModel.setServerOrigin(nil)
        appModel.setPhase(.connecting)
        do {
            // Discovery is best-effort. A released Hermes host may not expose
            // profiles.list; that must not turn a successful session-list read
            // into a failed authenticated connection.
            var discoveredProfiles: [String]?
            do {
                let profilesClient: any ProfilesListing
                if let relayProfilesClientFactory {
                    profilesClient = try await relayProfilesClientFactory(target, profile)
                } else {
                    let connection = try await RelayConnectionPool.shared.acquire(
                        target: target,
                        profile: profile
                    )
                    profilesClient = ProfilesClient(rpcRequest: { method, params in
                        try await connection.relayRequest(method, params: params)
                    })
                }
                discoveredProfiles = try await profilesClient.list()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                discoveredProfiles = nil
            }
            guard isCurrentRelayConnection(
                target: target,
                profile: profile,
                generation: generation,
                selectionGeneration: selectionGeneration
            ) else { return }

            let page: SessionPage
            if let relaySessionsPageLoader {
                page = try await relaySessionsPageLoader(target, profile, 20, 0)
            } else {
                page = try await Self.relaySessionsPage(
                    target: target, profile: profile, limit: 20, offset: 0
                )
            }
            guard isCurrentRelayConnection(
                target: target,
                profile: profile,
                generation: generation,
                selectionGeneration: selectionGeneration
            ) else { return }
            appModel.setActiveRelayTarget(target)
            appModel.profiles = reconciledProfiles(
                discoveredProfiles ?? [], selectedProfile: profile, rpc: true
            )
            appModel.sessions = page.rows
            publishedSessionScope = SessionRequestScope(
                origin: nil,
                relayTargetID: target.id,
                relaySelectionGeneration: selectionGeneration,
                profile: profile,
                connectionGeneration: generation
            )
            appModel.setCanLoadMoreSessions(page.hasMore)
            appModel.setSessionsError(nil)
            appModel.setHermesVersion(nil)
            appModel.setPhase(.connected)
        } catch is CancellationError {
            return
        } catch {
            guard isCurrentRelayConnection(
                target: target,
                profile: profile,
                generation: generation,
                selectionGeneration: selectionGeneration
            ) else { return }
            let message = Self.relayFailureMessage(error)
            // Cached sessions keep the list on screen; the banner carries the
            // reason so a stale list is never mistaken for a live one.
            appModel.setSessionsError(message)
            appModel.setPhase(.failed(message))
        }
    }

    /// User-facing relay failure text, distinct per cause (Android parity).
    static func relayFailureMessage(_ error: Error) -> String {
        switch error as? RelayConnectionError {
        case .routingRejected:
            return "The relay refused this phone's pairing token. Remove this relay and pair again from your host's Mercury Relay page."
        case .noHost:
            return "Your Hermes host isn't connected to the relay. Start Hermes on the host, then retry."
        case .notAuthorized:
            return "The host hasn't approved this device — or it was revoked. Approve it on the host, then try again."
        default:
            return "The relay or host is unreachable. Check that your Hermes host is online, then retry."
        }
    }

    /// One `relay.sessions.list` read over the pool's default lease channel.
    static func relaySessionsPage(
        target: RelayPairedTarget,
        profile: String,
        limit: Int,
        offset: Int
    ) async throws -> SessionPage {
        let connection = try await RelayConnectionPool.shared.acquire(target: target, profile: profile)
        let result = try await connection.relayRequest(
            "relay.sessions.list",
            params: ["profile": profile, "limit": limit, "offset": offset]
        )
        return try RelaySessionPage.decode(result, offset: offset, limit: limit)
    }

    // MARK: - Sessions

    /// Fetches one page of the active profile's sessions.
    ///
    /// Returns `nil` on failure; the previous rows are kept and
    /// `sessionsError` carries a friendly message (never token material).
    func loadSessionsPage(limit: Int = 20, offset: Int = 0) async -> SessionPage? {
        let requestGeneration = beginSessionRequest()
        let scope = captureSessionRequestScope()
        return await loadSessionsPage(
            limit: limit,
            offset: offset,
            requestGeneration: requestGeneration,
            scope: scope
        )
    }

    private func loadSessionsPage(
        limit: Int,
        offset: Int,
        requestGeneration: UInt64,
        scope: SessionRequestScope
    ) async -> SessionPage? {
        guard case .connected = appModel.connectionPhase else { return nil }

        if let targetID = scope.relayTargetID,
           let target = appModel.activeRelayTarget,
           target.id == targetID {
            // Reads use the default lease channel; open chats own their own
            // channels, so a visible chat never blocks a list refresh.
            do {
                let page: SessionPage
                if let relaySessionsPageLoader {
                    page = try await relaySessionsPageLoader(
                        target, scope.profile, limit, offset
                    )
                } else {
                    page = try await Self.relaySessionsPage(
                        target: target,
                        profile: scope.profile,
                        limit: limit,
                        offset: offset
                    )
                }
                guard isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
                    return nil
                }
                return page
            } catch is CancellationError {
                return nil
            } catch {
                guard isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
                    return nil
                }
                appModel.setSessionsError(Self.relayFailureMessage(error))
                return nil
            }
        }

        guard scope.relayTargetID == nil, let origin = scope.origin else { return nil }
        do {
            let page: SessionPage
            if let sessionPageLoader {
                page = try await sessionPageLoader(origin, scope.profile, limit, offset)
            } else {
                let sessionsClient = SessionsClient(
                    client: makeHTTPClient(origin: origin),
                    profile: scope.profile
                )
                page = try await sessionsClient.sessions(limit: limit, offset: offset)
            }
            guard isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
                return nil
            }
            return page
        } catch is CancellationError {
            return nil
        } catch {
            guard isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
                return nil
            }
            appModel.setSessionsError(Self.failureMessage(for: error, origin: origin))
            return nil
        }
    }

    /// Full refresh: the server list is authoritative. Routes through
    /// `SessionListMerge` so future local-draft rows survive a refresh; with
    /// no pending drafts the server list lands verbatim.
    ///
    /// On failure the previous rows are kept and `sessionsError` carries a
    /// friendly message (never token material).
    func refreshSessions() async {
        let requestGeneration = beginSessionRequest()
        let scope = captureSessionRequestScope()
        guard let page = await loadSessionsPage(
            limit: 20,
            offset: 0,
            requestGeneration: requestGeneration,
            scope: scope
        ), isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
            return
        }
        let merged = SessionListMerge.merged(
            serverSessions: page.rows,
            currentSessions: appModel.sessions,
            pendingDraftIDs: []
        )
        guard isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
            return
        }
        appModel.sessions = merged
        publishedSessionScope = scope
        appModel.pruneLifecycleMirrors(keeping: Set(merged.map(\.id)))
        appModel.setCanLoadMoreSessions(page.hasMore)
        appModel.setSessionsError(nil)
        if let origin = scope.origin {
            await appModel.cacheSessionMetadata(origin: origin, profile: scope.profile, rows: merged)
        }
    }

    /// Back-compat alias: page-1 refresh.
    func loadSessions() async {
        await refreshSessions()
    }

    /// Appends the next page of sessions to the list.
    ///
    /// No-op when there is no more to load, an append is already in flight,
    /// or the app is not connected. Fetches `offset = sessions.count`,
    /// dedupes overlapping ids against the current rows, preserves order, and
    /// updates `canLoadMoreSessions` from `SessionPage.hasMore`.
    func loadNextSessionsPage() async {
        guard appModel.canLoadMoreSessions, !appModel.isLoadingMoreSessions else { return }
        guard case .connected = appModel.connectionPhase else { return }

        appModel.setIsLoadingMoreSessions(true)
        defer { appModel.setIsLoadingMoreSessions(false) }

        let scope = captureSessionRequestScope()
        guard publishedSessionScope == scope else {
            return
        }
        let requestGeneration = beginSessionRequest()
        let offset = appModel.sessions.count
        guard let page = await loadSessionsPage(
            limit: 20,
            offset: offset,
            requestGeneration: requestGeneration,
            scope: scope
        ), isCurrentSessionRequest(requestGeneration: requestGeneration, scope: scope) else {
            return
        }
        let existingIDs = Set(appModel.sessions.map(\.id))
        let newRows = page.rows.filter { !existingIDs.contains($0.id) }
        appModel.sessions.append(contentsOf: newRows)
        appModel.setCanLoadMoreSessions(page.hasMore)
        appModel.setSessionsError(nil)
    }

    // MARK: - Session lifecycle (optimistic)

    /// Applies a title/archive/pin change to the matching row immediately,
    /// then persists it via `PATCH /api/sessions/{id}`. Reverts the local
    /// change on failure and surfaces a friendly error banner message.
    func updateSession(id: String, archived: Bool? = nil, pinned: Bool? = nil, title: String? = nil) async {
        guard case .connected = appModel.connectionPhase, let origin = appModel.serverOrigin else {
            return
        }
        guard let index = appModel.sessions.firstIndex(where: { $0.id == id }) else { return }
        let priorTitle = appModel.sessions[index].title
        let priorArchived = appModel.sessionArchived[id]
        let priorPinned = appModel.sessionPinned[id]

        appModel.applyLifecycleUpdate(id: id, title: title, archived: archived, pinned: pinned)

        let lifecycle = SessionLifecycleClient(client: makeHTTPClient(origin: origin))
        do {
            _ = try await lifecycle.update(
                sessionID: id,
                title: title,
                archived: archived,
                pinned: pinned,
                profile: appModel.activeProfile
            )
        } catch {
            appModel.restoreLifecycle(
                id: id,
                priorTitle: priorTitle,
                priorArchived: priorArchived,
                priorPinned: priorPinned
            )
            appModel.setSessionsError(Self.failureMessage(for: error, origin: origin))
        }
    }

    /// Removes a session locally immediately, then persists deletion via
    /// `DELETE /api/sessions/{id}`. Re-inserts the row at its original
    /// position if the delete fails.
    func deleteSession(id: String) async {
        guard case .connected = appModel.connectionPhase, let origin = appModel.serverOrigin else {
            return
        }
        guard let index = appModel.sessions.firstIndex(where: { $0.id == id }) else { return }
        let removed = appModel.sessions.remove(at: index)
        appModel.pruneLifecycleMirrors(keeping: Set(appModel.sessions.map(\.id)))

        let lifecycle = SessionLifecycleClient(client: makeHTTPClient(origin: origin))
        do {
            try await lifecycle.delete(sessionID: id, profile: appModel.activeProfile)
        } catch {
            appModel.sessions.insert(removed, at: min(index, appModel.sessions.count))
            appModel.setSessionsError(Self.failureMessage(for: error, origin: origin))
        }
    }

    // MARK: - Cloud (Portal device flow)

    /// Starts the Portal OAuth device authorization grant and returns the
    /// device code plus the verification URL to open in the browser.
    func startCloudSignIn() async throws -> PortalStart {
        let portal = PortalClient(origin: PortalClient.defaultOrigin, session: urlSession)
        let deviceCode = try await portal.startDeviceCode()

        appModel.setPendingPortalDeviceCode(deviceCode)

        // Prefer the pre-filled URL when the server offers one.
        let urlString = deviceCode.verificationURIComplete ?? deviceCode.verificationURI
        guard let url = URL(string: urlString) else {
            throw FlowError.badResponse
        }
        return PortalStart(deviceCode: deviceCode, verificationURL: url)
    }

    /// Polls the Portal token endpoint once. On success the rotated token set
    /// is persisted in the Keychain under the portal account.
    func pollCloudOnce(deviceCode: DeviceCode, interval: Int? = nil) async throws -> PortalClient.DevicePollOutcome {
        let portal = PortalClient(origin: PortalClient.defaultOrigin, session: urlSession)
        let outcome = try await portal.pollDeviceCode(
            deviceCode: deviceCode.deviceCode,
            interval: interval ?? deviceCode.interval
        )
        if case .success(let tokens) = outcome {
            persistPortalTokens(tokens)
        }
        return outcome
    }

    func discoverCloudAgents(accessToken: String, org: String?) async throws -> AgentDiscovery {
        do {
            let agentsClient = AgentsClient(session: urlSession)
            let result: AgentsResult
            do {
                result = try await agentsClient.agents(
                    origin: PortalClient.defaultOrigin,
                    accessToken: accessToken,
                    org: org
                )
            } catch AgentsError.invalidToken {
                guard let current = storedPortalTokens(), current.refreshToken != nil else {
                    throw AgentsError.invalidToken
                }
                let portal = PortalClient(origin: PortalClient.defaultOrigin, session: urlSession)
                let refreshed = try await portal.refresh(current)
                persistPortalTokens(refreshed)
                result = try await agentsClient.agents(
                    origin: PortalClient.defaultOrigin,
                    accessToken: refreshed.accessToken,
                    org: org
                )
            }
            let agents = result.agents.map { row in
                CloudAgent(
                    id: row.id,
                    name: row.name ?? row.id,
                    status: row.status ?? "unknown",
                    dashboardURL: row.dashboardURL,
                    gatewayState: row.dashboardGatewayState
                )
            }
            let resolvedOrg = result.org.flatMap { row -> PortalOrg? in
                guard let slug = row.slug, !slug.isEmpty else { return nil }
                return PortalOrg(id: slug, slug: slug, name: row.name ?? slug)
            }
            return AgentDiscovery(agents: agents, org: resolvedOrg)
        } catch AgentsError.orgSelectionRequired(let options) {
            throw OrgSelectionRequiredError(choices: options.map {
                OrgChoice(id: $0.slug, slug: $0.slug, name: $0.name ?? $0.slug)
            })
        }
    }

    func storedPortalTokens() -> TokenSet? {
        guard let pair = credentialStore.tokens(for: Self.portalAccountKey),
              let access = String(data: pair.accessToken, encoding: .utf8),
              !access.isEmpty else { return nil }
        let refresh = pair.refreshToken.flatMap { String(data: $0, encoding: .utf8) }
        return TokenSet(accessToken: access, refreshToken: refresh)
    }

    /// Connects Mercury to a discovered cloud agent by probing its dashboard
    /// origin with the same logic as a self-hosted probe.
    func selectAgent(_ agent: CloudAgent) async {
        guard let dashboardURL = agent.dashboardURL else {
            appModel.setPhase(.failed("This agent has no dashboard address yet."))
            return
        }
        appModel.setPhase(.connecting)
        await probeSelfHosted(origin: dashboardURL)
    }

    // MARK: - Internals

    private func clearUnscopedSessionRows() {
        appModel.sessions = []
        appModel.setCanLoadMoreSessions(false)
        appModel.setSessionsError(nil)
        appModel.setIsLoadingMoreSessions(false)
    }

    /// Parent-owned AppModel transitions can call this before disconnect/reset.
    /// It invalidates every in-flight catalog/session response and removes the
    /// previous host's profile scope without changing credentials.
    func resetProfileCatalogForConnectionBoundary() {
        connectionGeneration &+= 1
        sessionRequestGeneration &+= 1
        publishedSessionScope = nil
        clearUnscopedSessionRows()
        appModel.profiles = ["default"]
        appModel.setActiveProfile("default")
    }

    private func beginDirectConnectionBoundary(origin: String) -> UInt64 {
        connectionGeneration &+= 1
        sessionRequestGeneration &+= 1
        publishedSessionScope = nil
        if appModel.activeRelayTarget != nil || appModel.serverOrigin != nil && appModel.serverOrigin != origin {
            clearUnscopedSessionRows()
        }
        appModel.profiles = ["default"]
        appModel.setActiveProfile("default")
        appModel.setActiveRelayTarget(nil)
        return connectionGeneration
    }

    private func beginRelayConnectionBoundary(target: RelayPairedTarget) -> UInt64 {
        connectionGeneration &+= 1
        sessionRequestGeneration &+= 1
        publishedSessionScope = nil
        // A different relay is a different host scope. Never carry the prior
        // host's selected profile into its admission; reconnecting the same
        // target may retain the user's current profile.
        if appModel.activeRelayTarget?.id != target.id {
            appModel.setActiveProfile("default")
        }
        // Keep only the profile used for admission visible while the new host
        // catalog is loading; do not expose the previous host's catalog.
        appModel.profiles = [appModel.activeProfile]
        return connectionGeneration
    }

    private func beginSessionRequest() -> UInt64 {
        sessionRequestGeneration &+= 1
        return sessionRequestGeneration
    }

    private func captureSessionRequestScope() -> SessionRequestScope {
        let relayTargetID = appModel.activeRelayTarget?.id
        return SessionRequestScope(
            origin: relayTargetID == nil ? appModel.serverOrigin : nil,
            relayTargetID: relayTargetID,
            relaySelectionGeneration: appModel.relaySelectionGeneration,
            profile: appModel.activeProfile,
            connectionGeneration: connectionGeneration
        )
    }

    private func isCurrentDirectConnection(origin: String, generation: UInt64) -> Bool {
        connectionGeneration == generation &&
            appModel.serverOrigin == origin &&
            appModel.activeRelayTarget == nil
    }

    private func isCurrentRelayConnection(
        target: RelayPairedTarget,
        profile: String,
        generation: UInt64,
        selectionGeneration: UInt64
    ) -> Bool {
        connectionGeneration == generation &&
            appModel.relaySelectionGeneration == selectionGeneration &&
            appModel.selectedRelayTarget?.id == target.id &&
            appModel.activeProfile == profile
    }

    private func isCurrentSessionRequest(
        requestGeneration: UInt64,
        scope: SessionRequestScope
    ) -> Bool {
        guard sessionRequestGeneration == requestGeneration,
              connectionGeneration == scope.connectionGeneration,
              appModel.activeProfile == scope.profile,
              case .connected = appModel.connectionPhase else {
            return false
        }
        if let targetID = scope.relayTargetID {
            return appModel.serverOrigin == nil &&
                appModel.relaySelectionGeneration == scope.relaySelectionGeneration &&
                appModel.activeRelayTarget?.id == targetID
        }
        return appModel.activeRelayTarget == nil && appModel.serverOrigin == scope.origin
    }

    private func discoverDirectProfiles(origin: String, generation: UInt64) async {
        guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
        do {
            let client: any ProfilesListing
            if let directProfilesClientFactory {
                client = directProfilesClientFactory(origin)
            } else {
                client = ProfilesClient(httpClient: makeHTTPClient(origin: origin))
            }
            let names = try await client.list()
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.profiles = reconciledProfiles(
                names, selectedProfile: appModel.activeProfile, rpc: false
            )
        } catch is CancellationError {
            return
        } catch {
            guard isCurrentDirectConnection(origin: origin, generation: generation) else { return }
            appModel.profiles = reconciledProfiles(
                [], selectedProfile: appModel.activeProfile, rpc: false
            )
        }
    }

    private func reconciledProfiles(
        _ discovered: [String],
        selectedProfile: String,
        rpc: Bool
    ) -> [String] {
        let core = MercuryCore.ProfileCatalogPolicy.shared
        let sanitized = rpc
            ? Array(core.sanitizeRpcNames(names: discovered))
            : Array(core.sanitizeRestNames(names: discovered))
        let capacity = rpc ? Int(core.maxRpcProfiles) : Int(core.maxRestProfiles)
        var names = sanitized.isEmpty ? ["default"] : sanitized
        if !names.contains("default") {
            if names.count >= capacity { names.removeLast() }
            names.insert("default", at: 0)
        }
        if rpc,
           !names.contains(selectedProfile),
           core.isValidRpcName(name: selectedProfile) {
            if names.count >= capacity { names.removeLast() }
            names.append(selectedProfile)
        }
        return Array(names.prefix(capacity))
    }

    private func makeHTTPClient(origin: String) -> HermesHTTPClient {
        HermesHTTPClient.makeAuthenticated(
            origin: origin,
            urlSession: hermesURLSession,
            credentialStore: credentialStore
        )
    }

    private func hasSessionCookie(for origin: String) -> Bool {
        guard let url = URL(string: origin) else { return false }
        return !(HTTPCookieStorage.shared.cookies(for: url) ?? []).isEmpty
    }

    /// Persists a successful self-hosted sign-in outcome. Never logged.
    private func persist(outcome: TokenOutcome, origin: String) {
        switch outcome {
        case .json(let accessToken, let refreshToken):
            credentialStore.setTokens(
                TokenPair(
                    accessToken: Data(accessToken.utf8),
                    refreshToken: refreshToken.map { Data($0.utf8) }
                ),
                for: origin
            )
        case .cookies(let dict):
            mergeCookiesIntoSharedStore(dict, origin: origin)
        }
    }

    /// Persists portal tokens under the fixed portal account key.
    private func persistPortalTokens(_ tokens: TokenSet) {
        credentialStore.setTokens(
            TokenPair(
                accessToken: Data(tokens.accessToken.utf8),
                refreshToken: tokens.refreshToken.map { Data($0.utf8) }
            ),
            for: Self.portalAccountKey
        )
    }

    static let portalAccountKey = "portal.nousresearch.com"

    /// Merges cookie name→value pairs into the process-wide cookie store for
    /// the origin's host so subsequent requests replay them.
    private func mergeCookiesIntoSharedStore(_ dict: [String: String], origin: String) {
        guard let url = URL(string: origin), let host = url.host else { return }
        for (name, value) in dict {
            guard let cookie = HTTPCookie(
                properties: [
                    .domain: host,
                    .path: "/",
                    .name: name,
                    .value: value,
                    .secure: url.scheme == "https" ? "TRUE" : "FALSE",
                ]
            ) else { continue }
            HTTPCookieStorage.shared.setCookie(cookie)
        }
    }

    /// Maps a thrown error to a friendly, secret-free message.
    static func failureMessage(for error: Error, origin: String) -> String {
        if let authError = error as? HermesAuthError {
            switch authError {
            case .authRejected:
                return "Server rejected the connection"
            case .transient:
                break
            }
        }
        // Decoding failures, transport errors, and anything unexpected all read
        // as unreachable from the user's perspective; no underlying details are
        // surfaced.
        return "Could not reach \(origin) — check the address and your network"
    }

    // MARK: - Sign-out

    /// Signs out of `origin` completely: clears its Keychain credentials,
    /// purges every shared-store cookie belonging to the origin's host, and
    /// resets transient connection state (Android parity:
    /// `HermesConnectionViewModel.publishSignInRequired()` /
    /// `ServerSettingsViewModel` forget-server semantics).
    ///
    /// Nothing that could leak across origins survives: only the given
    /// origin's keychain item is deleted (no enumeration), and only cookies
    /// scoped to that host are removed.
    func signOut(origin rawOrigin: String) async {
        guard let origin = ServerOrigin.normalize(rawOrigin) else { return }

        resetProfileCatalogForConnectionBoundary()
        credentialStore.clearTokens(for: origin)
        purgeCookies(hostOf: origin)
        try? await appModel.offlineCacheStore.clearForLogout(origin: origin)

        appModel.signedOutPreservingServer(origin)
    }

    /// Removes all shared-store cookies whose domain covers (or is covered
    /// by) the origin's host. Cookies for any other host are left untouched.
    private func purgeCookies(hostOf origin: String) {
        guard let url = URL(string: origin), let host = url.host?.lowercased() else { return }
        let storage = HTTPCookieStorage.shared
        for cookie in storage.cookies ?? [] where Self.cookie(cookie, coversHost: host) {
            storage.deleteCookie(cookie)
        }
    }

    /// True when `cookie` would be replayed for requests to `host`.
    /// Leading-dot cookie domains are stripped; both exact and subdomain
    /// relationships count (`api.hermes.test` cookies cover `hermes.test`
    /// sign-out and vice versa).
    static func cookie(_ cookie: HTTPCookie, coversHost host: String) -> Bool {
        var domain = cookie.domain.lowercased()
        if domain.hasPrefix(".") { domain.removeFirst() }
        return host == domain || host.hasSuffix("." + domain) || domain.hasSuffix("." + host)
    }
}
