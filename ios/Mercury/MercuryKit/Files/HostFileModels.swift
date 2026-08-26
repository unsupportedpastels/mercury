import Foundation

let maxHostFileEntries = 500
let maxHostFilePathLength = 1_024
let maxHostFileNameLength = 255
let maxHostFileBytes = 10 * 1_024 * 1_024

struct HostFileEntry: Equatable, Sendable {
    let name: String
    let path: String
    let isDirectory: Bool
    let size: Int64?
    let mimeType: String?
    let modifiedEpochSeconds: Double?

    init(
        name: String,
        path: String,
        isDirectory: Bool,
        size: Int64? = nil,
        mimeType: String? = nil,
        modifiedEpochSeconds: Double? = nil
    ) {
        self.name = name
        self.path = path
        self.isDirectory = isDirectory
        self.size = size
        self.mimeType = mimeType
        self.modifiedEpochSeconds = modifiedEpochSeconds
    }

    var reference: String {
        get throws { try formatHostFileReference(self) }
    }
}

struct HostFileListing: Equatable, Sendable {
    let path: String
    let entries: [HostFileEntry]
    let parentPath: String?
    let root: String?
    let lockedRoot: String?
    let canChangePath: Bool

    init(
        path: String,
        entries: [HostFileEntry],
        parentPath: String? = nil,
        root: String? = nil,
        lockedRoot: String? = nil,
        canChangePath: Bool = true
    ) {
        self.path = path
        self.entries = entries
        self.parentPath = parentPath
        self.root = root
        self.lockedRoot = lockedRoot
        self.canChangePath = canChangePath
    }
}

struct HostFileContent: Equatable, Sendable {
    let name: String
    let path: String
    let mimeType: String
    let bytes: Data

    var size: Int { bytes.count }
}

enum HostFileModelError: Error, Equatable {
    case invalidCanonicalPath
    case unsafeReferenceQuoting
}

/// Validates a canonical path supplied by Hermes Serve. Callers must pass
/// server-returned paths back unchanged rather than joining child paths locally.
func validCanonicalHostFilePath(_ path: String?) -> String? {
    guard let path else { return nil }
    let value = path.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !value.isEmpty, value.count <= maxHostFilePathLength else { return nil }
    guard !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { return nil }

    let isUnixAbsolute = value.first == "/"
    let characters = Array(value)
    let isWindowsAbsolute = characters.count >= 3
        && characters[0].isASCII
        && characters[0].isLetter
        && characters[1] == ":"
        && (characters[2] == "/" || characters[2] == "\\")
    guard isUnixAbsolute || isWindowsAbsolute else { return nil }

    let components = value.split(
        omittingEmptySubsequences: false,
        whereSeparator: { $0 == "/" || $0 == "\\" }
    )
    guard !components.contains(where: { $0 == "." || $0 == ".." }) else { return nil }
    return value
}

func validHostFileName(_ name: String?) -> String? {
    guard let name, !name.isEmpty, name.count <= maxHostFileNameLength else { return nil }
    guard name != ".", name != ".." else { return nil }
    guard !name.contains("/"), !name.contains("\\") else { return nil }
    guard !name.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { return nil }
    return name
}

func validHostFileMIMEType(_ value: String?) -> String? {
    guard let value else { return nil }
    let mime = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !mime.isEmpty, mime.count <= 128 else { return nil }
    let pieces = mime.split(separator: "/", omittingEmptySubsequences: false)
    guard pieces.count == 2, pieces.allSatisfy({ !$0.isEmpty }) else { return nil }
    let punctuation = CharacterSet(charactersIn: "!#$&^_.+-")
    guard mime.unicodeScalars.allSatisfy({ scalar in
        scalar == "/"
            || (scalar.value >= 97 && scalar.value <= 122)
            || (scalar.value >= 48 && scalar.value <= 57)
            || punctuation.contains(scalar)
    }) else { return nil }
    return mime
}

/// Formats the official Desktop-compatible `@file:` / `@folder:` reference.
/// Quote selection is deliberate: backticks, then double quotes, then single
/// quotes. Paths containing every delimiter are rejected rather than escaped.
func formatHostFileReference(_ entry: HostFileEntry) throws -> String {
    guard let path = validCanonicalHostFilePath(entry.path) else {
        throw HostFileModelError.invalidCanonicalPath
    }
    let kind = entry.isDirectory ? "folder" : "file"
    let barePunctuation: Set<Character> = ["/", "\\", ".", "-", "_", ":", "+", "=", "@"]
    let safeBare = path.allSatisfy { character in
        character.isLetter || character.isNumber
            || barePunctuation.contains(character)
    }

    let value: String
    if safeBare {
        value = path
    } else if !path.contains("`") {
        value = "`\(path)`"
    } else if !path.contains("\"") {
        value = "\"\(path)\""
    } else if !path.contains("'") {
        value = "'\(path)'"
    } else {
        throw HostFileModelError.unsafeReferenceQuoting
    }
    return "@\(kind):\(value)"
}
