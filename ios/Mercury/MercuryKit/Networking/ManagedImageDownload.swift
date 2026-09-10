import Foundation

struct ManagedImageHTTPFailure: Error {
    let statusCode: Int
}

/// Incremental in-memory transport for managed images. It owns only a child
/// session made from the caller's configuration, so injected protocol classes,
/// cookie storage and cache policy are retained without invalidating the caller.
final class ManagedImageDownload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private let maximumBytes: Int64
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var continuation: CheckedContinuation<Data, Error>?
    private var data = Data()
    private var accepted = false
    private var cancelled = false

    init(maximumBytes: Int) {
        self.maximumBytes = Int64(max(0, min(maximumBytes, HermesHTTPClient.maxManagedImageBytes)))
        super.init()
    }

    func download(
        request: URLRequest,
        configuration: URLSessionConfiguration
    ) async throws -> Data {
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                defer { lock.unlock() }
                guard !cancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation
                data.reserveCapacity(Int(min(maximumBytes, 64 * 1_024)))
                configuration.urlCache = nil
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                let childSession = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                session = childSession
                let task = childSession.dataTask(with: request)
                self.task = task
                task.resume()
            }
        } onCancel: {
            self.cancel()
        }
    }

    func cancel() {
        lock.lock()
        defer { lock.unlock() }
        cancelled = true
        finish(CancellationError())
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        lock.lock()
        defer { lock.unlock() }
        completionHandler(nil)
        finish(URLError(.redirectToNonExistentLocation))
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        defer { lock.unlock() }
        guard continuation != nil, !cancelled, let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            finish(URLError(.cannotDecodeContentData))
            return
        }
        guard (200..<300).contains(http.statusCode) else {
            completionHandler(.cancel)
            finish(ManagedImageHTTPFailure(statusCode: http.statusCode))
            return
        }
        guard (http.value(forHTTPHeaderField: "Content-Type") ?? "")
            .lowercased().hasPrefix("image/") else {
            completionHandler(.cancel)
            finish(URLError(.cannotDecodeContentData))
            return
        }
        if let rawLength = http.value(forHTTPHeaderField: "Content-Length") {
            guard let declaredLength = Int64(rawLength), declaredLength >= 0 else {
                completionHandler(.cancel)
                finish(URLError(.cannotDecodeContentData))
                return
            }
            guard declaredLength <= maximumBytes else {
                completionHandler(.cancel)
                finish(ResponseTooLargeError())
                return
            }
        }
        accepted = true
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        defer { lock.unlock() }
        guard continuation != nil, accepted, !cancelled else { return }
        guard Int64(chunk.count) <= maximumBytes - Int64(data.count) else {
            finish(ResponseTooLargeError())
            return
        }
        data.append(chunk)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        defer { lock.unlock() }
        guard continuation != nil else { return }
        if let error {
            if cancelled || (error as? URLError)?.code == .cancelled {
                finish(CancellationError())
            } else {
                finish(TransportError(underlying: error))
            }
            return
        }
        guard accepted, !cancelled else {
            finish(URLError(.cannotDecodeContentData))
            return
        }
        let result = data
        let completion = continuation
        continuation = nil
        self.task = nil
        session.finishTasksAndInvalidate()
        self.session = nil
        completion?.resume(returning: result)
    }

    private func finish(_ error: Error) {
        task?.cancel()
        task = nil
        session?.invalidateAndCancel()
        session = nil
        data.removeAll(keepingCapacity: false)
        let completion = continuation
        continuation = nil
        completion?.resume(throwing: error)
    }
}
