import Foundation
import XCTest
@testable import Mercury

final class M9OperationsTests: XCTestCase {
    func testCronListUsesReleasedPayloadAndTolerantBoundedDecode() async throws {
        let recorder = M9RPCRecorder { _, _ in
            ["jobs": [
                ["job_id": "job-1", "name": "Nightly", "schedule": "0 0 * * *", "enabled": false, "future": true],
                ["job_id": "job-1", "name": "duplicate", "schedule": "ignored"],
                ["id": "job-2", "name": String(repeating: "n", count: 700), "schedule": "@hourly", "next_run_at": 123],
                ["name": "missing id", "schedule": "never"],
                "malformed",
            ]]
        }
        let client = CronClient(request: { method, params in
            try await recorder.request(method, params)
        })

        let jobs = try await client.list(profile: "work")

        let methods = await recorder.methods
        let recordedParams = await recorder.params
        XCTAssertEqual(methods, ["cron.manage"])
        let params = try XCTUnwrap(recordedParams.first)
        XCTAssertEqual(params["action"] as? String, "list")
        XCTAssertEqual(params["include_disabled"] as? Bool, true)
        XCTAssertEqual(params["profile"] as? String, "work")
        XCTAssertEqual(jobs.map(\.id), ["job-1", "job-2"])
        XCTAssertEqual(jobs[1].name.count, OperationsBounds.maxFieldCharacters)
        XCTAssertEqual(jobs[1].nextRunAt, "123")
    }

    func testEnableDisableUseExactCronManageShapesWithoutInventingResponseFields() async throws {
        let recorder = M9RPCRecorder { _, _ in [:] }
        let client = CronClient(request: { method, params in
            try await recorder.request(method, params)
        })

        try await client.setEnabled(true, jobID: "job-enable")
        try await client.setEnabled(false, jobID: "job-disable")

        let params = await recorder.params
        XCTAssertEqual(params.compactMap { $0["action"] as? String }, ["resume", "pause"])
        XCTAssertEqual(params.compactMap { $0["name"] as? String }, ["job-enable", "job-disable"])
    }

