import Foundation
import CryptoKit
import MercuryCore

struct ManagedVideoUnavailable: Error {}
struct ManagedVideoHTTPFailure: Error { let statusCode: Int }

/// A lease keeps the local file alive for the complete AVPlayer lifetime.
final class ManagedVideoFile {
    let url: URL
    init(url: URL) { self.url = url; ManagedVideoCache.retain(url) }
    deinit { ManagedVideoCache.release(url) }
}

enum ManagedVideoCache {
    private static let lock = NSRecursiveLock()
    private static var active: Set<URL> = []
    static let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("ManagedVideos", isDirectory: true)

    static func directory(origin: String, profile: String, root: URL = ManagedVideoCache.root) throws -> URL {
        let key = SHA256.hash(data: Data((origin + "\n" + profile).utf8)).map { String(format: "%02x", $0) }.joined()
        let directory = root.appendingPathComponent(key, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
    static func retain(_ url: URL) { lock.lock(); defer { lock.unlock() }; active.insert(url) }
    static func publish(part: URL, destination: URL) throws -> ManagedVideoFile {
        lock.lock(); defer { lock.unlock() }
        try FileManager.default.moveItem(at: part, to: destination)
        let file = ManagedVideoFile(url: destination)
        active.remove(part)
        return file
    }
    static func release(_ url: URL) { lock.lock(); defer { lock.unlock() }; active.remove(url); prune() }
    private static func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }
    static func prune(root: URL = ManagedVideoCache.root, limit: Int64 = ManagedVideoPolicy.shared.MAX_CACHE_BYTES) {
        lock.lock(); defer { lock.unlock() }
        let fm = FileManager.default
        let files = (fm.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey, .contentModificationDateKey])?.allObjects as? [URL] ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            .sorted { modified($0) < modified($1) }
        // In-flight partials are registered as active. Any other partial was
        // abandoned by a prior process and can never be a playable cache hit.
        for file in files where file.pathExtension == "part" && !active.contains(file) {
            try? fm.removeItem(at: file)
        }
        var total = files.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        for file in files where total > limit && !active.contains(file) {
            let size = Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            if (try? fm.removeItem(at: file)) != nil { total -= size }
        }
    }
}

/// Data-delegate streaming bounds bytes BEFORE disk writes, including chunked
/// and decompressed responses. No response-sized Data, URL credentials or redirects.
final class ManagedVideoDownload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var continuation: CheckedContinuation<ManagedVideoFile, Error>?
    private var handle: FileHandle?
    private var part: URL?
    private var destination: URL?
    private var received: Int64 = 0
    private var accepted = false
    private var cancelled = false
    private let maximumBytes: Int64

    init(maximumBytes: Int64 = ManagedVideoPolicy.shared.MAX_DOWNLOAD_BYTES) {
        self.maximumBytes = min(maximumBytes, ManagedVideoPolicy.shared.MAX_DOWNLOAD_BYTES)
    }

    func download(request: URLRequest, directory: URL,
                  configuration: URLSessionConfiguration = .ephemeral) async throws -> ManagedVideoFile {
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock(); defer { lock.unlock() }
                guard !cancelled else { continuation.resume(throwing: CancellationError()); return }
                self.continuation = continuation
                do {
                    let destination = directory.appendingPathComponent(UUID().uuidString + ".mp4")
                    let part = destination.appendingPathExtension("part")
                    self.destination = destination; self.part = part
                    ManagedVideoCache.retain(part)
                    guard FileManager.default.createFile(atPath: part.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
                    handle = try FileHandle(forWritingTo: part)
                    configuration.urlCache = nil
                    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                    configuration.timeoutIntervalForRequest = 30
                    configuration.timeoutIntervalForResource = 300
                    let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                    self.session = session
                    let task = session.dataTask(with: request)
                    self.task = task
                    task.resume()
                } catch { finish(error) }
            }
        } onCancel: { self.cancel() }
    }

    func cancel() {
        lock.lock(); defer { lock.unlock() }
        cancelled = true
        finish(CancellationError())
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        lock.lock(); defer { lock.unlock() }
        completionHandler(nil)
        finish(URLError(.redirectToNonExistentLocation))
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        lock.lock(); defer { lock.unlock() }
        guard continuation != nil, !cancelled,
              let http = response as? HTTPURLResponse else {
            completionHandler(.cancel); finish(URLError(.cannotDecodeContentData)); return
        }
        guard http.statusCode == 200 else {
            completionHandler(.cancel); finish(ManagedVideoHTTPFailure(statusCode: http.statusCode)); return
        }
        guard ManagedVideoPolicy.shared.isVideoMimeType(contentType: http.value(forHTTPHeaderField: "Content-Type") ?? ""),
              response.expectedContentLength <= maximumBytes else {
            completionHandler(.cancel); finish(URLError(.cannotDecodeContentData)); return
        }
        if let raw = http.value(forHTTPHeaderField: "Content-Length"),
           Int64(raw).map({ $0 >= 0 && $0 <= maximumBytes }) != true {
            completionHandler(.cancel); finish(ResponseTooLargeError()); return
        }
        accepted = true
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock(); defer { lock.unlock() }
        guard continuation != nil, accepted, !cancelled else { return }
        guard Int64(data.count) <= maximumBytes - received else { finish(ResponseTooLargeError()); return }
        do { try handle?.write(contentsOf: data); received += Int64(data.count) }
        catch { finish(error) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); defer { lock.unlock() }
        guard continuation != nil else { return }
        if let error { finish(error); return }
        guard accepted, received > 0, !cancelled, let part, let destination else {
            finish(URLError(.zeroByteResource)); return
        }
        do {
            try handle?.close(); handle = nil
            let file = try ManagedVideoCache.publish(part: part, destination: destination)
            self.part = nil
            let completion = continuation; continuation = nil
            session.finishTasksAndInvalidate(); self.session = nil; self.task = nil
            ManagedVideoCache.prune()
            completion?.resume(returning: file)
        } catch { finish(error) }
    }

    private func finish(_ error: Error) {
        task?.cancel(); task = nil
        session?.invalidateAndCancel(); session = nil
        try? handle?.close(); handle = nil
        if let part {
            try? FileManager.default.removeItem(at: part)
            ManagedVideoCache.release(part)
            self.part = nil
        }
        let completion = continuation; continuation = nil
        completion?.resume(throwing: error)
    }
}
