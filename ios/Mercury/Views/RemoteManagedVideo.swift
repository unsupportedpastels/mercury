import SwiftUI
import AVKit

/// Tap-to-download, then native fullscreen playback of a leased local file.
/// Remote HTTPS artifacts never enter this view or AVPlayer.
struct RemoteManagedVideo: View {
    let path: String
    let scope: String
    var available = true
    var load: (String) async throws -> ManagedVideoFile

    @Environment(\.scenePhase) private var scenePhase
    @State private var loading = false
    @State private var failed = false
    @State private var player: AVPlayer?
    @State private var file: ManagedVideoFile?
    @State private var showPlayer = false
    @State private var work: Task<Void, Never>?
    @State private var generation = UUID()
    @State private var playbackFailed = false
    @State private var itemObservation: NSKeyValueObservation?

    var body: some View {
        Button(action: open) {
            VStack(spacing: 10) {
                if loading { ProgressView("Downloading video…") }
                else { Image(systemName: failed ? "exclamationmark.triangle" : "play.rectangle.fill").font(.largeTitle) }
                Text(path.split(separator: "/").last.map(String.init) ?? "Video")
                    .font(.callout).lineLimit(1)
                Text(!available ? "Video unavailable over Relay" : failed ? "Video unavailable. Tap to retry" : "Tap to play video")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity).frame(height: 160)
            .background(Color.surfaceLow).clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(loading || !available || scenePhase != .active)
        .accessibilityIdentifier("managed-video-preview")
        .fullScreenCover(isPresented: $showPlayer, onDismiss: closePlayer) {
            if let player {
                ZStack(alignment: .topTrailing) {
                    NativeManagedVideoPlayer(player: player).ignoresSafeArea()
                    if playbackFailed {
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                            Text("This video could not be played on this device.")
                                .multilineTextAlignment(.center)
                        }
                        .foregroundStyle(.white).padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(.black)
                        .accessibilityIdentifier("managed-video-playback-error")
                    }
                    VStack(alignment: .trailing) {
                        Button("Close video") { showPlayer = false }
                            .padding().background(.ultraThinMaterial).clipShape(Capsule()).padding()
                            .accessibilityIdentifier("managed-video-close")
                        #if DEBUG
                        // Native simulator evidence reads the actual AVPlayer clock,
                        // never a synthetic timer or a mocked playback state.
                        if ProcessInfo.processInfo.arguments.contains("-uitest-video-clock") {
                            TimelineView(.periodic(from: .now, by: 1)) { _ in
                                let time = player.currentTime().seconds
                                Text("Playback \(time.isFinite ? Int(max(0, min(time, 86400))) : 0) seconds")
                                    .font(.caption).padding(8).background(.ultraThinMaterial)
                                    .accessibilityIdentifier("managed-video-playback-time")
                            }
                        }
                        #endif
                    }
                }
                .onAppear { player.play() }
            }
        }
        .onChange(of: scope) { _, _ in dispose() }
        .onChange(of: path) { _, _ in dispose() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { dispose() } }
        // A fullscreen cover can make its presenting row disappear. The
        // cover owns playback until dismissal; don't cancel its own launch.
        .onDisappear { if !showPlayer { dispose() } }
    }

    private func open() {
        guard available, scenePhase == .active else { return }
        // Reuse only this presentation owner's authenticated lease. A scope
        // change, background or navigation releases it; no cross-login hit.
        if let file {
            preparePlayer(file: file)
            showPlayer = true
            return
        }
        dispose()
        let owner = generation
        loading = true; failed = false
        work = Task { @MainActor in
            do {
                let downloaded = try await load(path)
                try Task.checkCancellation()
                guard generation == owner, scenePhase == .active else { return }
                // The local-file guard is deliberately redundant at the UI boundary.
                guard downloaded.url.isFileURL else { throw URLError(.badURL) }
                file = downloaded
                preparePlayer(file: downloaded)
                loading = false; showPlayer = true
            } catch {
                guard generation == owner, !Task.isCancelled else { return }
                loading = false; failed = true
            }
        }
    }

    private func dispose() {
        generation = UUID()
        work?.cancel(); work = nil
        closePlayer()
        file = nil
    }

    private func closePlayer() {
        itemObservation?.invalidate(); itemObservation = nil
        player?.pause(); player?.replaceCurrentItem(with: nil); player = nil
        loading = false; showPlayer = false
    }

    private func preparePlayer(file: ManagedVideoFile) {
        playbackFailed = false
        let item = AVPlayerItem(url: file.url)
        player = AVPlayer(playerItem: item)
        let owner = generation
        itemObservation = item.observe(\.status, options: [.initial, .new]) { item, _ in
            guard item.status == .failed else { return }
            DispatchQueue.main.async {
                guard generation == owner, player?.currentItem === item else { return }
                player?.pause()
                playbackFailed = true
            }
        }
    }
}

private struct NativeManagedVideoPlayer: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = false
        controller.showsPlaybackControls = true
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        // Preserve the existing native binding across unrelated SwiftUI updates.
        if controller.player !== player {
            controller.player = player
        }
    }

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: ()) {
        controller.player?.pause(); controller.player = nil
    }
}
