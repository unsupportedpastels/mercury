import CoreFoundation
import Foundation

/// Shared JSON-RPC seam supplied by integration code that owns a connected
/// gateway. Keeping it injected lets M9 remain independent of ChatConnection.
typealias OperationsRPCRequest = @Sendable (String, [String: Any]) async throws -> [String: Any]

struct CronClient: @unchecked Sendable {
    private let request: OperationsRPCRequest

    init(request: @escaping OperationsRPCRequest) {
        self.request = request
    }

    func list(profile: String) async throws -> [CronJob] {
        let profile = try boundedInput(
            profile.trimmingCharacters(in: .whitespacesAndNewlines),
            max: OperationsBounds.maxProfileCharacters,
            label: "Cron profile"
        )
        let result = try await request("cron.manage", [
            "action": "list",
            "include_disabled": true,
            "profile": profile,
        ])
        return parseCronJobs(result)
    }

    /// Hermes resolves the `name` field by job ID or name. Released lifecycle
    /// verbs are `resume` and `pause`, not client-invented enable/disable verbs.
    func setEnabled(_ enabled: Bool, jobID: String) async throws {
        let id = try boundedInput(jobID, max: OperationsBounds.maxIDCharacters, label: "Cron job ID")
        _ = try await request("cron.manage", [
            "action": enabled ? "resume" : "pause",
            "name": id,
        ])
    }
}

enum CronRESTError: Error, Equatable {
    case invalidRequest
    case authenticationRejected
    case unsupported
    case transient(Int)
    case rejected(Int)
    case malformedResponse
    case transport(Int)
}

/// Run-now is the dashboard REST contract, not a `cron.manage` action. A
/// successful response is the refreshed job row; it is not a claim that a
/// background delivery completed.
struct CronRESTClient: @unchecked Sendable {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)

    private let origin: URL
    private let accessToken: String?
    private let profile: String
    private let transport: Transport

    init(origin: URL, accessToken: String?, profile: String, transport: @escaping Transport) {
        self.origin = origin
        self.accessToken = accessToken
        self.profile = profile
        self.transport = transport
    }

    init(origin: URL, accessToken: String?, profile: String, session: URLSession = .shared) {
        self.init(origin: origin, accessToken: accessToken, profile: profile) { request in
            do {
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else { throw CronRESTError.invalidRequest }
                return (data, http)
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError where error.code == .cancelled {
                throw CancellationError()
            } catch let error as CronRESTError {
                throw error
            } catch let error as URLError {
                throw CronRESTError.transport(error.code.rawValue)
            } catch {
                throw CronRESTError.transport((error as NSError).code)
            }
        }
    }

    func trigger(jobID: String) async throws -> CronTriggerResult {
        let request = try Self.makeTriggerRequest(
            origin: origin,
            accessToken: accessToken,
            profile: profile,
            jobID: jobID
        )
        let (data, response) = try await transport(request)
        guard data.count <= 128 * 1024 else { throw CronRESTError.malformedResponse }
        switch response.statusCode {
        case 200..<300: break
        case 401, 403: throw CronRESTError.authenticationRejected
        case 404, 405: throw CronRESTError.unsupported
        case 408, 425, 429, 500...599: throw CronRESTError.transient(response.statusCode)
        default: throw CronRESTError.rejected(response.statusCode)
        }
        if data.isEmpty {
            return CronTriggerResult(refreshedJob: nil, accepted: true, background: false)
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CronRESTError.malformedResponse
        }
        if object.isEmpty {
            return CronTriggerResult(refreshedJob: nil, accepted: true, background: false)
        }
        if let job = parseCronJob(object) {
            return CronTriggerResult(refreshedJob: job, accepted: true, background: false)
        }
        for key in ["job", "result", "data"] {
            if let nested = object[key] as? [String: Any], let job = parseCronJob(nested) {
                return CronTriggerResult(
                    refreshedJob: job,
                    accepted: strictBool(object["accepted"]) ?? true,
                    background: strictBool(object["background"]) ?? false
                )
            }
        }
        if let accepted = strictBool(object["accepted"]),
           let background = strictBool(object["background"]) {
            return CronTriggerResult(refreshedJob: nil, accepted: accepted, background: background)
        }
        throw CronRESTError.malformedResponse
    }

    static func makeTriggerRequest(
        origin: URL,
        accessToken: String?,
        profile: String,
        jobID: String
    ) throws -> URLRequest {
        guard let scheme = origin.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              origin.host != nil else { throw CronRESTError.invalidRequest }
        if let accessToken, accessToken.isEmpty { throw CronRESTError.invalidRequest }
        let boundedProfile = try boundedInput(
            profile.trimmingCharacters(in: .whitespacesAndNewlines),
            max: OperationsBounds.maxProfileCharacters,
            label: "Cron profile"
        )
        let boundedID = try boundedInput(jobID, max: OperationsBounds.maxIDCharacters, label: "Cron job ID")
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        guard let encodedID = boundedID.addingPercentEncoding(withAllowedCharacters: allowed) else {
            throw CronRESTError.invalidRequest
        }
        var components = URLComponents()
        components.scheme = scheme
        components.host = origin.host
        components.port = origin.port
        components.percentEncodedPath = "/api/cron/jobs/\(encodedID)/trigger"
        components.queryItems = [URLQueryItem(name: "profile", value: boundedProfile)]
        guard let url = components.url else { throw CronRESTError.invalidRequest }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 180
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        return request
    }
}

