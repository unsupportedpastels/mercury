import XCTest
@testable import Mercury

/// Hermetic tests for the hardened Portal device-code poll loop
/// (`PortalPoller`). Both the poll attempt and the sleep are stubbed
/// closures, so no network and no real waiting is involved.
final class CloudPollTests: XCTestCase {

    // MARK: - nextInterval (pure interval logic)

    /// Intervals below the 1s floor clamp up to 1.
    func testNextIntervalClampsBelowOneToOne() {
        XCTAssertEqual(PortalPoller.nextInterval(current: 0, outcome: .pending), 1)
        XCTAssertEqual(PortalPoller.nextInterval(current: -5, outcome: .pending), 1)
        XCTAssertEqual(PortalPoller.nextInterval(current: 0, outcome: .slowDown(interval: 0)), 1)
    }

    /// Intervals above the 30s ceiling clamp down to 30.
    func testNextIntervalClampsAboveThirtyToThirty() {
        XCTAssertEqual(PortalPoller.nextInterval(current: 31, outcome: .pending), 30)
        XCTAssertEqual(PortalPoller.nextInterval(current: 100, outcome: .pending), 30)
        XCTAssertEqual(PortalPoller.nextInterval(current: 28, outcome: .slowDown(interval: 99)), 30)
    }

    /// slow_down raises the interval by exactly 5 seconds.
    func testSlowDownAddsExactlyFive() {
        XCTAssertEqual(PortalPoller.nextInterval(current: 5, outcome: .slowDown(interval: 10)), 10)
        XCTAssertEqual(PortalPoller.nextInterval(current: 12, outcome: .slowDown(interval: 17)), 17)
    }

    /// clamp() bounds, exercised directly.
    func testClampBounds() {
        XCTAssertEqual(PortalPoller.clamp(0), 1)
        XCTAssertEqual(PortalPoller.clamp(-3), 1)
        XCTAssertEqual(PortalPoller.clamp(1), 1)
        XCTAssertEqual(PortalPoller.clamp(15), 15)
        XCTAssertEqual(PortalPoller.clamp(30), 30)
        XCTAssertEqual(PortalPoller.clamp(999), 30)
    }

    // MARK: - run (loop driver)

    /// Serially accessed recorder for poll/sleep calls; each test awaits a
    /// single `run` to completion before asserting, so plain vars suffice.
    private struct PollCall: Equatable {
        let code: String
        let interval: Int
    }

    private final class Log {
        var polls: [PollCall] = []
        var sleeps: [Int] = []
    }

    /// A poll that throws a transient network error is treated as
    /// authorization_pending: the loop keeps polling at the same interval.
    /// (Browser-steals-focus network teardown must not kill sign-in.)
    func testTransientNetworkErrorKeepsPolling() async throws {
        let log = Log()
        let tokens = TokenSet(accessToken: "at", refreshToken: "rt")
        let outcomes: [Result<PortalClient.DevicePollOutcome, Error>] = [
            .failure(URLError(.notConnectedToInternet)),
            .failure(URLError(.dnsLookupFailed)),
            .success(.success(tokens)),
        ]

        let result = try await PortalPoller.run(
            deviceCode: "dc_1",
            initialInterval: 5,
            poll: { code, interval in
                log.polls.append(PollCall(code: code, interval: interval))
                return try outcomes[min(log.polls.count - 1, outcomes.count - 1)].get()
            },
            sleep: { log.sleeps.append($0) }
        )

        XCTAssertEqual(result, tokens)
        XCTAssertEqual(log.polls.count, 3, "transient failures must not stop the loop")
        XCTAssertEqual(log.polls.map(\.code), ["dc_1", "dc_1", "dc_1"])
        XCTAssertEqual(log.polls.map(\.interval), [5, 5, 5], "interval unchanged across transient failures")
        XCTAssertEqual(log.sleeps, [5, 5, 5])
    }

