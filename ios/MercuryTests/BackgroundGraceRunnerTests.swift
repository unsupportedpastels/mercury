import XCTest
@testable import Mercury

/// The BackgroundGraceRunner timing/lifecycle logic is unit-testable with
/// injected reconcile/sleep/task closures (the real UIApplication background
/// task and 20s window are only meaningful on a device).
@MainActor
final class BackgroundGraceRunnerTests: XCTestCase {

    func testGraceLoopReconcilesMaxPollsThenEndsTask() async {
        var reconcileCount = 0
        var beginCount = 0
        var endCount = 0
        let finished = expectation(description: "grace loop finished")

        let runner = BackgroundGraceRunner(
            pollInterval: 0.001,
            maxWindow: 0.005,           // maxPolls = 5
            reconcile: { reconcileCount += 1 },
            beginTask: { beginCount += 1 },
            endTask: { endCount += 1; finished.fulfill() },
            sleep: { _ in }             // no real delay
        )

        runner.begin()
        await fulfillment(of: [finished], timeout: 2)

        XCTAssertEqual(beginCount, 1, "background task should be started exactly once")
        XCTAssertEqual(endCount, 1, "background task should be ended exactly once")
        XCTAssertEqual(reconcileCount, 5, "should reconcile once per poll up to maxPolls")
    }

    func testBeginIsIdempotentWhileRunning() async {
        var beginCount = 0
        let finished = expectation(description: "finished")

        let runner = BackgroundGraceRunner(
            pollInterval: 0.001,
            maxWindow: 0.001,           // maxPolls = 1
            reconcile: {
                // Re-entrant begin() during the window must not start a 2nd task.
            },
            beginTask: { beginCount += 1 },
            endTask: { finished.fulfill() },
            sleep: { _ in }
        )

        runner.begin()
        runner.begin()                  // second call must no-op
        await fulfillment(of: [finished], timeout: 2)

        XCTAssertEqual(beginCount, 1)
    }

    func testEndBeforeBeginIsSafeNoOp() {
        var endCount = 0
        let runner = BackgroundGraceRunner(
            reconcile: {},
            beginTask: {},
            endTask: { endCount += 1 },
            sleep: { _ in }
        )
        // Ending without an active window must not touch the platform task.
        runner.end()
        XCTAssertEqual(endCount, 0)
    }
}
