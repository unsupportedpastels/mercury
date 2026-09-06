package com.unsupportedpastels.hermesandroid.relay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDiagnosticsTest {
    @Test
    fun recordsUtcAndMonotonicDurationForEachAttemptPhase() {
        var utcMillis = 1_756_400_000_000L
        var monotonicNanos = 10_000_000_000L
        val diagnostics = RelayDiagnostics(
            capacity = 16,
            utcMillis = { utcMillis },
            monotonicNanos = { monotonicNanos },
            attemptIdFactory = { "attempt-123" },
            appBuildMetadata = RelayAppBuildMetadata("0.2.2", 17),
            logger = {},
        )

        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        utcMillis += 2_000L
        monotonicNanos += 2_750_000_000L
        attempt.recordSuccess(RelayDiagnosticPhase.Open)

        val event = diagnostics.snapshot().last()
        assertEquals("attempt-123", event.attemptId)
        assertEquals("2025-08-28T16:53:22Z", event.utc)
        assertEquals(2_750L, event.monotonicDurationMillis)
        assertEquals(RelayDiagnosticPhase.Open, event.phase)
        assertEquals(RelayDiagnosticStatus.Succeeded, event.status)
        assertEquals("0.2.2", event.appVersionName)
        assertEquals(17L, event.appVersionCode)
    }

    @Test
    fun ringBufferEvictsOldestEventsAndRejectsUnboundedCapacity() {
        assertThrowsIllegalArgument { RelayDiagnostics(capacity = 0) }
        assertThrowsIllegalArgument { RelayDiagnostics(capacity = RelayDiagnostics.MAX_CAPACITY + 1) }
        assertThrowsIllegalArgument { RelayDiagnostics(capacity = Int.MAX_VALUE) }

        val diagnostics = RelayDiagnostics(
            capacity = 3,
            attemptIdFactory = { "bounded" },
            logger = {},
        )
        repeat(4) {
            diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        }

        val events = diagnostics.snapshot()
        assertEquals(3, events.size)
        assertTrue(events.all { it.attemptId == "bounded" })
        assertEquals(RelayDiagnosticStatus.Started, events.first().status)
    }

    @Test
    fun failureTimeoutCancellationAndDisconnectUseClosedSafeReasons() {
        val diagnostics = RelayDiagnostics(
            capacity = 16,
            attemptIdFactory = { "safe-attempt" },
            logger = {},
        )

        val failed = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        failed.recordFailure(RelayDiagnosticPhase.Handshake, RelayDiagnosticReason.NotAuthorized)
        failed.recordFailure(RelayDiagnosticPhase.Admission, RelayDiagnosticReason.TransportFailure)
        failed.recordDisconnect(RelayDiagnosticCloseReason.LocalFailure)

        val timedOut = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        timedOut.recordTimeout(RelayDiagnosticPhase.Open)

        val cancelled = diagnostics.beginAttempt(RelayDiagnosticOperation.Pairing)
        cancelled.recordCancellation()
        cancelled.recordDisconnect(RelayDiagnosticCloseReason.Cancelled)

        val events = diagnostics.snapshot()
        assertEquals(8, events.size)
        assertEquals(RelayDiagnosticStatus.Failed, events[1].status)
        assertEquals(RelayDiagnosticReason.NotAuthorized, events[1].reason)
        assertEquals(RelayDiagnosticPhase.Disconnect, events[2].phase)
        assertEquals(RelayDiagnosticCloseReason.LocalFailure, events[2].closeReason)
        assertEquals(RelayDiagnosticStatus.TimedOut, events[4].status)
        assertEquals(RelayDiagnosticStatus.Cancelled, events[6].status)
        assertEquals(RelayDiagnosticCloseReason.Cancelled, events[7].closeReason)
    }

    @Test
    fun logLinesContainOnlyBoundedDiagnosticFields() {
        val logs = mutableListOf<String>()
        val diagnostics = RelayDiagnostics(
            capacity = 8,
            attemptIdFactory = { "safe-id" },
            appBuildMetadata = RelayAppBuildMetadata(
                versionName = "release\nhttps://secret.example/token?x=credential",
                versionCode = -1,
            ),
            logger = logs::add,
        )
        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        attempt.recordFailure(RelayDiagnosticPhase.Admission, RelayDiagnosticReason.ProtocolViolation)
        attempt.recordDisconnect(RelayDiagnosticCloseReason.ProtocolViolation)

        val combined = logs.joinToString("\n")
        assertFalse(combined.contains("https://"))
        assertFalse(combined.contains("secret.example"))
        assertFalse(combined.contains("credential"))
        assertFalse(combined.contains("routing"))
        assertFalse(combined.contains("device"))
        assertFalse(combined.contains("session"))
        assertFalse(combined.contains("token"))
        assertTrue(combined.contains("event=relay_diagnostic"))
        assertTrue(combined.contains("phase=admission"))
        assertTrue(combined.contains("reason=protocol_violation"))
        assertNotNull(diagnostics.snapshot().first().appVersionName)
        assertTrue(diagnostics.snapshot().first().appVersionName.length <= 32)
        assertEquals(null, diagnostics.snapshot().first().appVersionCode)
    }

    @Test
    fun diagnosticClockIdAndLoggerFailuresDoNotEscapeIntoRelayCode() {
        val diagnostics = RelayDiagnostics(
            capacity = 4,
            utcMillis = { throw IllegalStateException("wall clock unavailable") },
            monotonicNanos = { throw IllegalStateException("monotonic clock unavailable") },
            attemptIdFactory = { throw IllegalStateException("id unavailable") },
            logger = { throw IllegalStateException("log sink unavailable") },
        )

        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        attempt.recordSuccess(RelayDiagnosticPhase.Open)

        assertEquals(2, diagnostics.snapshot().size)
        assertEquals("invalid", diagnostics.snapshot().first().attemptId)
        assertEquals("unknown", diagnostics.snapshot().first().utc)
        assertEquals(0L, diagnostics.snapshot().last().monotonicDurationMillis)
    }

    @Test
    fun concurrentWritersNeverExceedRingCapacity() {
        val diagnostics = RelayDiagnostics(
            capacity = 32,
            attemptIdFactory = { Thread.currentThread().name.take(12) },
            logger = {},
        )
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val done = CountDownLatch(4)
        repeat(4) {
            executor.execute {
                ready.countDown()
                ready.await()
                repeat(100) {
                    diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
                }
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertTrue(diagnostics.snapshot().size <= 32)
    }

    @Test
    fun openStagesAreOrderedBoundedAndIgnoreLateWorkerEvents() {
        val diagnostics = RelayDiagnostics(logger = {})
        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        RelayDiagnosticOpenStage.entries.forEach { stage ->
            repeat(3) { attempt.recordOpenStageStarted(stage) }
            repeat(3) { attempt.recordOpenStageSucceeded(stage) }
        }
        val stages = diagnostics.snapshot().drop(1)
        assertEquals(8, stages.size)
        assertEquals(RelayDiagnosticOpenStage.entries.flatMap { listOf(it, it) }, stages.map { it.openStage })
        assertEquals(List(4) { listOf(RelayDiagnosticStatus.Started, RelayDiagnosticStatus.Succeeded) }.flatten(), stages.map { it.status })
        attempt.recordTimeout(RelayDiagnosticPhase.Open)
        attempt.recordOpenStageStarted(RelayDiagnosticOpenStage.Dns)
        attempt.recordOpenStageSucceeded(RelayDiagnosticOpenStage.Upgrade)
        assertEquals(10, diagnostics.snapshot().size)
        assertEquals(RelayDiagnosticOpenStage.Upgrade, diagnostics.snapshot().last().openStage)
        assertEquals(RelayDiagnosticExceptionCategory.Timeout, diagnostics.snapshot().last().exceptionCategory)
    }

    @Test
    fun openFailureCategoriesNeverExposeExceptionDetails() {
        val secret = "https://private.example:443/path?credential=secret payload address"
        val cases = listOf(
            java.net.SocketTimeoutException(secret) to RelayDiagnosticExceptionCategory.Timeout,
            javax.net.ssl.SSLHandshakeException(secret) to RelayDiagnosticExceptionCategory.Tls,
            java.net.UnknownHostException(secret) to RelayDiagnosticExceptionCategory.Dns,
            java.net.ConnectException(secret) to RelayDiagnosticExceptionCategory.Connection,
            java.net.NoRouteToHostException(secret) to RelayDiagnosticExceptionCategory.Connection,
            java.io.EOFException(secret) to RelayDiagnosticExceptionCategory.Connection,
            IllegalStateException(secret) to RelayDiagnosticExceptionCategory.Unknown,
            RelayConnectionException(RelayConnectionFailure.ProtocolViolation) to RelayDiagnosticExceptionCategory.Protocol,
            RelayConnectionException(RelayConnectionFailure.NotAuthorized) to RelayDiagnosticExceptionCategory.Authorization,
            javax.net.ssl.SSLException(secret, java.net.SocketTimeoutException(secret)) to RelayDiagnosticExceptionCategory.Timeout,
        )
        cases.forEach { (error, category) ->
            val diagnostics = RelayDiagnostics(logger = {})
            val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Pairing)
            attempt.recordOpenStageStarted(RelayDiagnosticOpenStage.Dns)
            attempt.recordOpenFailure(error)
            val event = diagnostics.snapshot().last()
            assertEquals(category, event.exceptionCategory)
            assertEquals(RelayDiagnosticOpenStage.Dns, event.openStage)
            assertEquals(if (category == RelayDiagnosticExceptionCategory.Timeout) RelayDiagnosticStatus.TimedOut else RelayDiagnosticStatus.Failed, event.status)
            val line = event.toSafeLogLine()
            assertTrue(line.contains("open_stage=dns"))
            assertFalse(line.contains(secret))
            assertFalse(line.contains(error.javaClass.name))
            assertFalse(line.contains("private.example"))
            assertTrue(line.length < 512)
        }
    }

    @Test
    fun cancellationKeepsActiveStageAndSuppressesLaterFailure() {
        val diagnostics = RelayDiagnostics(logger = {})
        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        attempt.recordOpenStageStarted(RelayDiagnosticOpenStage.Dns)
        attempt.recordCancellation()
        attempt.recordOpenFailure(java.net.SocketException("private"))
        attempt.recordOpenStageSucceeded(RelayDiagnosticOpenStage.Dns)
        val event = diagnostics.snapshot().last()
        assertEquals(3, diagnostics.snapshot().size)
        assertEquals(RelayDiagnosticStatus.Cancelled, event.status)
        assertEquals(RelayDiagnosticOpenStage.Dns, event.openStage)
        assertEquals(RelayDiagnosticExceptionCategory.Cancellation, event.exceptionCategory)
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
