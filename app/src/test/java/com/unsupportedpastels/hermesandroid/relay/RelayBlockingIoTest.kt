package com.unsupportedpastels.hermesandroid.relay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayBlockingIoTest {
    @Test fun candidateIsClosedWhenCancellationWinsTheDispatcherHandoff() = runTest {
        val resumeQueued = CountDownLatch(1)
        val allowResult = CountDownLatch(1)
        val candidateCloses = AtomicInteger()
        var delivered = false
        val job = launch {
            runRelayBlockingIo(
                onCancellation = {},
                onResultCancellation = { _: Any -> candidateCloses.incrementAndGet() },
                onSuccessfulResume = { resumeQueued.countDown() },
            ) { check(allowResult.await(5, TimeUnit.SECONDS)); Any() }
            delivered = true
        }
        runCurrent()
        allowResult.countDown()
        assertTrue("IO worker did not enqueue result", resumeQueued.await(5, TimeUnit.SECONDS))
        // The caller's test dispatcher has not resumed after the IO worker.
        job.cancel()
        runCurrent()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(false, delivered)
        assertEquals(1, candidateCloses.get())
    }

    @Test fun resourcesCreatedAfterCancellationAreImmediatelyClosed() {
        val registry = RelayCloseRegistry()
        val closes = AtomicInteger()
        registry.close()
        registry.register { closes.incrementAndGet() }
        registry.close()
        assertEquals(1, closes.get())
    }

    @Test fun acceptedHandoffDetachesResourcesFromConnectCleanup() {
        val registry = RelayCloseRegistry()
        val closes = AtomicInteger()
        registry.register { closes.incrementAndGet() }
        registry.detach()
        registry.close()
        assertEquals(0, closes.get())
    }
}