struct OperationsClient: @unchecked Sendable {
    private let request: OperationsRPCRequest

    init(request: @escaping OperationsRPCRequest) {
        self.request = request
    }

    func listProcesses(runtimeSessionID: String) async throws -> [ActivityProcess] {
        let sessionID = try boundedInput(
            runtimeSessionID,
            max: OperationsBounds.maxIDCharacters,
            label: "Runtime session ID"
        )
        let result = try await request("process.list", ["session_id": sessionID])
        return parseProcesses(result)
    }
}

private func parseCronJobs(_ result: [String: Any]) -> [CronJob] {
    guard let rawRows = result["jobs"] as? [Any] else { return [] }
    var seen = Set<String>()
    var jobs: [CronJob] = []
    for value in rawRows.prefix(OperationsBounds.maxCronRows) {
        guard let row = value as? [String: Any],
              let id = boundedScalarString(row["job_id"] ?? row["id"], max: OperationsBounds.maxFieldCharacters),
              let name = boundedScalarString(row["name"], max: OperationsBounds.maxFieldCharacters),
              let schedule = boundedScalarString(row["schedule"], max: OperationsBounds.maxFieldCharacters),
              seen.insert(id).inserted else { continue }
        jobs.append(CronJob(
            id: id,
            name: name,
            schedule: schedule,
            enabled: strictBool(row["enabled"]),
            state: boundedScalarString(row["state"], max: OperationsBounds.maxFieldCharacters),
            nextRunAt: boundedScalarString(row["next_run_at"], max: OperationsBounds.maxFieldCharacters),
            lastRunAt: boundedScalarString(row["last_run_at"], max: OperationsBounds.maxFieldCharacters),
            lastStatus: boundedScalarString(row["last_status"], max: OperationsBounds.maxFieldCharacters),
            lastDeliveryError: boundedScalarString(
                row["last_delivery_error"] ?? row["delivery_error"],
                max: OperationsBounds.maxFieldCharacters
            )
        ))
    }
    return jobs
}

private func parseCronJob(_ row: [String: Any]) -> CronJob? {
    guard let id = boundedScalarString(row["job_id"] ?? row["id"], max: OperationsBounds.maxFieldCharacters),
          let name = boundedScalarString(row["name"], max: OperationsBounds.maxFieldCharacters),
          let schedule = boundedScalarString(row["schedule"], max: OperationsBounds.maxFieldCharacters) else {
        return nil
    }
    return CronJob(
        id: id,
        name: name,
        schedule: schedule,
        enabled: strictBool(row["enabled"]),
        state: boundedScalarString(row["state"], max: OperationsBounds.maxFieldCharacters),
        nextRunAt: boundedScalarString(row["next_run_at"], max: OperationsBounds.maxFieldCharacters),
        lastRunAt: boundedScalarString(row["last_run_at"], max: OperationsBounds.maxFieldCharacters),
        lastStatus: boundedScalarString(row["last_status"], max: OperationsBounds.maxFieldCharacters),
        lastDeliveryError: boundedScalarString(
            row["last_delivery_error"] ?? row["delivery_error"],
            max: OperationsBounds.maxFieldCharacters
        )
    )
}

private func parseProcesses(_ result: [String: Any]) -> [ActivityProcess] {
    guard let rawRows = result["processes"] as? [Any] else { return [] }
    var seen = Set<String>()
    var rows: [ActivityProcess] = []
    for value in rawRows {
        guard rows.count < OperationsBounds.maxProcessRows else { break }
        guard let row = value as? [String: Any],
              let id = boundedScalarString(row["session_id"], max: OperationsBounds.maxIDCharacters),
              let command = boundedScalarString(row["command"], max: OperationsBounds.maxCommandCharacters),
              let status = boundedScalarString(row["status"], max: OperationsBounds.maxStatusCharacters),
              seen.insert(id).inserted else { continue }
        let uptime = nonnegativeInt64(row["uptime_seconds"] ?? row["uptime"])
        rows.append(ActivityProcess(
            id: id,
            command: command,
            status: status,
            outputTail: boundedScalarString(row["output_tail"], max: OperationsBounds.maxOutputCharacters),
            exitCode: boundedInt(row["exit_code"]),
            uptimeSeconds: uptime
        ))
    }
    return rows
}

private func boundedInput(_ value: String, max: Int, label: String) throws -> String {
    guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value.count <= max else {
        throw OperationsProtocolError.invalidInput("\(label) is invalid")
    }
    return value
}

private func boundedScalarString(_ value: Any?, max: Int) -> String? {
    let string: String
    switch value {
    case let value as String: string = value
    case let value as NSNumber where CFGetTypeID(value) != CFBooleanGetTypeID(): string = value.stringValue
    default: return nil
    }
    guard !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return String(string.prefix(max))
}

private func strictBool(_ value: Any?) -> Bool? {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
    return number.boolValue
}

private func nonnegativeInt64(_ value: Any?) -> Int64? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    let double = number.doubleValue
    guard double.isFinite, double.rounded(.towardZero) == double, double >= 0, double <= Double(Int64.max) else { return nil }
    return number.int64Value
}

private func boundedInt(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    let double = number.doubleValue
    guard double.isFinite, double.rounded(.towardZero) == double,
          double >= Double(Int.min), double <= Double(Int.max) else { return nil }
    return number.intValue
}
