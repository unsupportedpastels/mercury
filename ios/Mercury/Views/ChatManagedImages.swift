import SwiftUI

extension ChatView {
    // MARK: Managed images

    /// Managed MEDIA:/markdown image artifacts in a completed assistant
    /// message, rendered as authenticated inline images (M6.4). Only
    /// server-managed absolute paths render; remote URLs stay links.
    @ViewBuilder
    func managedImages(in text: String) -> some View {
        let artifacts = MediaDirectiveExtractor.extract(text)
            .filter { ($0.origin == .managedPath && $0.type == .image) || $0.type == .video }
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
        let scope = managedImageScope
        if appModel.activeRelayTarget != nil {
            guard let live = state.connection else { throw ChatError.transport("Connect the Relay chat to load images") }
            let bytes = try await RelayImageReader.shared.read(profile: appModel.activeProfile, path: path) { method, params in
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
        let client = makeHTTPClient(origin: origin)
        let (data, response) = try await client.get(
            path: "/api/files/download",
            queryItems: [URLQueryItem(name: "path", value: path)]
        )
        guard (200..<300).contains(response.statusCode),
              (response.value(forHTTPHeaderField: "Content-Type") ?? "")
                  .lowercased().hasPrefix("image/"),
              data.count <= 10 * 1024 * 1024 else {
            throw URLError(.cannotDecodeContentData)
        }
        try Task.checkCancellation()
        guard managedImageScope == scope else { throw CancellationError() }
        return data
    }
}