    func testRunNowUsesOfficialRESTTriggerAndReturnsRefreshedJob() async throws {
        let recorder = M9HTTPRecorder(body: [
            "job_id": "job/a",
            "name": "Monitor",
            "schedule": "every 12h",
            "enabled": true,
            "state": "running",
        ])
        let client = CronRESTClient(
            origin: URL(string: "https://example.test")!,
            accessToken: "token",
            profile: "default",
            transport: { request in try await recorder.send(request) }
        )

        let result = try await client.trigger(jobID: "job/a")
        let job = try XCTUnwrap(result.refreshedJob)
        let recordedRequest = await recorder.request
        let request = try XCTUnwrap(recordedRequest)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/cron/jobs/job/a/trigger")
        XCTAssertTrue(request.url?.absoluteString.contains("job%2Fa") == true)
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "default")
        XCTAssertEqual(job.id, "job/a")
        XCTAssertEqual(job.state, "running")
        XCTAssertEqual(result.message, "Run requested; status refreshed from the server.")
    }

    func testRunNowAcceptsReleasedBackgroundAcknowledgementWithoutClaimingCompletion() async throws {
        let recorder = M9HTTPRecorder(body: ["accepted": true, "background": true])
        let client = CronRESTClient(
            origin: URL(string: "https://example.test")!,
            accessToken: "token",
            profile: "default",
            transport: { request in try await recorder.send(request) }
        )

        let result = try await client.trigger(jobID: "job")

        XCTAssertNil(result.refreshedJob)
        XCTAssertTrue(result.accepted)
        XCTAssertTrue(result.background)
        XCTAssertEqual(result.message, "Run accepted in the background.")
    }

    func testRunNowAcceptsSuccessfulEmptyLegacyResponseWithoutClaimingCompletion() async throws {
        let client = CronRESTClient(
            origin: URL(string: "https://example.test")!,
            accessToken: "token",
            profile: "default",
            transport: { request in
                (
                    Data(),
                    HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
                )
            }
        )

        let result = try await client.trigger(jobID: "job")

        XCTAssertNil(result.refreshedJob)
        XCTAssertTrue(result.accepted)
        XCTAssertFalse(result.background)
        XCTAssertEqual(result.message, "Run accepted.")
    }

    func testPerJobActionStateRejectsDoubleRunButAllowsAnotherJob() {
        var state = CronActionState()

        XCTAssertTrue(state.begin(jobID: "one", action: .runNow))
        XCTAssertFalse(state.begin(jobID: "one", action: .runNow))
        XCTAssertFalse(state.begin(jobID: "one", action: .disable))
        XCTAssertTrue(state.begin(jobID: "two", action: .enable))
        XCTAssertEqual(state.pendingAction(for: "one"), .runNow)
        XCTAssertEqual(state.pendingAction(for: "two"), .enable)

        state.finish(jobID: "one", message: "Accepted to run in the background.")
        XCTAssertNil(state.pendingAction(for: "one"))
        XCTAssertEqual(state.message(for: "one"), "Accepted to run in the background.")
        XCTAssertTrue(state.begin(jobID: "one", action: .runNow))
    }

    func testProcessListIsSessionScopedBoundedDeduplicatedAndTolerant() async throws {
        let rows: [Any] = (0..<60).map { index -> Any in
            ["session_id": "p-\(index)", "command": "command \(index)", "status": "running", "uptime_seconds": index] as [String: Any]
        } + [
            ["session_id": "p-0", "command": "duplicate", "status": "done"],
            ["command": "missing id", "status": "running"],
            "malformed",
        ]
        let recorder = M9RPCRecorder { _, _ in ["processes": rows] }
        let client = OperationsClient(request: { method, params in
            try await recorder.request(method, params)
        })

        let processes = try await client.listProcesses(runtimeSessionID: "runtime-1")

        let methods = await recorder.methods
        let recordedParams = await recorder.params
        XCTAssertEqual(methods, ["process.list"])
        XCTAssertEqual(recordedParams.first?["session_id"] as? String, "runtime-1")
        XCTAssertEqual(processes.count, OperationsBounds.maxProcessRows)
        XCTAssertEqual(processes.first?.id, "p-0")
        XCTAssertEqual(processes.first?.uptimeSeconds, 0)
    }

    func testActivityStackContainsOnlyAuditedFamilies() {
        let state = ActivityStackState(
            todos: [ActivityTodo(id: "t", content: "Ship", status: .inProgress)],
            loops: [ActivityLoop(id: "l", title: "Iteration 2", status: .running)],
            processes: [ActivityProcess(id: "p", command: "swift test", status: "running")]
        )

        XCTAssertEqual(state.visibleFamilies, [.todos, .loops, .processes])
        XCTAssertEqual(state.summary, "Activity · 0/1 tasks · 1 loop · 1 process-local process")
        XCTAssertTrue(state.isRunning)
    }

    func testOperationalStatusParsesOnlyBoundedPublicSubset() {
        let components = Dictionary(uniqueKeysWithValues: (0..<40).map { index in
            ("component-\(index)", ["status": index == 0 ? "healthy" : "future", "state": "ready", "secret": "ignored"])
        })
        let status = OperationalStatusParser.parse(
            [
                "version": "0.20.5",
                "overall": "degraded",
                "components": components,
                "memory": ["pressure": "elevated"],
                "disk_pressure": "critical",
                "unknown_family": ["must": "stay hidden"],
            ],
            profile: String(repeating: "p", count: 100)
        )

        XCTAssertEqual(status.profile.count, OperationsBounds.maxProfileCharacters)
        XCTAssertEqual(status.version, "0.20.5")
        XCTAssertEqual(status.overall, .degraded)
        XCTAssertEqual(status.components.count, OperationsBounds.maxOperationalComponents)
        XCTAssertEqual(status.components.first(where: { $0.name == "component-0" })?.health, .ok)
        XCTAssertEqual(status.memoryPressure, .warning)
        XCTAssertEqual(status.diskPressure, .critical)
    }
}

private actor M9RPCRecorder {
    typealias Handler = @Sendable (String, [String: Any]) async throws -> [String: Any]
    private(set) var methods: [String] = []
    private(set) var params: [[String: Any]] = []
    private let handler: Handler

    init(handler: @escaping Handler) { self.handler = handler }

    func request(_ method: String, _ params: [String: Any]) async throws -> [String: Any] {
        methods.append(method)
        self.params.append(params)
        return try await handler(method, params)
    }
}

private actor M9HTTPRecorder {
    private(set) var request: URLRequest?
    private let body: [String: Any]

    init(body: [String: Any]) { self.body = body }

    func send(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        self.request = request
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        return (try JSONSerialization.data(withJSONObject: body), response)
    }
}

private extension XCTestCase {
    func XCTAssertThrowsErrorAsync<T>(
        _ expression: @autoclosure () async throws -> T,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await expression()
            XCTFail("Expected expression to throw", file: file, line: line)
        } catch {}
    }
}
