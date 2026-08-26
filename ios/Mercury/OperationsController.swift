import Foundation

@MainActor
final class OperationsController: ObservableObject {
    @Published private(set) var jobs: [CronJob] = []
    @Published private(set) var isLoading = false
    @Published private(set) var loadError: String?
    @Published private(set) var actionState = CronActionState()

    private var connection: ChatConnection?
    private var eventTask: Task<Void, Never>?
    private var client: CronClient?
    private var restClient: CronRESTClient?

    func connect(appModel: AppModel) async {
        guard connection == nil,
              let origin = appModel.serverOrigin,
              let originURL = URL(string: origin) else {
            loadError = "Sign in to load scheduled jobs."
            return
        }
        let token = storedAccessToken(origin: origin)
        isLoading = true
        loadError = nil
        do {
            let gateway = try ChatGateway(
                origin: origin,
                accessToken: token,
                ticketClient: WsTicketClient(session: .shared),
                socketFactory: URLSessionChatWebSocketFactory()
            )
            let socket = try await gateway.connect()
            let connection = try ChatConnection(socket: socket)
            self.connection = connection
            let events = connection.start()
            eventTask = Task {
                for await _ in events {
                    if Task.isCancelled { break }
                }
            }
            client = CronClient(request: { method, params in
                try await connection.operationsRequest(method, params: params)
            })
            restClient = CronRESTClient(
                origin: originURL,
                accessToken: token,
                profile: appModel.activeProfile
            )
            try await refresh(profile: appModel.activeProfile)
        } catch {
            isLoading = false
            loadError = "Scheduled jobs are unavailable on this server."
        }
    }

    func refresh(profile: String) async throws {
        guard let client else { throw OperationsProtocolError.invalidInput("Cron connection is unavailable") }
        isLoading = true
        loadError = nil
        defer { isLoading = false }
        do {
            jobs = try await client.list(profile: profile)
        } catch {
            loadError = "Scheduled jobs are unavailable on this server."
            throw error
        }
    }

    func setEnabled(_ enabled: Bool, job: CronJob, profile: String) async {
        let action: CronPendingAction = enabled ? .enable : .disable
        guard actionState.begin(jobID: job.id, action: action), let client else { return }
        do {
            try await client.setEnabled(enabled, jobID: job.id)
            actionState.finish(jobID: job.id, message: enabled ? "Job enabled." : "Job paused.")
            try await refresh(profile: profile)
        } catch {
            actionState.finish(jobID: job.id, message: "The job could not be updated.")
        }
    }

    func runNow(_ job: CronJob) async {
        guard actionState.begin(jobID: job.id, action: .runNow), let restClient else { return }
        do {
            let result = try await restClient.trigger(jobID: job.id)
            if let refreshed = result.refreshedJob,
               let index = jobs.firstIndex(where: { $0.id == refreshed.id }) {
                jobs[index] = refreshed
            }
            actionState.finish(jobID: job.id, message: result.message)
        } catch CronRESTError.unsupported {
            actionState.finish(jobID: job.id, message: "Run now is not supported by this server.")
        } catch CronRESTError.rejected(409) {
            actionState.finish(jobID: job.id, message: "Job is already running or was just claimed.")
        } catch CronRESTError.authenticationRejected {
            actionState.finish(jobID: job.id, message: "Run request was rejected by authentication.")
        } catch CronRESTError.transient(let status) {
            actionState.finish(jobID: job.id, message: "Run request hit a temporary HTTP \(status) error.")
        } catch CronRESTError.rejected(let status) {
            actionState.finish(jobID: job.id, message: "Run request was rejected with HTTP \(status).")
        } catch CronRESTError.malformedResponse {
            actionState.finish(jobID: job.id, message: "Run request returned an invalid response.")
        } catch CronRESTError.invalidRequest {
            actionState.finish(jobID: job.id, message: "Run request could not be built.")
        } catch CronRESTError.transport(let code) {
            actionState.finish(jobID: job.id, message: "Run request hit network transport error \(code).")
        } catch {
            actionState.finish(jobID: job.id, message: "The run request failed.")
        }
    }

    func stop() async {
        eventTask?.cancel()
        eventTask = nil
        let active = connection
        connection = nil
        client = nil
        restClient = nil
        await active?.close()
    }

    private func storedAccessToken(origin: String) -> String? {
        guard let pair = KeychainCredentialStore().tokens(for: origin),
              let token = String(data: pair.accessToken, encoding: .utf8),
              !token.isEmpty else { return nil }
        return token
    }
}
