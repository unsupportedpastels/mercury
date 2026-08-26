import Foundation

// MARK: - Models (ported from ArtifactModels.kt)

/// The safe presentation category of a transcript-delivered artifact.
enum ArtifactType: Sendable, Equatable {
    case image
    case audio
    case file
}

/// Whether an artifact is a managed host path or an approved remote URL.
enum ArtifactOrigin: Sendable, Equatable {
    case managedPath
    case remoteURL
}

/// Bounded metadata for one transcript-delivered artifact.
///
/// `source` is deliberately a path or URL only; it is never HTML, a data URI,
/// or a client-local file URI. `stableIdentity` is suitable for a per-session
/// browser key and is independent of the message in which the artifact first
/// appeared.
struct Artifact: Sendable, Equatable {
    let stableIdentity: String
    let type: ArtifactType
    let origin: ArtifactOrigin
    let source: String
    let displayName: String

    /// Short alias for callers that use identity terminology.
    var identity: String { stableIdentity }

    /// Short alias for callers that use location terminology.
    var location: String { source }
}

/// Input and output bounds for the pure transcript extractor.
struct ArtifactExtractionLimits: Sendable, Equatable {
    let maxTranscriptChars: Int
    let maxItems: Int
    let maxDisplayNameChars: Int
    let maxSourceChars: Int
    let maxLocationChars: Int

    init(
        maxTranscriptChars: Int = 64 * 1024,
        maxItems: Int = 64,
        maxDisplayNameChars: Int = 128,
        maxSourceChars: Int = 4 * 1024,
        maxLocationChars: Int = 4 * 1024
    ) {
        precondition(maxTranscriptChars > 0, "maxTranscriptChars must be positive")
        precondition(maxItems > 0, "maxItems must be positive")
        precondition(maxDisplayNameChars > 0, "maxDisplayNameChars must be positive")
        precondition(maxSourceChars > 0, "maxSourceChars must be positive")
        precondition(maxLocationChars > 0, "maxLocationChars must be positive")
        self.maxTranscriptChars = maxTranscriptChars
        self.maxItems = maxItems
        self.maxDisplayNameChars = maxDisplayNameChars
        self.maxSourceChars = maxSourceChars
        self.maxLocationChars = maxLocationChars
    }
}

// MARK: - Message input

/// Minimal transcript-message input for the pure extractor.
///
/// Mirrors the Android gateway `ChatMessage` fields that matter here. Only
/// `text` is ever read; `reasoningText` is carried for call-site fidelity and
/// intentionally never extracted from.
struct MediaExtractionMessage: Sendable, Equatable {
    let text: String
    let reasoningText: String?

    init(text: String, reasoningText: String? = nil) {
        self.text = text
        self.reasoningText = reasoningText
    }
}

// MARK: - Extractor (ported from ArtifactExtractor.kt)

/// Extracts only explicit, bounded artifact references from transcript text.
///
/// This layer does not fetch, preview, or interpret arbitrary prose. A source
/// must be a standalone MEDIA directive, a standalone HTTPS URL, or an
/// explicit Markdown link/image link.
///
/// Ported faithfully from the Android `ArtifactExtractor.kt`. Where Kotlin
/// uses `java.net.URI`, this port replicates the observable behavior with a
/// strict structural parse instead of Foundation's more permissive
/// `URLComponents`, so hostile inputs are rejected on exactly the same shapes.
enum MediaDirectiveExtractor {
    private static let mediaPrefix = "MEDIA:"
    private static let managedIDPrefix = "managed:"
    private static let remoteIDPrefix = "remote:"
    private static let quotes: Set<Character> = ["`", "\"", "'"]

    /// Kotlin: (!?)\[([^]\r\n]{1,512})\]\(\s*(<[^>\r\n]{1,4096}>|[^()\s\r\n]+)\s*\)
    /// (`]` is escaped inside the class; semantics are identical.)
    private static let markdownLinkPattern = try! NSRegularExpression(
        pattern: "(!?)\\[([^\\]\\r\\n]{1,512})\\]\\(\\s*(<[^>\\r\\n]{1,4096}>|[^()\\s\\r\\n]+)\\s*\\)"
    )

