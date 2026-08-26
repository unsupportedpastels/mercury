import SwiftUI
import UIKit

/// Renders one server-managed image artifact (M6.4 UI half).
///
/// Mirrors Android's `RemoteMediaImage` UX: loading spinner, aspect-fit
/// rounded image capped at ~280pt tall, tap to open a full-screen pinch-zoom
/// viewer, and a compact broken-image placeholder on failure. Decoded images
/// are cached in a tiny NSCache keyed by path.
///
/// Transport-free by design: the authenticated fetch is injected via `load`
/// (the orchestrator supplies it from the session's HTTP client); this view
/// only caches, decodes, lays out, and presents. `path` is the
/// server-canonical managed path (starts with `/`), e.g. the `source` of an
/// `Artifact` with origin `.managedPath` from `MediaDirectiveExtractor`.
struct RemoteManagedImage: View {
    let path: String
    var load: (String) async throws -> Data

    private enum LoadState {
        case loading
        case loaded(UIImage)
        case failed
    }

    @State private var state: LoadState = .loading
    @State private var showViewer = false

    private static let maxImageBytes = 10 * 1024 * 1024
    private static let maxRenderDimension: CGFloat = 2048
    private static let maxSourceDimension: CGFloat = 16_384
    private static let inlineMaxHeight: CGFloat = 280

    var body: some View {
        Group {
            switch state {
            case .loading:
                loadingPlaceholder
            case .loaded(let image):
                loadedImage(image)
            case .failed:
                failedPlaceholder
            }
        }
        .task(id: path) {
            await fetch()
        }
    }

    // MARK: - States

    private var loadingPlaceholder: some View {
        VStack(spacing: 8) {
            ProgressView()
            Text("Loading image…")
                .font(.footnote)
                .foregroundStyle(Color.secondary)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 180)
        .background(Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func loadedImage(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: .infinity)
            .frame(maxHeight: Self.inlineMaxHeight)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
            .onTapGesture { showViewer = true }
            .accessibilityLabel("Generated image; tap to enlarge")
            .fullScreenCover(isPresented: $showViewer) {
                ZoomableRemoteImageViewer(image: image, fileName: fileName)
            }
    }

    private var failedPlaceholder: some View {
        HStack(spacing: 8) {
            Image(systemName: "photo.badge.exclamationmark")
                .foregroundStyle(Color.secondary)
            Text(fileName)
                .font(.footnote)
                .foregroundStyle(Color.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Image unavailable: \(fileName)")
    }

    private var fileName: String {
        path.split(separator: "/").last.map(String.init) ?? path
    }

    // MARK: - Loading

    private func fetch() async {
        if let cached = ManagedImageCache.image(for: path) {
            state = .loaded(cached)
            return
        }
        state = .loading
        // Managed paths are server-canonical and start with '/'.
        guard path.hasPrefix("/"), path.count > 1 else {
            state = .failed
            return
        }
        do {
            let data = try await load(path)
            guard data.count <= Self.maxImageBytes,
                  let decoded = Self.decode(data)
            else {
                state = .failed
                return
            }
            ManagedImageCache.store(decoded, for: path)
            state = .loaded(decoded)
        } catch {
            state = .failed
        }
    }

    /// Decode with the same dimension guards as Android's `decodeRemoteImage`:
    /// reject absurd source dimensions, downsample anything beyond the render
    /// dimension via ImageIO so a hostile image cannot exhaust memory.
    private static func decode(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil)
                as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? CGFloat,
              let height = properties[kCGImagePropertyPixelHeight] as? CGFloat
        else {
            return nil
        }
        guard width > 0, height > 0,
              width <= maxSourceDimension, height <= maxSourceDimension
        else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxRenderDimension,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}

// MARK: - Cache

/// Tiny decoded-image cache keyed by managed path (~4 entries), mirroring
/// Android's `RemoteImageRuntime` LRU. NSCache is thread-safe and evicts
/// under memory pressure on its own.
private enum ManagedImageCache {
    private static let cache: NSCache<NSString, UIImage> = {
        let store = NSCache<NSString, UIImage>()
        store.countLimit = 4
        return store
    }()

    static func image(for path: String) -> UIImage? {
        cache.object(forKey: path as NSString)
    }

    static func store(_ image: UIImage, for path: String) {
        cache.setObject(image, forKey: path as NSString)
    }
}

// MARK: - Full-screen zoom viewer

/// Full-screen viewer mirroring Android's enlarged-image dialog: black
/// background, pinch-zoom 1x–4x, drag pan while zoomed, Close button.
private struct ZoomableRemoteImageViewer: View {
    let image: UIImage
    let fileName: String

    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .padding(16)
                .scaleEffect(scale)
                .offset(offset)
                .clipped()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .gesture(magnification.simultaneously(with: drag))
                .accessibilityLabel("\(fileName), zoom \(Int((scale * 100).rounded()))%")

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white)
                    .padding(20)
            }
            .accessibilityLabel("Close enlarged image")
        }
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(max(lastScale * value, 1), 4)
                if scale <= 1.01 {
                    offset = .zero
                    lastOffset = .zero
                }
            }
            .onEnded { _ in
                lastScale = scale
            }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1.01 else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }
}

#if DEBUG
#Preview {
    RemoteManagedImage(path: "/media/generated/example.png") { _ in
        Data()
    }
    .padding()
    .amoledScreen()
}
#endif
