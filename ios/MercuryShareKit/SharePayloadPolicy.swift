import Foundation

public enum SharedAttachmentKind: String, Codable, Equatable, Sendable {
    case image
    case file
}

public struct SharedAttachmentCandidate: Equatable, Sendable {
    public let id: String
    public let stagedRelativePath: String
    public let displayName: String
    public let mimeType: String?
    public let sizeBytes: Int64
    public let isReadableFile: Bool

    public init(
        id: String,
        stagedRelativePath: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Int64,
        isReadableFile: Bool
    ) {
        self.id = id
        self.stagedRelativePath = stagedRelativePath
        self.displayName = displayName
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.isReadableFile = isReadableFile
    }
}

public struct SharedAttachment: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let stagedRelativePath: String
    public let displayName: String
    public let mimeType: String?
    public let sizeBytes: Int64
    public let kind: SharedAttachmentKind

    public init(
        id: String,
        stagedRelativePath: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Int64,
        kind: SharedAttachmentKind
    ) {
        self.id = id
        self.stagedRelativePath = stagedRelativePath
        self.displayName = displayName
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.kind = kind
    }
}

/// Bounded content staged by the share extension. It has deliberately no
/// destination, send flag, or remote-session identifier: the host may only
/// place it into its composer draft after an explicit inbox consume.
public struct SharePayload: Codable, Equatable, Sendable {
    public let requestID: String
    public let text: String
    public let attachments: [SharedAttachment]
    public let rejections: [String]

    public init(requestID: String, text: String, attachments: [SharedAttachment], rejections: [String]) {
        self.requestID = requestID
        self.text = text
        self.attachments = attachments
        self.rejections = rejections
    }

    public var isEmpty: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && attachments.isEmpty
    }
}

public struct SharePayloadBuildResult: Equatable, Sendable {
    public let payload: SharePayload
    public let rejections: [String]

    public init(payload: SharePayload, rejections: [String]) {
        self.payload = payload
        self.rejections = rejections
    }
}

public enum SharePayloadPolicy {
    public static let maxTextCharacters = 32_768
    public static let maxMIMETypeCharacters = 256
    public static let maxForwardedItems = 20
    public static let maxAttachments = 5
    public static let maxImageBytes: Int64 = 24 * 1024 * 1024
    public static let maxFileBytes: Int64 = 10 * 1024 * 1024
    public static let maxAggregateBytes: Int64 = 30 * 1024 * 1024
    public static let maxDisplayNameCharacters = 120

    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "bmp"]
    private static let invalidNameScalars: CharacterSet = {
        var set = CharacterSet.controlCharacters
        set.insert(charactersIn: "<>:\"/\\|?*")
        return set
    }()

    public static func build(
        text: String?,
        candidates: [SharedAttachmentCandidate],
        requestID: String = UUID().uuidString
    ) -> SharePayloadBuildResult {
        var accepted: [SharedAttachment] = []
        var rejections: [String] = []

        for candidate in candidates.prefix(maxForwardedItems) {
            guard candidate.isReadableFile, isSafeRelativePath(candidate.stagedRelativePath) else {
                rejections.append("One shared item was not a readable document")
                continue
            }
            let displayName = sanitizeDisplayName(candidate.displayName)
            let mime = candidate.mimeType.map { String($0.prefix(maxMIMETypeCharacters)) }
                .flatMap { isBlank($0) ? nil : $0 }
            let kind = kindOf(mimeType: mime, displayName: displayName)
            let attachment = SharedAttachment(
                id: candidate.id,
                stagedRelativePath: candidate.stagedRelativePath,
                displayName: displayName,
                mimeType: mime,
                sizeBytes: candidate.sizeBytes,
                kind: kind
            )
            if let reason = rejection(existing: accepted, candidate: attachment) {
                rejections.append(reason)
            } else {
                accepted.append(attachment)
            }
        }

        if candidates.count > maxForwardedItems {
            rejections.append("Maximum of \(maxForwardedItems) shared items can be inspected")
        }
        let boundedText = String((text ?? "").prefix(maxTextCharacters))
        if isBlank(boundedText), accepted.isEmpty {
            rejections.append("Nothing readable was shared")
        }
        let payload = SharePayload(
            requestID: requestID,
            text: boundedText,
            attachments: accepted,
            rejections: rejections
        )
        return SharePayloadBuildResult(payload: payload, rejections: rejections)
    }

    public static func sanitizeDisplayName(_ raw: String) -> String {
        let components = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).map(String.init)
        let basename = components.last(where: { !isBlank($0) }) ?? ""
        let filteredScalars = basename.unicodeScalars.filter { !invalidNameScalars.contains($0) }
        let cleaned = String(filteredScalars.map(Character.init))
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .drop(while: { $0 == "." })
        let bounded = String(cleaned.prefix(maxDisplayNameCharacters))
        return isBlank(bounded) ? "attachment" : bounded
    }

    public static func kindOf(mimeType: String?, displayName: String) -> SharedAttachmentKind {
        if mimeType?.lowercased().hasPrefix("image/") == true { return .image }
        guard let dot = displayName.lastIndex(of: ".") else { return .file }
        let ext = displayName[displayName.index(after: dot)...].lowercased()
        return imageExtensions.contains(ext) ? .image : .file
    }

    public static func isSafeRelativePath(_ value: String) -> Bool {
        guard !value.isEmpty, !value.hasPrefix("/"), !value.hasPrefix("\\") else { return false }
        let normalized = value.replacingOccurrences(of: "\\", with: "/")
        return !normalized.split(separator: "/", omittingEmptySubsequences: false).contains("..")
    }

    private static func rejection(existing: [SharedAttachment], candidate: SharedAttachment) -> String? {
        if candidate.sizeBytes < 0 {
            return "\(candidate.displayName) did not report a valid size"
        }
        if existing.contains(where: { $0.id == candidate.id }) {
            return "\(candidate.displayName) is already attached"
        }
        if existing.count >= maxAttachments {
            return "Maximum of \(maxAttachments) attachments"
        }
        let cap = candidate.kind == .image ? maxImageBytes : maxFileBytes
        if candidate.sizeBytes > cap {
            return "\(candidate.displayName) exceeds the \(cap / (1024 * 1024)) MB limit for \(candidate.kind.rawValue)s"
        }
        let aggregate = existing.reduce(Int64(0)) { $0 + max(0, $1.sizeBytes) } + max(0, candidate.sizeBytes)
        if aggregate > maxAggregateBytes {
            return "Total attachment size exceeds the limit"
        }
        return nil
    }

    private static func isBlank(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