    /// Kotlin: (?i)^(image|audio|file|video)\s*:\s*
    private static let typePrefixPattern = try! NSRegularExpression(
        pattern: "^(image|audio|file|video)\\s*:\\s*",
        options: [.caseInsensitive]
    )

    private static let imageExtensions: Set<String> = [
        "bmp", "gif", "heic", "jpeg", "jpg", "png", "tif", "tiff", "webp",
    ]
    private static let audioExtensions: Set<String> = [
        "aac", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav",
    ]

    // MARK: Entry points

    /// Extract from the text field of every transcript message, in message order.
    static func extract(
        messages: [MediaExtractionMessage],
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        var artifacts: [Artifact] = []
        artifacts.reserveCapacity(min(limits.maxItems, 16))
        var identities: Set<String> = []
        var consumed = 0

        for message in messages {
            if artifacts.count >= limits.maxItems || consumed >= limits.maxTranscriptChars { break }
            let remaining = limits.maxTranscriptChars - consumed
            let text = prefix(message.text, remaining)
            consumed += text.utf16.count
            extractFromText(text, limits: limits, artifacts: &artifacts, identities: &identities)
        }
        return artifacts
    }

    static func extract(
        message: MediaExtractionMessage,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        extract(messages: [message], limits: limits)
    }

    static func extract(
        _ text: String,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        extract(messages: [MediaExtractionMessage(text: text)], limits: limits)
    }

    // MARK: Per-text extraction

    private struct Candidate {
        let offset: Int
        let source: String
        let labelHint: String?
        let forcedType: ArtifactType?
    }

    private static func extractFromText(
        _ text: String,
        limits: ArtifactExtractionLimits,
        artifacts: inout [Artifact],
        identities: inout Set<String>
    ) {
        var candidates: [Candidate] = []
        // Line-oriented extraction mirrors the first-party TUI's standalone
        // MEDIA grammar while intentionally excluding its inline/prose form.
        var lineOffset = 0
        for rawLine in text.components(separatedBy: "\n") {
            let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
            if let directive = standaloneMediaSource(line) {
                candidates.append(Candidate(offset: lineOffset, source: directive, labelHint: nil, forcedType: nil))
            } else if let source = standaloneHttpsSource(line) {
                candidates.append(Candidate(offset: lineOffset, source: source, labelHint: nil, forcedType: nil))
            }
            lineOffset += rawLine.utf16.count + 1
        }

        let nsText = text as NSString
        let fullRange = NSRange(location: 0, length: nsText.length)
        for match in markdownLinkPattern.matches(in: text, range: fullRange) {
            let isImageLink = match.range(at: 1).location != NSNotFound
                && nsText.substring(with: match.range(at: 1)) == "!"
            let label = match.range(at: 2).location != NSNotFound
                ? nsText.substring(with: match.range(at: 2)) : ""
            guard match.range(at: 3).location != NSNotFound else { continue }
            let token = nsText.substring(with: match.range(at: 3))
            let destination =
                (token.hasPrefix("<") && token.hasSuffix(">") && token.count >= 2)
                ? String(token.dropFirst().dropLast())
                : token
            candidates.append(
                Candidate(
                    offset: match.range.location,
                    source: destination,
                    labelHint: label,
                    forcedType: isImageLink ? .image : nil
                )
            )
        }

        // Kotlin sortBy is stable; enforce stability explicitly because
        // Swift's sort makes no guarantee for equal offsets.
        candidates = candidates.enumerated().sorted { lhs, rhs in
            if lhs.element.offset != rhs.element.offset {
                return lhs.element.offset < rhs.element.offset
            }
            return lhs.offset < rhs.offset
        }
        .map { $0.element }

        for candidate in candidates {
            if artifacts.count >= limits.maxItems { break }
            addCandidate(
                source: candidate.source,
                labelHint: candidate.labelHint,
                forcedType: candidate.forcedType,
                limits: limits,
                artifacts: &artifacts,
                identities: &identities
            )
        }
    }

