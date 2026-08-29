import Foundation
import Observation

/// One live relay connection: the admitted secure channel, its
/// `ChatConnection`, the in-process session inbox (`relay.sessions.list`),
/// and the active chat transcript.
///
/// Relay mode has no HTTP surface (ADR 0007 removed loopback tunneling), so
/// this model deliberately consumes only the approved v1 JSON-RPC methods
/// plus the `relay.*` reads; the direct-mode REST clients are never involved.
@MainActor
@Observable
final class RelaySessionModel {
    enum Phase: Equatable {
        case connecting
        case connected
        /// Admission was refused: still pending approval, denied, or revoked.
        case notAuthorized
        case offline
        case failed(String)
    }

    struct ChatRow: Identifiable, Equatable {
        let id = UUID()
        var role: String
        var text: String
    }

    struct PendingApproval: Equatable {
        let requestID: String?
        let command: String?
        let description: String?
        let choices: [String]
    }

    struct PendingClarify: Equatable {
        let requestID: String
        let question: String
        let choices: [String]
        let multiSelect: Bool
    }

    let target: RelayPairedTarget
    private let profile = "default"
    private let socketFactory: any RelayBinarySocketFactorying

    private(set) var phase: Phase = .connecting
    private(set) var sessions: [SessionRow] = []
    private(set) var transcript: [ChatRow] = []
    private(set) var runtimeSessionID: String?
    private(set) var running = false
    private(set) var approval: PendingApproval?
    private(set) var clarify: PendingClarify?
    var chatError: String?

    /// Last connection outcome, for debugging (never shown as UI copy).
    private(set) var lastConnectDetail = "—"

    private var connection: ChatConnection?
    private var chatSocket: RelayChatSocket?
    private var eventTask: Task<Void, Never>?

    init(
        target: RelayPairedTarget,
        socketFactory: any RelayBinarySocketFactorying = URLSessionRelaySocketFactory()
    ) {
        self.target = target
        self.socketFactory = socketFactory
    }

    // MARK: - Connection lifecycle

    private var connecting = false

    func connect() async {
        // Guard against re-entrant connects: a second device socket to the
        // same installation makes the router supersede the first (close 4001).
        guard !connecting, connection == nil else { return }
        connecting = true
        defer { connecting = false }
        phase = .connecting
        lastConnectDetail = "connecting"
        do {
            let connected = try await RelayConnector.connect(
                target: target, profile: profile, socketFactory: socketFactory
            )
            lastConnectDetail = "handshake+envelope sent"
            let socket = RelayChatSocket(connected: connected)
            let candidate = try ChatConnection(socket: socket)
            chatSocket = socket
            connection = candidate
            let stream = candidate.start()
            eventTask = Task { [weak self] in
                for await event in stream {
                    await MainActor.run { self?.handle(event) }
                }
                await MainActor.run { self?.handleStreamEnd() }
            }
            // Admission has no success ack: a pending/denied/revoked device
            // only observes the host closing the channel. Prove admission
            // with one allowed round trip before reporting connected.
            do {
                _ = try await candidate.relayRequest("gateway.ping")
            } catch {
                lastConnectDetail = "ping failed: \(error)"
                await disconnect()
                phase = .notAuthorized
                return
            }
            lastConnectDetail = "ping ok"
            phase = .connected
            await loadSessions()
        } catch let error as RelayConnectionError {
            lastConnectDetail = "connect error: \(error)"
            phase = error == .notAuthorized ? .notAuthorized : .offline
        } catch {
            lastConnectDetail = "other: \(error)"
            phase = .failed("The relay connection could not be established.")
        }
    }

    func disconnect() async {
        eventTask?.cancel()
        eventTask = nil
        // channel.close does not imply session.close: the host keeps a
        // running turn alive under its lease (PROTOCOL §9).
        await connection?.close()
        connection = nil
        chatSocket = nil
    }

    private func handleStreamEnd() {
        let code = chatSocket?.lastCloseCode()
        let detail = chatSocket?.lastErrorDetail()
        connection = nil
        chatSocket = nil
        if phase == .connected {
            lastConnectDetail = "ended close=\(code.map(String.init) ?? "nil") err=\(detail ?? "none")"
            phase = .offline
            running = false
        }
    }

    // MARK: - Inbox (relay.* in-process reads)

    func loadSessions() async {
        guard let connection else { return }
        do {
            let result = try await connection.relayRequest(
                "relay.sessions.list",
                params: ["profile": profile, "limit": 50, "offset": 0]
            )
            guard let rows = result["sessions"] as? [[String: Any]] else {
                sessions = []
                return
            }
            let data = try JSONSerialization.data(withJSONObject: rows)
            sessions = (try? JSONDecoder().decode([SessionRow].self, from: data)) ?? []
        } catch {
            // The inbox is best-effort; chat remains usable.
            sessions = []
        }
    }

