import SwiftUI

enum ChatManagedImagePolicy {
    static func artifacts(in text: String) -> [Artifact] {
        let managedImageIdentities = Set(MediaDirectiveExtractor.managedImageArtifacts(text).map(\.stableIdentity))
        return MediaDirectiveExtractor.extract(text).filter {
            ($0.origin == .managedPath && $0.type == .image
                && managedImageIdentities.contains($0.stableIdentity)) || $0.type == .video
        }
    }
}

enum ManagedImageLoader {
    static func direct(client: HermesHTTPClient, path: String) async throws -> Data {
        try await client.downloadManagedImage(path: path)
    }

    static func relay(
        reader: RelayImageReader = .shared,
        profile: String,
        path: String,
        request: @MainActor (String, [String: Any]) async throws -> [String: Any]
    ) async throws -> Data {
        try await reader.read(profile: profile, path: path, request: request)
    }
}

extension ChatView {
    // MARK: Managed images

    @ViewBuilder
    func completedAssistantMessage(in text: String) -> some View {
        OrderedManagedMessageView(text: text) { source in
            AnyView(
                RemoteManagedImage(path: source, scope: managedImageScope) { path in
                    try await loadManagedImage(path: path)
                }
            )
        }
        ForEach(ChatManagedImagePolicy.artifacts(in: text).filter { $0.type == .video }, id: \.stableIdentity) { artifact in
            if artifact.origin == .remoteURL,
               let url = URL(string: artifact.source), url.scheme?.lowercased() == "https" {
                Link(destination: url) {
                    Label("Open video: \(artifact.displayName)", systemImage: "arrow.up.right.video")
                }
            } else if artifact.origin == .managedPath {
                RemoteManagedVideo(path: artifact.source, scope: managedImageScope,
                                   available: appModel.activeRelayTarget == nil) { path in
                    try await loadManagedVideo(path: path)
                }
            }
        }
    }

    /// Managed MEDIA:/markdown image artifacts in a completed assistant
    /// message, rendered as authenticated inline images (M6.4). Only
    /// server-managed absolute paths render; remote URLs stay links.
    @ViewBuilder
    func managedImages(in text: String) -> some View {
        let artifacts = ChatManagedImagePolicy.artifacts(in: text)
        ForEach(artifacts, id: \.stableIdentity) { artifact in
            Group {
                if artifact.type == .video, artifact.origin == .remoteURL {
                    if let url = URL(string: artifact.source), url.scheme?.lowercased() == "https" {
                        Link(destination: url) {
                            Label("Open video: \(artifact.displayName)", systemImage: "arrow.up.right.video")
                        }
                    }
                } else if artifact.type == .video {
                    RemoteManagedVideo(path: artifact.source, scope: managedImageScope,
                                       available: appModel.activeRelayTarget == nil) { path in
                        try await loadManagedVideo(path: path)
                    }
                } else {
                    RemoteManagedImage(path: artifact.source, scope: managedImageScope) { path in
                        try await loadManagedImage(path: path)
                    }
                }
            }
            .id(managedImageScope + "|" + artifact.source)
        }
    }

    /// Authenticated managed-image fetch: GET /api/files/download?path=…
    /// with the bearer token; image/* content type required; 10 MiB cap
    /// (Android downloadManagedImage parity).
    private var managedImageScope: String {
        #if DEBUG
        if fixtureManagedImageLoader != nil { return "synthetic-managed-image-fixture" }
        #endif
        let transport = appModel.activeRelayTarget.map { "relay:\($0.relayOrigin)|\($0.id)" }
            ?? "direct:\(appModel.serverOrigin ?? "unconfigured")"
        return "\(transport)|\(appModel.activeProfile)|\(state.connection.map { String(describing: ObjectIdentifier($0)) } ?? "disconnected")"
    }

    private func loadManagedVideo(path: String) async throws -> ManagedVideoFile {
        // Relay's image_read base64 contract is not a bounded video file stream.
        // No relay route is invented or used as a direct-mode dependency.
        guard appModel.activeRelayTarget == nil else { throw ManagedVideoUnavailable() }
        guard let origin = appModel.serverOrigin else { throw URLError(.userAuthenticationRequired) }
        let scope = managedImageScope
        let profile = appModel.activeProfile
        try Task.checkCancellation()
        let file = try await makeHTTPClient(origin: origin).downloadManagedVideo(path: path, profile: profile)
        try Task.checkCancellation()
        guard managedImageScope == scope, appModel.activeRelayTarget == nil,
              appModel.serverOrigin == origin, appModel.activeProfile == profile else { throw CancellationError() }
        return file
    }

    private func loadManagedImage(path: String) async throws -> Data {
        #if DEBUG
        if let fixtureManagedImageLoader {
            return try await fixtureManagedImageLoader(path)
        }
        #endif
        let scope = managedImageScope
        if appModel.activeRelayTarget != nil {
            guard let live = state.connection else { throw ChatError.transport("Connect the Relay chat to load images") }
            let bytes = try await ManagedImageLoader.relay(profile: appModel.activeProfile, path: path) { method, params in
                try Task.checkCancellation()
                guard managedImageScope == scope, state.connection === live else { throw CancellationError() }
                return try await live.relayRequest(method, params: params)
            }
            try Task.checkCancellation()
            guard managedImageScope == scope, state.connection === live else { throw CancellationError() }
            return bytes
        }
        guard let origin = appModel.serverOrigin else {
            throw URLError(.userAuthenticationRequired)
        }
        let data = try await ManagedImageLoader.direct(client: makeHTTPClient(origin: origin), path: path)
        try Task.checkCancellation()
        guard managedImageScope == scope else { throw CancellationError() }
        return data
    }
}