    private static func addCandidate(
        source: String,
        labelHint: String?,
        forcedType: ArtifactType?,
        limits: ArtifactExtractionLimits,
        artifacts: inout [Artifact],
        identities: inout Set<String>
    ) {
        let sourceLength = source.utf16.count
        if sourceLength > limits.maxSourceChars || sourceLength > limits.maxLocationChars { return }
        if containsHostileControl(source) { return }

        guard let resolved = resolveSource(source) else { return }
        if resolved.location.utf16.count > limits.maxLocationChars { return }
        if !identities.insert(resolved.identity).inserted { return }

        let type = forcedType ?? inferType(resolved.location, labelHint: labelHint)
        let name = displayName(source: resolved.location, labelHint: labelHint, maxLength: limits.maxDisplayNameChars)
        artifacts.append(
            Artifact(
                stableIdentity: resolved.identity,
                type: type,
                origin: resolved.origin,
                source: resolved.location,
                displayName: name
            )
        )
    }

    private struct ResolvedSource {
        let origin: ArtifactOrigin
        let location: String
        let identity: String
    }

    private static func resolveSource(_ source: String) -> ResolvedSource? {
        if isManagedPath(source) {
            guard let canonical = canonicalManagedPath(source) else { return nil }
            return ResolvedSource(
                origin: .managedPath,
                location: canonical,
                identity: managedIDPrefix + canonical
            )
        }
        guard let uri = safeHttpsUri(source), let canonical = canonicalRemoteURL(uri) else { return nil }
        return ResolvedSource(origin: .remoteURL, location: canonical, identity: remoteIDPrefix + canonical)
    }

    // MARK: Standalone line grammars

    private static func standaloneMediaSource(_ line: String) -> String? {
        var body = trim(line, characters: " \t")
        if body.isEmpty { return nil }

        // The desktop/TUI grammar accepts an optional quote around the whole
        // tag as well as an optional quote/backtick around its value.
        if let wrapper = body.first, quotes.contains(wrapper) {
            guard body.count >= 2, body.last == wrapper else { return nil }
            body = trim(String(body.dropFirst().dropLast()), characters: " \t")
        }
        guard body.hasPrefix(mediaPrefix) else { return nil }

        var payload = trim(String(body.dropFirst(mediaPrefix.count)), characters: " \t")
        if payload.isEmpty { return nil }
        if let quote = payload.first, quotes.contains(quote) {
            guard payload.count >= 2, payload.last == quote else { return nil }
            payload = String(payload.dropFirst().dropLast())
            if payload.isEmpty { return nil }
        } else {
            // Unquoted first-party values are one non-whitespace token. A
            // stray quote/backtick is malformed rather than a path.
            if payload.contains(where: { isKotlinWhitespace($0) || quotes.contains($0) }) { return nil }
        }
        return isNotBlank(payload) ? payload : nil
    }

    private static func standaloneHttpsSource(_ line: String) -> String? {
        let trimmed = trim(line, characters: " \t")
        if trimmed.isEmpty { return nil }
        let source =
            (trimmed.first == "<" && trimmed.last == ">" && trimmed.count >= 2)
            ? String(trimmed.dropFirst().dropLast())
            : trimmed
        if source.contains(where: \.isWhitespace) { return nil }
        return hasCaseInsensitivePrefix(source, "https://") ? source : nil
    }

    // MARK: Managed paths

    private static func isManagedPath(_ value: String) -> Bool {
        value.hasPrefix("/") && !value.hasPrefix("//") && !value.contains("\\")
    }

    private static func canonicalManagedPath(_ value: String) -> String? {
        let segments = value.components(separatedBy: "/")
        if segments.count <= 1 || segments.last?.isEmpty == true { return nil }
        var normalized: [String] = []
        normalized.reserveCapacity(segments.count)
        for segment in segments.dropFirst() {
            switch segment {
            case "", ".":
                continue
            case "..":
                return nil
            default:
                normalized.append(segment)
            }
        }
        if normalized.isEmpty { return nil }
        return "/" + normalized.joined(separator: "/")
    }