    // MARK: - Chat

    func openSession(durableSessionID: String) async {
        guard let connection else { return }
        chatError = nil
        transcript = []
        approval = nil
        clarify = nil
        do {
            let resumed = try await connection.resume(
                durableSessionID: durableSessionID, profile: nil
            )
            runtimeSessionID = resumed.runtimeSessionID
            transcript = Self.rows(fromSnapshot: resumed.messages)
            running = resumed.running || resumed.inflight != nil
            if running, let inflight = resumed.inflight?.assistant, !inflight.isEmpty {
                transcript.append(ChatRow(role: "assistant", text: inflight))
            }
        } catch {
            chatError = "The session could not be resumed over the relay."
        }
    }

    func startNewSession() async {
        guard let connection else { return }
        chatError = nil
        transcript = []
        approval = nil
        clarify = nil
        do {
            let created = try await connection.createSession(profile: nil)
            runtimeSessionID = created.runtimeSessionID
            running = false
        } catch {
            chatError = "A new session could not be started over the relay."
        }
    }

    func submit(_ text: String) async {
        guard let connection, let runtimeSessionID else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        transcript.append(ChatRow(role: "user", text: trimmed))
        running = true
        do {
            _ = try await connection.submitPrompt(
                runtimeSessionID: runtimeSessionID, text: trimmed
            )
        } catch {
            running = false
            chatError = "The prompt could not be delivered."
        }
    }

    func respondToApproval(choice: String) async {
        guard let connection, let runtimeSessionID, let approval else { return }
        do {
            _ = try await connection.respondToApproval(
                runtimeSessionID: runtimeSessionID,
                choice: choice,
                requestID: approval.requestID
            )
            self.approval = nil
        } catch {
            chatError = "The approval response could not be delivered."
        }
    }

    func respondToClarify(answer: String) async {
        guard let connection, let clarify else { return }
        do {
            _ = try await connection.respondToClarification(
                requestID: clarify.requestID, answer: answer
            )
            self.clarify = nil
        } catch {
            chatError = "The answer could not be delivered."
        }
    }

    func interrupt() async {
        guard let connection, let runtimeSessionID else { return }
        _ = try? await connection.interruptSession(runtimeSessionID: runtimeSessionID)
    }

    // MARK: - Events

    private func handle(_ event: ChatEvent) {
        guard let runtimeSessionID, event.sessionID == runtimeSessionID else { return }
        switch event {
        case .messageStart(_, let text):
            running = true
            transcript.append(ChatRow(role: "assistant", text: text ?? ""))
        case .messageDelta(_, let text):
            appendToAssistantRow(text)
        case .messageInterim(_, let text, let alreadyStreamed):
            if !alreadyStreamed { appendToAssistantRow(text) }
        case .messageComplete(_, let text, _, let error, _, _, _, _, _):
            if let text, !text.isEmpty {
                replaceLastAssistantRow(with: text)
            }
            running = false
            if let error, !error.isEmpty { chatError = error }
        case .error(_, let message):
            running = false
            chatError = message
        case .approvalRequest(_, let requestID, let command, let description, let choices):
            approval = PendingApproval(
                requestID: requestID,
                command: command,
                description: description,
                choices: choices
            )
        case .approvalExpire:
            approval = nil
        case .clarifyRequest(_, let requestID, let question, let choices, let multiSelect):
            clarify = PendingClarify(
                requestID: requestID,
                question: question,
                choices: choices,
                multiSelect: multiSelect
            )
        case .clarifyExpire:
            clarify = nil
        case .toolStart(_, _, let name, _):
            transcript.append(ChatRow(role: "tool", text: name))
        case .sessionInfo(_, _, _, _, _, _, _, let running):
            if let running { self.running = running }
        default:
            break
        }
    }

    private func appendToAssistantRow(_ text: String) {
        if let index = transcript.lastIndex(where: { $0.role == "assistant" }),
           index == transcript.indices.last {
            transcript[index].text += text
        } else {
            transcript.append(ChatRow(role: "assistant", text: text))
        }
    }

    private func replaceLastAssistantRow(with text: String) {
        if let index = transcript.lastIndex(where: { $0.role == "assistant" }),
           index == transcript.indices.last {
            transcript[index].text = text
        } else {
            transcript.append(ChatRow(role: "assistant", text: text))
        }
    }

    private static func rows(fromSnapshot messages: [[String: Any]]) -> [ChatRow] {
        messages.compactMap { message in
            guard let role = message["role"] as? String else { return nil }
            let text = (message["content"] as? String)
                ?? (message["text"] as? String)
                ?? ""
            guard !text.isEmpty else { return nil }
            return ChatRow(role: role, text: text)
        }
    }
}
