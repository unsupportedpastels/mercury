import Foundation

/// Failures specific to the official managed-files REST contract. Authentication
/// and retryable server statuses use `HermesAuthError` so callers can share the
/// app's existing sign-in and retry classification.
enum HostFilesClientError: Error, Equatable {
    case invalidOrigin
    case invalidBearerToken
    case invalidPath
    case invalidResponse
    case httpStatus(Int)
    case responseTooLarge
    case invalidMIMEType
    case invalidContentLength
    case malformedListing
    case malformedDirectoryCreation
    case malformedContent
    case contentSizeMismatch
}

/// Authenticated, origin-scoped client for Hermes Serve's managed-files API.
///
/// This client intentionally does not use `HermesHTTPClient`: file reads and
/// downloads may reach 10 MiB, while that general-purpose client has a 64 KiB
/// response cap. Bodies are consumed through `URLSession.bytes(for:)` and are
/// stopped as soon as the endpoint-specific bound is exceeded.
///
/// The type never logs the origin, bearer token, path, metadata, or content.
final class HostFilesClient {
    typealias BearerRefreshProvider = () async throws -> String

    private static let maxListingBodyBytes = 512 * 1_024
    private static let maxReadBodyBytes = ((maxHostFileBytes + 2) / 3 * 4) + (64 * 1_024)

    let origin: String
    private var bearerToken: String?
    private let bearerTokenLock = NSLock()
    private let refreshProvider: BearerRefreshProvider?
    private let session: URLSession
    private let ownsSession: Bool