    // MARK: HTTPS URL safety

    /// Structural stand-in for one parsed `java.net.URI`.
    private struct SafeUri {
        let host: String
        let port: Int?
        let rawPath: String
        let rawQuery: String?
    }

    private static func safeHttpsUri(_ value: String) -> SafeUri? {
        guard hasCaseInsensitivePrefix(value, "https://") else { return nil }
        if containsEncodedControl(value) { return nil }
        if containsHostileControl(value) { return nil }
        // java.net.URI's constructor throws on any character outside its RFC
        // grammar (including '[', ']', '{', '}', '|', '\\', '^', '`', '"',
        // '<', '>', and space) or on a malformed percent escape. Reject those
        // up front so the structural parse below observes the same shapes.
        guard containsOnlyLegalURICharacters(value) else { return nil }

        var rest = String(value.dropFirst("https://".count))
        // Any raw '#' begins a fragment; Kotlin rejects rawFragment != null.
        if rest.contains("#") { return nil }
        var query: String?
        if let qIndex = rest.firstIndex(of: "?") {
            query = String(rest[rest.index(after: qIndex)...])
            rest = String(rest[..<qIndex])
        }

        let authority: String
        let path: String
        if let slashIndex = rest.firstIndex(of: "/") {
            authority = String(rest[..<slashIndex])
            path = String(rest[slashIndex...])
        } else {
            authority = rest
            path = ""
        }

        // Userinfo present (rawUserInfo != null) is rejected outright.
        guard !authority.contains("@") else { return nil }

        var host = authority
        var port: Int?
        if let colonIndex = authority.firstIndex(of: ":") {
            host = String(authority[..<colonIndex])
            let portText = String(authority[authority.index(after: colonIndex)...])
            if !portText.isEmpty {
                // Java parses the port as an Int32; anything else makes the
                // constructor throw (or fall back to a registry-based
                // authority whose null host is rejected below).
                guard let parsed = Int(portText), parsed <= Int32.max else { return nil }
                port = parsed
            }
        }

        // uri.host ?: return null — hosts Java cannot parse server-based
        // (illegal hostname characters such as '_' or '%') have a null host
        // and were already rejected above by the legal-character scan.
        if isKotlinBlank(host) { return nil }
        if let p = port, p != 443 { return nil }

        let normalizedHost = host.lowercased()
        if normalizedHost == "localhost"
            || normalizedHost.hasSuffix(".localhost")
            || normalizedHost.hasSuffix(".local")
            || normalizedHost.contains(":")
            || normalizedHost.allSatisfy({ kotlinIsDigit($0) || $0 == "." })
        { return nil }
        if normalizedHost.contains(where: { !(kotlinIsLetterOrDigit($0) || $0 == "." || $0 == "-") }) {
            return nil
        }
        return SafeUri(host: normalizedHost, port: port, rawPath: path, rawQuery: query)
    }

    private static func canonicalRemoteURL(_ uri: SafeUri) -> String? {
        let host = uri.host.lowercased()
        let port = (uri.port == nil || uri.port == 443) ? "" : ":\(uri.port!)"
        let path = uri.rawPath.isEmpty ? "/" : uri.rawPath
        if path.components(separatedBy: "/").contains("..") { return nil }
        let query = uri.rawQuery.map { "?\($0)" } ?? ""
        return "https://\(host)\(port)\(path)\(query)"
    }

    // MARK: Type inference and display names

    private static func inferType(_ source: String, labelHint: String?) -> ArtifactType {
        let name = blankOr(pathName(source), fallback: labelHint ?? "")
        // Kotlin substringAfterLast('.', ""): missing delimiter yields "".
        let extensionName = (name.contains(".") ? afterLast(name, ".") : "").lowercased()
        if imageExtensions.contains(extensionName) { return .image }
        if audioExtensions.contains(extensionName) { return .audio }
        switch typePrefixGroup(labelHint ?? "") {
        case "image": return .image
        case "audio": return .audio
        default: return .file
        }
    }

