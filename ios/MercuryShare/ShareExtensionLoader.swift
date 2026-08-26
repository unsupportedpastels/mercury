import Foundation
import UniformTypeIdentifiers
import MercuryShareKit

struct ShareExtensionLoader {
    let store: ShareInboxStore
    let fileManager: FileManager

    init(store: ShareInboxStore, fileManager: FileManager = .default) {
        self.store = store
        self.fileManager = fileManager
    }

    func stage(providers: [NSItemProvider], requestID: String = UUID().uuidString) async -> SharePayloadBuildResult {
        let boundedProviders = Array(providers.prefix(SharePayloadPolicy.maxForwardedItems))
        let text = await firstSharedText(from: boundedProviders)
        var candidates: [SharedAttachmentCandidate] = []

        for provider in boundedProviders {
            guard let type = attachmentType(for: provider),
                  let candidate = await stageFile(from: provider, type: type, requestID: requestID)
            else { continue }
            candidates.append(candidate)
        }

        let result = SharePayloadPolicy.build(text: text, candidates: candidates, requestID: requestID)
        let acceptedIDs = Set(result.payload.attachments.map(\.id))
        for candidate in candidates where !acceptedIDs.contains(candidate.id) {
            let url = store.containerURL.appendingPathComponent(candidate.stagedRelativePath)
            try? fileManager.removeItem(at: url)
        }
        return result
    }

    private func firstSharedText(from providers: [NSItemProvider]) async -> String? {
        for provider in providers where provider.canLoadObject(ofClass: NSString.self) {
            if let text = await withCheckedContinuation({ (continuation: CheckedContinuation<String?, Never>) in
                provider.loadObject(ofClass: NSString.self) { object, _ in
                    continuation.resume(returning: object as? String)
                }
            }), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return String(text.prefix(SharePayloadPolicy.maxTextCharacters))
            }
        }
        return nil
    }

    private func attachmentType(for provider: NSItemProvider) -> UTType? {
        let types = provider.registeredTypeIdentifiers.compactMap(UTType.init)
        if let image = types.first(where: { $0.conforms(to: .image) }) { return image }
        return types.first(where: {
            !$0.conforms(to: .text) && ($0.conforms(to: .content) || $0.conforms(to: .data))
        })
    }

    private func stageFile(
        from provider: NSItemProvider,
        type: UTType,
        requestID: String
    ) async -> SharedAttachmentCandidate? {
        await withCheckedContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { source, _ in
                guard let source else {
                    continuation.resume(returning: nil)
                    return
                }
                do {
                    let values = try source.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                    let size = Int64(values.fileSize ?? -1)
                    let cap = type.conforms(to: .image)
                        ? SharePayloadPolicy.maxImageBytes
                        : SharePayloadPolicy.maxFileBytes
                    guard values.isRegularFile != false,
                          size >= 0,
                          size <= cap else {
                        continuation.resume(returning: nil)
                        return
                    }
                    let requestDirectory = self.store.stagedURL
                        .appendingPathComponent(requestID, isDirectory: true)
                    try self.fileManager.createDirectory(at: requestDirectory, withIntermediateDirectories: true)
                    let ext: String? = source.pathExtension.isEmpty
                        ? type.preferredFilenameExtension
                        : source.pathExtension
                    let suffix = ext.flatMap { $0.isEmpty ? nil : ".\($0)" } ?? ""
                    let generatedName = UUID().uuidString + suffix
                    let destination = requestDirectory.appendingPathComponent(generatedName)
                    let handle = try FileHandle(forReadingFrom: source)
                    defer { try? handle.close() }
                    let data = try handle.read(upToCount: Int(cap) + 1) ?? Data()
                    guard Int64(data.count) == size else {
                        continuation.resume(returning: nil)
                        return
                    }
                    try data.write(to: destination, options: [.atomic, .completeFileProtectionUnlessOpen])
                    let relative = destination.path.replacingOccurrences(
                        of: self.store.containerURL.path.hasSuffix("/")
                            ? self.store.containerURL.path
                            : self.store.containerURL.path + "/",
                        with: "",
                        options: [.anchored]
                    )
                    continuation.resume(returning: SharedAttachmentCandidate(
                        id: UUID().uuidString,
                        stagedRelativePath: relative,
                        displayName: provider.suggestedName ?? source.lastPathComponent,
                        mimeType: type.preferredMIMEType,
                        sizeBytes: size,
                        isReadableFile: true
                    ))
                } catch {
                    continuation.resume(returning: nil)
                }
            }
        }
    }
}