    convenience init(origin: String, bearerToken: String) throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration)
        try self.init(
            origin: origin,
            bearerToken: bearerToken,
            session: session,
            ownsSession: true,
            refreshProvider: nil
        )
    }

    convenience init(
        origin: String,
        bearerToken: String,
        refreshProvider: @escaping BearerRefreshProvider
    ) throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration)
        try self.init(
            origin: origin,
            bearerToken: bearerToken,
            session: session,
            ownsSession: true,
            refreshProvider: refreshProvider
        )
    }

    /// Injected sessions remain caller-owned and are never invalidated here.
    convenience init(origin: String, bearerToken: String, session: URLSession) throws {
        try self.init(
            origin: origin,
            bearerToken: bearerToken,
            session: session,
            ownsSession: false,
            refreshProvider: nil
        )
    }

    /// Cookie-authenticated variant used by the Android-parity basic login.
    /// The supplied session must share the cookie store that received the
    /// `/auth/password-login` response.
    convenience init(cookieAuthenticatedOrigin origin: String, session: URLSession = .shared) throws {
        try self.init(
            origin: origin,
            bearerToken: nil,
            session: session,
            ownsSession: false,
            refreshProvider: nil
        )
    }

    convenience init(
        origin: String,
        bearerToken: String,
        session: URLSession,
        refreshProvider: BearerRefreshProvider?
    ) throws {
        try self.init(
            origin: origin,
            bearerToken: bearerToken,
            session: session,
            ownsSession: false,
            refreshProvider: refreshProvider
        )
    }

    private init(
        origin: String,
        bearerToken: String?,
        session: URLSession,
        ownsSession: Bool,
        refreshProvider: BearerRefreshProvider?
    ) throws {
        guard let normalizedOrigin = ServerOrigin.normalize(origin) else {
            throw HostFilesClientError.invalidOrigin
        }
        if let bearerToken {
            guard !bearerToken.isEmpty,
                  !bearerToken.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
            else {
                throw HostFilesClientError.invalidBearerToken
            }
        }
        self.origin = normalizedOrigin
        self.bearerToken = bearerToken
        self.refreshProvider = refreshProvider
        self.session = session
        self.ownsSession = ownsSession
    }

    deinit {
        if ownsSession {
            session.finishTasksAndInvalidate()
        }
    }

    func list(path: String? = nil) async throws -> HostFileListing {
        let canonicalPath: String?
        if let path {
            guard let valid = validCanonicalHostFilePath(path) else {
                throw HostFilesClientError.invalidPath
            }
            canonicalPath = valid
        } else {
            canonicalPath = nil
        }
        let query = canonicalPath.map { [URLQueryItem(name: "path", value: $0)] } ?? []
        let (data, _) = try await get(
            endpoint: "/api/files",
            queryItems: query,
            accept: "application/json",
            maximumBytes: Self.maxListingBodyBytes
        )
        return try parseListing(data)
    }

    func read(path: String) async throws -> HostFileContent {
        guard let canonicalPath = validCanonicalHostFilePath(path) else {
            throw HostFilesClientError.invalidPath
        }
        let (data, _) = try await get(
            endpoint: "/api/files/read",
            queryItems: [URLQueryItem(name: "path", value: canonicalPath)],
            accept: "application/json",
            maximumBytes: Self.maxReadBodyBytes
        )
        return try parseReadContent(data)
    }

    func download(path: String) async throws -> HostFileContent {
        guard let canonicalPath = validCanonicalHostFilePath(path) else {
            throw HostFilesClientError.invalidPath
        }
        let (data, response) = try await get(
            endpoint: "/api/files/download",
            queryItems: [URLQueryItem(name: "path", value: canonicalPath)],
            accept: "*/*",
            maximumBytes: maxHostFileBytes
        )
        guard let rawContentType = response.value(forHTTPHeaderField: "Content-Type"),
              let mimeType = validHostFileMIMEType(rawContentType.components(separatedBy: ";")[0])
        else {
            throw HostFilesClientError.invalidMIMEType
        }
        let name = canonicalPath.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? canonicalPath
        guard let validName = validHostFileName(name) else {
            throw HostFilesClientError.malformedContent
        }
        return HostFileContent(name: validName, path: canonicalPath, mimeType: mimeType, bytes: data)
    }

    /// Creates one directory below a server-returned canonical parent, then
    /// reloads the canonical path returned by the managed mkdir endpoint.
    func createDirectory(parentPath: String, name: String) async throws -> HostFileListing {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let canonicalParent = validCanonicalHostFilePath(parentPath),
              let childName = validHostFileName(trimmedName),
              let createPath = joinedCreatePath(parent: canonicalParent, child: childName)
        else {
            throw HostFilesClientError.invalidPath
        }

        guard let url = URL(string: origin + "/api/files/mkdir") else {
            throw HostFilesClientError.invalidOrigin
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["path": createPath])

        let (data, _) = try await send(request, maximumBytes: Self.maxListingBodyBytes)
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["ok"] as? Bool == true,
              let returnedPath = validCanonicalHostFilePath(object.string("path"))
        else {
            throw HostFilesClientError.malformedDirectoryCreation
        }
        return try await list(path: returnedPath)
    }

    // MARK: - Bounded transport

    private func get(
        endpoint: String,
        queryItems: [URLQueryItem],
        accept: String,
        maximumBytes: Int
    ) async throws -> (Data, HTTPURLResponse) {
        guard var components = URLComponents(string: origin + endpoint) else {
            throw HostFilesClientError.invalidOrigin
        }
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let url = components.url else { throw HostFilesClientError.invalidOrigin }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(accept, forHTTPHeaderField: "Accept")
        return try await send(request, maximumBytes: maximumBytes)
    }

    private func send(
        _ request: URLRequest,
        maximumBytes: Int
    ) async throws -> (Data, HTTPURLResponse) {
        let initialToken = currentBearerToken()
        let initial = try await start(request, bearerToken: initialToken)

        if initial.response.statusCode == 401,
           let initialToken,
           refreshProvider != nil {
            let retryToken = try await refreshBearerToken(afterRejecting: initialToken)
            let retry = try await start(request, bearerToken: retryToken)
            return try await classifyAndRead(retry, maximumBytes: maximumBytes)
        }
        return try await classifyAndRead(initial, maximumBytes: maximumBytes)
    }

    private func start(
        _ request: URLRequest,
        bearerToken: String?
    ) async throws -> (bytes: URLSession.AsyncBytes, response: HTTPURLResponse) {
        var authenticatedRequest = request
        if let bearerToken {
            authenticatedRequest.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }

        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await session.bytes(for: authenticatedRequest)
        } catch let cancellation as CancellationError {
            throw cancellation
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw TransportError(underlying: error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw HostFilesClientError.invalidResponse
        }
        return (bytes, http)
    }

    private func classifyAndRead(
        _ attempt: (bytes: URLSession.AsyncBytes, response: HTTPURLResponse),
        maximumBytes: Int
    ) async throws -> (Data, HTTPURLResponse) {
        let bytes = attempt.bytes
        let http = attempt.response
        // Classify status before MIME inspection or body decoding. In particular,
        // malformed/large error bodies cannot hide an auth rejection.
        guard (200...299).contains(http.statusCode) else {
            if let classified = HermesAuthError.classify(http.statusCode) {
                throw classified
            }
            throw HostFilesClientError.httpStatus(http.statusCode)
        }

        if let rawLength = http.value(forHTTPHeaderField: "Content-Length") {
            guard let declaredLength = Int64(rawLength), declaredLength >= 0 else {
                throw HostFilesClientError.invalidContentLength
            }
            guard declaredLength <= Int64(maximumBytes) else {
                throw HostFilesClientError.responseTooLarge
            }
        }

        var data = Data()
        data.reserveCapacity(min(maximumBytes, 64 * 1_024))
        do {
            for try await byte in bytes {
                guard data.count < maximumBytes else {
                    throw HostFilesClientError.responseTooLarge
                }
                data.append(byte)
            }
        } catch let cancellation as CancellationError {
            throw cancellation
        } catch let error as HostFilesClientError {
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw TransportError(underlying: error)
        }
        return (data, http)
    }

    private func currentBearerToken() -> String? {
        bearerTokenLock.lock()
        defer { bearerTokenLock.unlock() }
        return bearerToken
    }

    /// Installs a refresh result only if the rejected token is still current.
    /// Concurrent refreshes may both complete, but a later stale result cannot
    /// overwrite the token already selected by another request.
    private func refreshBearerToken(afterRejecting rejectedToken: String) async throws -> String {
        guard let current = currentBearerToken() else { throw HermesAuthError.authRejected }
        if current != rejectedToken { return current }
        guard let refreshProvider else { throw HermesAuthError.authRejected }

        let refreshed = try await refreshProvider()
        guard refreshed != rejectedToken,
              !refreshed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !refreshed.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else {
            throw HostFilesClientError.invalidBearerToken
        }

        bearerTokenLock.lock()
        if bearerToken == rejectedToken {
            bearerToken = refreshed
        }
        let selectedToken = bearerToken
        bearerTokenLock.unlock()
        guard let selectedToken else { throw HermesAuthError.authRejected }
        return selectedToken
    }

    private func joinedCreatePath(parent: String, child: String) -> String? {
        let characters = Array(parent)
        let windowsStyle = characters.count >= 2 && characters[1] == ":"
        let separator: Character = windowsStyle ? "\\" : "/"
        var trimmedParent = parent
        while trimmedParent.last == "/" || trimmedParent.last == "\\" {
            trimmedParent.removeLast()
        }
        let joined = trimmedParent + String(separator) + child
        return validCanonicalHostFilePath(joined)
    }

    // MARK: - Tolerant contract decoding

    private func parseListing(_ data: Data) throws -> HostFileListing {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let path = validCanonicalHostFilePath(object.string("path"))
        else {
            throw HostFilesClientError.malformedListing
        }

        var entries: [HostFileEntry] = []
        var seenPaths = Set<String>()
        if let rows = object["entries"] as? [Any] {
            for row in rows {
                guard entries.count < maxHostFileEntries else { break }
                guard let entry = parseEntry(row), seenPaths.insert(entry.path).inserted else { continue }
                entries.append(entry)
            }
        }

        return HostFileListing(
            path: path,
            entries: entries,
            parentPath: object.string("parent").flatMap(validCanonicalHostFilePath),
            root: object.string("root").flatMap(validCanonicalHostFilePath),
            lockedRoot: object.string("locked_root").flatMap(validCanonicalHostFilePath),
            canChangePath: object["can_change_path"] as? Bool ?? true
        )
    }

    private func parseEntry(_ value: Any) -> HostFileEntry? {
        guard let row = value as? [String: Any],
              let name = validHostFileName(row.string("name")),
              let path = validCanonicalHostFilePath(row.string("path")),
              let isDirectory = row["is_directory"] as? Bool
        else { return nil }

        if let rawType = row["type"], !(rawType is NSNull) {
            guard let type = rawType as? String else { return nil }
            let canonicalType = type.lowercased()
            guard ["file", "directory", "dir"].contains(canonicalType) else { return nil }
            guard (canonicalType == "file") != isDirectory else { return nil }
        }

        let size = row.integer("size")
        if let rawSize = row["size"], !(rawSize is NSNull), size == nil { return nil }
        if let size, !(0...Int64(maxHostFileBytes)).contains(size) { return nil }

        let declaredMIME: String?
        if let rawMIME = row["mime_type"], !(rawMIME is NSNull) {
            guard let value = rawMIME as? String else { return nil }
            declaredMIME = value
        } else {
            declaredMIME = nil
        }
        let mimeType = declaredMIME.flatMap(validHostFileMIMEType)
        if declaredMIME != nil, mimeType == nil { return nil }

        let modified = row.number("mtime")
        if let rawModified = row["mtime"], !(rawModified is NSNull), modified == nil { return nil }
        if let modified, !modified.isFinite { return nil }

        return HostFileEntry(
            name: name,
            path: path,
            isDirectory: isDirectory,
            size: isDirectory ? nil : size,
            mimeType: isDirectory ? nil : mimeType,
            modifiedEpochSeconds: modified
        )
    }

    private func parseReadContent(_ data: Data) throws -> HostFileContent {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = validHostFileName(object.string("name")),
              let path = validCanonicalHostFilePath(object.string("path")),
              let mimeType = validHostFileMIMEType(object.string("mime_type")),
              let declaredSize = object.integer("size"),
              (0...Int64(maxHostFileBytes)).contains(declaredSize),
              let dataURL = object.string("data_url")
        else {
            throw HostFilesClientError.malformedContent
        }

        guard dataURL.hasPrefix("data:"),
              let comma = dataURL.firstIndex(of: ","),
              comma > dataURL.index(dataURL.startIndex, offsetBy: 5)
        else {
            throw HostFilesClientError.malformedContent
        }
        let metadata = String(dataURL[dataURL.index(dataURL.startIndex, offsetBy: 5)..<comma])
        let metadataParts = metadata.split(separator: ";", omittingEmptySubsequences: false)
        guard metadataParts.count >= 2,
              metadataParts.dropFirst().contains("base64"),
              let dataMIME = validHostFileMIMEType(String(metadataParts[0])),
              dataMIME == mimeType
        else {
            throw HostFilesClientError.invalidMIMEType
        }

        let encoded = String(dataURL[dataURL.index(after: comma)...])
        let maximumEncodedBytes = ((maxHostFileBytes + 2) / 3) * 4
        guard encoded.utf8.count <= maximumEncodedBytes,
              let bytes = Data(base64Encoded: encoded),
              bytes.count <= maxHostFileBytes
        else {
            throw HostFilesClientError.malformedContent
        }
        guard Int64(bytes.count) == declaredSize else {
            throw HostFilesClientError.contentSizeMismatch
        }
        return HostFileContent(name: name, path: path, mimeType: mimeType, bytes: bytes)
    }
}

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? { self[key] as? String }

    func integer(_ key: String) -> Int64? {
        guard let number = self[key] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double.rounded(.towardZero) == double,
              double >= Double(Int64.min), double <= Double(Int64.max)
        else { return nil }
        return number.int64Value
    }

    func number(_ key: String) -> Double? {
        guard let number = self[key] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else { return nil }
        return number.doubleValue
    }
}