    private static func displayName(source: String, labelHint: String?, maxLength: Int) -> String {
        let sourceName = pathName(source)
        let hint = stripTypePrefix(labelHint ?? "")
        var candidate = blankOr(sourceName, fallback: hint)
        if isKotlinBlank(candidate) { candidate = "artifact" }
        let sanitized = candidate.map { character -> Character in
            if isHostileControl(character) || character == "/" || character == "\\" { return "_" }
            return character
        }
        var result = String(sanitized)
        result = collapseRegexWhitespace(result)
        result = kotlinTrim(result)
        result = trim(result, characters: ".")
        if isKotlinBlank(result) { result = "artifact" }
        result = String(result.prefix(maxLength))
        return isKotlinBlank(result) ? "artifact" : result
    }

    /// Kotlin `pathName`: last path segment of the source, percent-decoded the
    /// way `java.net.URI.getPath` plus `URLDecoder.decode(UTF_8)` would.
    ///
    /// When `URI(source)` would throw (e.g. a managed path containing a raw
    /// space), Kotlin falls back to the raw text before '?'/'#'; this mirrors
    /// that fallback exactly.
    private static func pathName(_ source: String) -> String {
        let path = decodedURIPathIfLegal(source)
            ?? beforeFirst(beforeFirst(source, "?"), "#")
        let name = afterLast(path, "/")
        return formDecode(name) ?? name
    }

    /// Returns the URI-decoded path component when `source` parses like a
    /// legal `java.net.URI` (managed absolute path or https URL); otherwise
    /// nil, mirroring `runCatching { URI(source).path }.getOrNull()`.
    private static func decodedURIPathIfLegal(_ source: String) -> String? {
        var rawPath: String?
        if source.hasPrefix("/") {
            rawPath = source
        } else if hasCaseInsensitivePrefix(source, "https://") {
            var rest = String(source.dropFirst("https://".count))
            if let slashIndex = rest.firstIndex(of: "/") {
                rest = String(rest[slashIndex...])
            } else {
                rest = ""
            }
            if let qIndex = rest.firstIndex(of: "?") { rest = String(rest[..<qIndex]) }
            if let fIndex = rest.firstIndex(of: "#") { rest = String(rest[..<fIndex]) }
            rawPath = rest
        } else {
            // Other schemes/relative forms are outside the inputs produced by
            // resolveSource; treat them like a failed parse.
            return nil
        }
        guard let path = rawPath, containsOnlyLegalURICharacters(path) else { return nil }
        return uriDecode(path)
    }

    // MARK: Character classification helpers

    /// Kotlin `Char.isDigit()` (Unicode Nd category).
    private static func kotlinIsDigit(_ c: Character) -> Bool {
        c.unicodeScalars.allSatisfy { CharacterSet.decimalDigits.contains($0) }
    }

    /// Kotlin `Char.isLetterOrDigit()`.
    private static func kotlinIsLetterOrDigit(_ c: Character) -> Bool {
        c.isLetter || kotlinIsDigit(c)
    }

    /// Kotlin `Char.isWhitespace()` (Java semantics: NBSP/U+2007/U+202F are
    /// NOT whitespace; U+001C–U+001F are).
    private static func isKotlinWhitespace(_ c: Character) -> Bool {
        switch c {
        case "\u{1C}" ... "\u{1F}":
            return true
        case "\u{00A0}", "\u{2007}", "\u{202F}":
            return false
        default:
            return c.isWhitespace
        }
    }

    /// `java.lang.Character.isISOControl`.
    private static func isHostileControl(_ c: Character) -> Bool {
        c.unicodeScalars.contains { scalar in
            scalar.value < 0x20 || (0x7F...0x9F).contains(scalar.value)
        }
    }