    /// Non-network failures are not authorization_pending and must propagate
    /// rather than being hidden until the device code expires.
    func testNonTransientPollErrorPropagates() async {
        struct FixtureError: Error {}

        do {
            _ = try await PortalPoller.run(
                deviceCode: "dc_failure",
                initialInterval: 5,
                poll: { _, _ in throw FixtureError() },
                sleep: { _ in }
            )
            XCTFail("expected FixtureError")
        } catch is FixtureError {
            // expected
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    /// The server-suggested interval is clamped into 1...30 before the first
    /// poll, and slow_down raises the interval for the following attempt.
    func testIntervalClampedAtStartAndRaisedOnSlowDown() async throws {
        let log = Log()
        let tokens = TokenSet(accessToken: "at", refreshToken: "rt")

        // initialInterval 0 → first poll sees the 1s floor; then slow_down
        // (server says 6 = 1+5) → second poll sees 6.
        _ = try await PortalPoller.run(
            deviceCode: "dc_2",
            initialInterval: 0,
            poll: { _, interval in
                log.polls.append(PollCall(code: "dc_2", interval: interval))
                return log.polls.count == 1 ? .slowDown(interval: 6) : .success(tokens)
            },
            sleep: { log.sleeps.append($0) }
        )

        XCTAssertEqual(log.polls.map(\.interval), [1, 6])
        XCTAssertEqual(log.sleeps, [1, 6])

        // initialInterval 999 → first poll sees the 30s ceiling.
        let log2 = Log()
        _ = try await PortalPoller.run(
            deviceCode: "dc_3",
            initialInterval: 999,
            poll: { _, interval in
                log2.polls.append(PollCall(code: "dc_3", interval: interval))
                return .success(tokens)
            },
            sleep: { log2.sleeps.append($0) }
        )
        XCTAssertEqual(log2.polls.map(\.interval), [30])
        XCTAssertEqual(log2.sleeps, [30])
    }

    /// A terminal outcome exits immediately with PortalTerminalError; no
    /// further polls are attempted.
    func testTerminalOutcomeExitsImmediately() async {
        let log = Log()

        do {
            _ = try await PortalPoller.run(
                deviceCode: "dc_4",
                initialInterval: 5,
                poll: { _, interval in
                    log.polls.append(PollCall(code: "dc_4", interval: interval))
                    return .terminal("expired_token")
                },
                sleep: { log.sleeps.append($0) }
            )
            XCTFail("expected PortalTerminalError")
        } catch let error as PortalTerminalError {
            XCTAssertEqual(error.reason, "expired_token")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }

        XCTAssertEqual(log.polls.count, 1, "terminal outcome must exit after one attempt")
        XCTAssertEqual(log.sleeps, [5])
    }

    /// Success exits immediately with the tokens; no further polls.
    func testSuccessExitsWithTokens() async throws {
        let log = Log()
        let tokens = TokenSet(accessToken: "at_ok", refreshToken: "rt_ok")

        let result = try await PortalPoller.run(
            deviceCode: "dc_5",
            initialInterval: 5,
            poll: { _, interval in
                log.polls.append(PollCall(code: "dc_5", interval: interval))
                return .success(tokens)
            },
            sleep: { log.sleeps.append($0) }
        )

        XCTAssertEqual(result, tokens)
        XCTAssertEqual(log.polls.count, 1)
        XCTAssertEqual(log.sleeps, [5])
    }

    /// Polling stops at the device code's expiry instead of continuing forever
    /// when the Portal keeps returning authorization_pending.
    func testExpiryStopsPendingPollLoop() async {
        let log = Log()
        var now: Int64 = 0

        do {
            _ = try await PortalPoller.run(
                deviceCode: "dc_expiring",
                initialInterval: 5,
                expiresIn: 10,
                nowSeconds: { now },
                poll: { _, interval in
                    log.polls.append(PollCall(code: "dc_expiring", interval: interval))
                    return .pending
                },
                sleep: { seconds in
                    log.sleeps.append(seconds)
                    now += Int64(seconds)
                }
            )
            XCTFail("expected expired_token")
        } catch let error as PortalTerminalError {
            XCTAssertEqual(error.reason, "expired_token")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }

        XCTAssertEqual(log.polls.count, 2)
        XCTAssertEqual(log.sleeps, [5, 5])
    }

    /// Cancelling the surrounding task exits the loop with CancellationError
    /// instead of polling forever.
    func testTaskCancellationExitsLoop() async {
        let log = Log()
        let handle = Task {
            try await PortalPoller.run(
                deviceCode: "dc_6",
                initialInterval: 5,
                poll: { _, interval in
                    log.polls.append(PollCall(code: "dc_6", interval: interval))
                    return .pending
                },
                // A real sleep honours cancellation; emulate that here.
                sleep: { _ in try Task.checkCancellation() }
            )
        }

        // Give the loop a moment to start, then cancel.
        try? await Task.sleep(nanoseconds: 50_000_000)
        handle.cancel()

        do {
            _ = try await handle.value
            XCTFail("expected CancellationError")
        } catch is CancellationError {
            // expected
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }
}
