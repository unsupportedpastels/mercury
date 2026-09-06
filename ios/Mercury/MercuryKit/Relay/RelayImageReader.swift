import Foundation
import CoreFoundation

struct RelayImageUnsupportedError: LocalizedError {
    var errorDescription: String? { "This Relay host does not support image reads" }
}

/// Serializes complete reads across native views. Actor reentrancy alone does not serialize awaits.
actor RelayImageReader {
    static let shared = RelayImageReader()
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private static let maxBytes = 2 * 1024 * 1024
    private static let mimeTypes: Set<String> = ["image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"]

    private func acquire() async {
        if busy { await withCheckedContinuation { waiters.append($0) } }
        else { busy = true }
    }

    private func release() {
        if waiters.isEmpty { busy = false }
        else { waiters.removeFirst().resume() }
    }

    func read(profile: String, path: String,
              request: @MainActor (String, [String: Any]) async throws -> [String: Any]) async throws -> Data {
        await acquire()
        defer { release() }
        try Task.checkCancellation()
        guard path.hasPrefix("/"), (2...4096).contains(path.utf8.count),
              !path.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              !path.split(separator: "/").contains(".."), !path.contains("://") else {
            throw URLError(.badURL)
        }
        let status: [String: Any]
        do { status = try await request("relay.status", [:]) }
        catch is ChatMethodNotFoundError { throw RelayImageUnsupportedError() }
        guard let capabilities = status["capabilities"] as? [String: Any],
              let capability = capabilities["image_read"] as? [String: Any],
              capability["method"] as? String == "relay.image.read",
              Self.exactInteger(capability["max_bytes"]) == Self.maxBytes,
              let types = capability["mime_types"] as? [String],
              !Self.mimeTypes.isDisjoint(with: types) else { throw RelayImageUnsupportedError() }
        try Task.checkCancellation()
        let result: [String: Any]
        do { result = try await request("relay.image.read", ["profile": profile, "path": path]) }
        catch is ChatMethodNotFoundError { throw RelayImageUnsupportedError() }
        try Task.checkCancellation()
        guard let mime = result["mime_type"] as? String, types.contains(mime) else {
            throw URLError(.cannotDecodeContentData)
        }
        return try Self.decode(result)
    }

    private static func exactInteger(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              !["f", "d"].contains(String(cString: number.objCType)) else { return nil }
        let value = number.int64Value
        guard value > 0, value <= Int64(maxBytes) else { return nil }
        return Int(value)
    }

    static func decode(_ result: [String: Any]) throws -> Data {
        guard let mime = result["mime_type"] as? String, mimeTypes.contains(mime),
              let size = exactInteger(result["size"]),
              let encoded = result["base64"] as? String,
              encoded.utf8.count == ((size + 2) / 3) * 4,
              let bytes = Data(base64Encoded: encoded), bytes.count == size,
              bytes.base64EncodedString() == encoded else { throw URLError(.cannotDecodeContentData) }
        let detected: String?
        if bytes.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) { detected = "image/png" }
        else if bytes.starts(with: [255, 216, 255]) { detected = "image/jpeg" }
        else if bytes.starts(with: Array("GIF87a".utf8)) || bytes.starts(with: Array("GIF89a".utf8)) { detected = "image/gif" }
        else if bytes.starts(with: [66, 77]) { detected = "image/bmp" }
        else if bytes.count >= 12, bytes.starts(with: Array("RIFF".utf8)),
                bytes[8..<12] == Data("WEBP".utf8) { detected = "image/webp" }
        else { detected = nil }
        guard detected == mime else { throw URLError(.cannotDecodeContentData) }
        return bytes
    }
}