    private static func containsHostileControl(_ value: String) -> Bool {
        value.contains(where: isHostileControl)
    }

    /// Kotlin `String.isBlank()`.
    private static func isKotlinBlank(_ value: String) -> Bool {
        value.isEmpty || value.allSatisfy(isKotlinWhitespace)
    }

    private static func isNotBlank(_ value: String) -> Bool {
        !isKotlinBlank(value)
    }

    /// Kotlin `String.ifBlank { fallback }`.
    private static func blankOr(_ value: String, fallback: String) -> String {
        isKotlinBlank(value) ? fallback : value
    }

    /// Regex `(?i)%0[0-9a-f]|%1[0-9a-f]`.
    private static func containsEncodedControl(_ value: String) -> Bool {
        let scalars = Array(value.lowercased().unicodeScalars)
        guard scalars.count >= 3 else { return false }
        for index in 0...(scalars.count - 3) where scalars[index] == "%" {
            let first = scalars[index + 1]
            let second = scalars[index + 2]
            if first == "0" || first == "1", isHexScalar(second) { return true }
        }
        return false
    }

    private static func isHexScalar(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar {
        case "0" ... "9", "a" ... "f", "A" ... "F": return true
        default: return false
        }
    }

    /// Characters `java.net.URI(String)` accepts anywhere in a URI: ASCII
    /// alphanumerics, the RFC 2396 marks `-_.!~*'()`, the reserved set
    /// `;/?:@&=+$,`, and `%XX` escapes. Everything else (space, `"<>{}|\^``,
    /// backtick, brackets, non-ASCII) makes the constructor throw.
    private static func containsOnlyLegalURICharacters(_ value: String) -> Bool {
        let extraLegals: Set<Character> = ["-", "_", ".", "!", "~", "*", "'", "(", ")",
                                           ";", "/", "?", ":", "@", "&", "=", "+", "$", ",", "%"]
        let scalars = Array(value.unicodeScalars)
        var index = 0
        while index < scalars.count {
            let scalar = scalars[index]
            if scalar.value < 128 {
                let character = Character(scalar)
                if kotlinIsLetterOrDigit(character) || extraLegals.contains(character) {
                    if scalar == "%" {
                        guard index + 2 < scalars.count,
                            isHexScalar(scalars[index + 1]),
                            isHexScalar(scalars[index + 2])
                        else { return false }
                        index += 3
                        continue
                    }
                    index += 1
                    continue
                }
                return false
            }
            return false
        }
        return true
    }

    private static func hasCaseInsensitivePrefix(_ value: String, _ prefix: String) -> Bool {
        guard value.utf16.count >= prefix.utf16.count else { return false }
        return value.lowercased().hasPrefix(prefix.lowercased())
    }

    private static func trim(_ value: String, characters: some Collection<Character>) -> String {
        let set = Set(characters)
        var view = Substring(value)
        while let first = view.first, set.contains(first) { view.removeFirst() }
        while let last = view.last, set.contains(last) { view.removeLast() }
        return String(view)
    }

    /// Kotlin `String.trim()` (whitespace per `Char.isWhitespace`).
    private static func kotlinTrim(_ value: String) -> String {
        var view = Substring(value)
        while let first = view.first, isKotlinWhitespace(first) { view.removeFirst() }
        while let last = view.last, isKotlinWhitespace(last) { view.removeLast() }
        return String(view)
    }

    /// Java regex `\s+` collapsed to a single space (`replace(Regex("\\s+"), " ")`).
    private static func collapseRegexWhitespace(_ value: String) -> String {
        let regexWhitespace: Set<Character> = [" ", "\t", "\n", "\u{0B}", "\u{0C}", "\r"]
        var output: [Character] = []
        output.reserveCapacity(value.count)
        var pendingSpace = false
        for character in value {
            if regexWhitespace.contains(character) {
                if !pendingSpace {
                    output.append(" ")
                    pendingSpace = true
                }
            } else {
                output.append(character)
                pendingSpace = false
            }
        }
        return String(output)
    }

