import Foundation
import Network

/// Real HTTP redirects for URLSession tests. URLProtocol redirect handoffs
/// cannot safely be followed by an unconditional finish callback, and omitting
/// that callback strands a request when its delegate refuses the redirect.
/// Bind only to loopback, use an ephemeral port, and keep all state on one queue.
final class LoopbackHTTPTestServer: @unchecked Sendable {
    private let listener: NWListener
    private let response: Data
    private let queue = DispatchQueue(label: "mercury.tests.http")
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var startContinuation: CheckedContinuation<URL, Error>?
    private var receivedRequests = 0

    init(redirectTo destination: URL? = nil) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: 0)
        listener = try NWListener(using: parameters)
        let headers = destination.map {
            "HTTP/1.1 302 Found\r\nLocation: \($0.absoluteString)\r\n"
        } ?? "HTTP/1.1 200 OK\r\n"
        response = Data((headers + "Content-Length: 0\r\nConnection: close\r\n\r\n").utf8)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.accept(connection)
        }
    }

    var requestCount: Int { queue.sync { receivedRequests } }

    func start() async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                self.startContinuation = continuation
                self.listener.stateUpdateHandler = { [weak self] state in
                    guard let self, let pending = self.startContinuation else { return }
                    switch state {
                    case .ready:
                        self.startContinuation = nil
                        if let port = self.listener.port,
                           let url = URL(string: "http://127.0.0.1:\(port.rawValue)") {
                            pending.resume(returning: url)
                        } else {
                            pending.resume(throwing: URLError(.cannotFindHost))
                        }
                    case .failed(let error):
                        self.startContinuation = nil
                        pending.resume(throwing: error)
                    case .cancelled:
                        self.startContinuation = nil
                        pending.resume(throwing: CancellationError())
                    default:
                        break
                    }
                }
                self.listener.start(queue: self.queue)
                self.queue.asyncAfter(deadline: .now() + 5) { [weak self] in
                    guard let self, let pending = self.startContinuation else { return }
                    self.startContinuation = nil
                    pending.resume(throwing: URLError(.timedOut))
                    self.listener.cancel()
                }
            }
        }
    }

    /// Call from the test's defer, including when start or the request fails.
    func stop() {
        queue.sync {
            listener.cancel()
            for connection in connections.values { connection.cancel() }
            connections.removeAll()
            startContinuation?.resume(throwing: CancellationError())
            startContinuation = nil
        }
    }

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.readHeaders(connection, buffer: Data())
            case .failed, .cancelled:
                self.connections.removeValue(forKey: id)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func readHeaders(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8_192) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            var buffer = buffer
            if let data { buffer.append(data) }
            guard buffer.count <= 16_384, error == nil else {
                connection.cancel()
                return
            }
            if buffer.range(of: Data("\r\n\r\n".utf8)) != nil {
                self.receivedRequests += 1
                connection.send(content: self.response, completion: .contentProcessed { _ in
                    connection.cancel()
                })
            } else if complete {
                connection.cancel()
            } else {
                self.readHeaders(connection, buffer: buffer)
            }
        }
    }
}
