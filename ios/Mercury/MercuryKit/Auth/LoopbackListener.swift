import Foundation
import Network

/// Thrown when no callback arrives within the deadline.
struct LoopbackTimeoutError: Error {}

/// Minimal loopback HTTP listener for OAuth redirects.
///
/// Binds to 127.0.0.1 on an ephemeral port and resolves the first
/// `GET /callback?...` request with its full URL. Any other path gets a 404
/// and the listener keeps waiting.
final class LoopbackListener {
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private let queue = DispatchQueue(label: "mercury.loopback.listener")

    private var continuation: CheckedContinuation<URL, Error>?
    private var redirectURL: URL?
    private var started = false
    private var timeoutWorkItem: DispatchWorkItem?

    deinit {
        stop()
    }

    /// Starts accepting on an ephemeral loopback port.
    /// - Returns: the redirect URI, e.g. `http://127.0.0.1:{port}/callback`.
    @discardableResult
    func start() async throws -> URL {
        if let redirectURL { return redirectURL }

        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(
            host: "127.0.0.1",
            port: 0
        )

        let listener = try NWListener(using: params)
        self.listener = listener

        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection: connection)
        }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var resumed = false
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if !resumed {
                        resumed = true
                        cont.resume()
                    }
                case .failed(let error):
                    if !resumed {
                        resumed = true
                        self?.listener = nil
                        cont.resume(throwing: error)
                    }
                case .cancelled:
                    if !resumed {
                        resumed = true
                        cont.resume(throwing: LoopbackTimeoutError())
                    }
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }

        guard let port = listener.port else {
            stop()
            throw FlowError.badResponse
        }
        started = true
        let url = URL(string: "http://127.0.0.1:\(port.rawValue)/callback")!
        redirectURL = url
        return url
    }

    /// Waits for the first `GET /callback?...` request.
    func waitForCallback(timeoutSeconds: TimeInterval = 300) async throws -> URL {
        try await withCheckedThrowingContinuation { cont in
            queue.async { [weak self] in
                guard let self else { return }
                if let url = self.redirectURL, self.continuation == nil, self.didReceiveCallback {
                    cont.resume(returning: url)
                    return
                }
                self.continuation = cont
                let work = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.fail(with: LoopbackTimeoutError())
                }
                self.timeoutWorkItem = work
                self.queue.asyncAfter(deadline: .now() + timeoutSeconds, execute: work)
            }
        }
    }

    /// Stops listening and closes all open connections. Idempotent.
    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.timeoutWorkItem?.cancel()
            self.timeoutWorkItem = nil
            for connection in self.connections {
                connection.cancel()
            }
            self.connections.removeAll()
            self.listener?.cancel()
            self.listener = nil
            self.fail(with: CancellationError())
        }
    }

    // MARK: - Private

    private var didReceiveCallback = false

    private func fail(with error: Error) {
        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        if let cont = continuation {
            continuation = nil
            cont.resume(throwing: error)
        }
    }

    private func handle(connection: NWConnection) {
        connections.append(connection)
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.readRequest(connection: connection)
            case .failed, .cancelled:
                self?.connections.removeAll { $0 === connection }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func readRequest(connection: NWConnection, buffer: Data = Data()) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var accumulated = buffer
            if let data { accumulated.append(data) }

            if let headerEnd = accumulated.range(of: Data("\r\n\r\n".utf8)) {
                let head = accumulated.subdata(in: accumulated.startIndex..<headerEnd.lowerBound)
                self.finishRequest(connection: connection, head: head)
                return
            }
            if error != nil || (isComplete && accumulated.isEmpty) {
                self.connections.removeAll { $0 === connection }
                return
            }
            if isComplete {
                // Connection closed without a complete header; nothing to do.
                self.connections.removeAll { $0 === connection }
                return
            }
            self.readRequest(connection: connection, buffer: accumulated)
        }
    }

    private func finishRequest(connection: NWConnection, head: Data) {
        let requestLine = String(decoding: head, as: UTF8.self)
            .split(separator: "\r\n", maxSplits: 1, omittingEmptySubsequences: false)
            .first
            .map(String.init) ?? ""

        // "GET /callback?code=X&state=Y HTTP/1.1" → target is between the
        // first and second spaces.
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        let target = parts.count >= 2 ? String(parts[1]) : ""

        let isCallback = Self.path(of: target) == "/callback"

        if isCallback {
            let body = "<html><body>Sign-in complete — return to Mercury.</body></html>"
            let response = Self.response(status: "200 OK", contentType: "text/html", body: body)
            connection.send(content: response, completion: .contentProcessed { [weak self] _ in
                connection.cancel()
                self?.connections.removeAll { $0 === connection }
            })
            // Complete the OAuth transaction as soon as the callback request
            // has been parsed. Waiting for NWConnection's send completion can
            // leave the app's continuation suspended even though the browser
            // has already rendered this response page.
            completeCallback(target: target)
        } else {
            let response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
                connection.cancel()
            })
            self.connections.removeAll { $0 === connection }
        }
    }

    private func completeCallback(target: String) {
        guard !didReceiveCallback else { return }
        didReceiveCallback = true

        var fullURL: URL?
        if let host = listener?.port {
            let encoded = target.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? target
            fullURL = URL(string: "http://127.0.0.1:\(host.rawValue)\(encoded)")
        }
        listener?.cancel()
        listener = nil
        redirectURL = fullURL ?? redirectURL

        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        if let cont = continuation, let url = fullURL {
            continuation = nil
            cont.resume(returning: url)
        }
    }

    /// Extracts the percent-decoded path from a request target.
    private static func path(of target: String) -> String {
        guard let url = URLComponents(string: "http://localhost\(target)") else { return "" }
        return url.path
    }

    private static func response(status: String, contentType: String, body: String) -> Data {
        let bodyData = Data(body.utf8)
        var head = "HTTP/1.1 \(status)\r\n"
        head += "Content-Type: \(contentType)\r\n"
        head += "Content-Length: \(bodyData.count)\r\n"
        head += "Connection: close\r\n\r\n"
        var out = Data(head.utf8)
        out.append(bodyData)
        return out
    }
}