    private static func beforeFirst(_ value: String, _ delimiter: Character) -> String {
        guard let index = value.firstIndex(of: delimiter) else { return value }
        return String(value[..<index])
    }

    private static func afterLast(_ value: String, _ delimiter: Character) -> String {
        guard let index = value.lastIndex(of: delimiter) else { return value }
        return String(value[value.index(after: index)...])
    }

    // MARK: Type-prefix helpers

    /// Group 1 of `(?i)^(image|audio|file|video)\s*:\s*` when it matches at
    /// the start, lowercased; otherwise nil.
    private static func typePrefixGroup(_ hint: String) -> String? {
        let nsHint = hint as NSString
        guard
            let match = typePrefixPattern.firstMatch(
                in: hint,
                range: NSRange(location: 0, length: nsHint.length)
            ),
            match.range.location == 0,
            match.range(at: 1).location != NSNotFound
        else { return nil }
        return nsText(nsHint, match.range(at: 1)).lowercased()
    }

    /// `typePrefixPattern.replace(hint, "")` — removes the anchored prefix once.
    private static func stripTypePrefix(_ hint: String) -> String {
        let nsHint = hint as NSString
        guard
            let match = typePrefixPattern.firstMatch(
                in: hint,
                range: NSRange(location: 0, length: nsHint.length)
            ),
            match.range.location == 0
        else { return hint }
        return nsText(nsHint, NSRange(location: match.range.upperBound, length: nsHint.length - match.range.upperBound))
    }

    private static func nsText(_ string: NSString, _ range: NSRange) -> String {
        range.location == NSNotFound ? "" : string.substring(with: range)
    }

    // MARK: Decoding helpers

    /// `java.net.URI.getPath` decoding: `%XX` bytes joined and decoded as
    /// UTF-8; `'+'` is left alone. Returns nil when the bytes are not valid
    /// UTF-8 (treated like a failed parse by callers).
    private static func uriDecode(_ value: String) -> String? {
        decodePercentBytes(value, plusAsSpace: false)
    }

    /// `java.net.URLDecoder.decode(value, UTF_8)`: `'+'` becomes a space and
    /// `%XX` sequences decode as UTF-8; malformed input returns nil so the
    /// caller can keep the original (mirroring runCatching/getOrDefault).
    private static func formDecode(_ value: String) -> String? {
        decodePercentBytes(value, plusAsSpace: true)
    }

    private static func decodePercentBytes(_ value: String, plusAsSpace: Bool) -> String? {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(value.utf8.count)
        let utf8 = Array(value.utf8)
        var index = 0
        while index < utf8.count {
            let byte = utf8[index]
            if plusAsSpace, byte == 0x2B { // '+'
                bytes.append(0x20)
                index += 1
            } else if byte == 0x25 { // '%'
                guard index + 2 < utf8.count,
                    let high = hexNibble(utf8[index + 1]),
                    let low = hexNibble(utf8[index + 2])
                else { return nil }
                bytes.append(high << 4 | low)
                index += 3
            } else {
                bytes.append(byte)
                index += 1
            }
        }
        return String(bytes: bytes, encoding: .utf8)
    }

    private static func hexNibble(_ byte: UInt8) -> UInt8? {
        switch byte {
        case 0x30 ... 0x39: return byte - 0x30
        case 0x41 ... 0x46: return byte - 0x41 + 10
        case 0x61 ... 0x66: return byte - 0x61 + 10
        default: return nil
        }
    }

    /// Kotlin `String.take(n)` on characters.
    private static func prefix(_ value: String, _ maxLength: Int) -> String {
        guard maxLength >= 0 else { return "" }
        return maxLength >= value.count ? value : String(value.prefix(maxLength))
    }
}

// MARK: - Top-level convenience (ported from ArtifactExtractor.kt)

func extractArtifacts(
    messages: [MediaExtractionMessage],
    limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
) -> [Artifact] {
    MediaDirectiveExtractor.extract(messages: messages, limits: limits)
}
