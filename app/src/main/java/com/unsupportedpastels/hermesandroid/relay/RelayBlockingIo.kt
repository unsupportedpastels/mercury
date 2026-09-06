package com.unsupportedpastels.hermesandroid.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs one blocking native-socket operation on an IO worker and closes its
 * resource when the caller is cancelled. The resume callback also handles the
 * small handoff window between the worker producing a resource and the caller
 * receiving it.
 */
internal suspend fun <T> runRelayBlockingIo(
    onCancellation: () -> Unit,
    onResultCancellation: (T) -> Unit = { onCancellation() },
    onSuccessfulResume: (T) -> Unit = {},
    block: () -> T,
): T = suspendCancellableCoroutine { continuation ->
    val ioJob = CoroutineScope(continuation.context).launch(Dispatchers.IO) {
        try {
            val result = block()
            if (!continuation.isActive) {
                runCatching { onResultCancellation(result) }
                return@launch
            }
            try {
                continuation.resume(result) { _, value, _ ->
                    runCatching { onResultCancellation(value) }
                }
                onSuccessfulResume(result)
            } catch (error: Throwable) {
                runCatching { onResultCancellation(result) }
                if (continuation.isActive) {
                    runCatching { continuation.resumeWithException(error) }
                }
            }
        } catch (error: Throwable) {
            runCatching { onCancellation() }
            if (continuation.isActive) {
                runCatching { continuation.resumeWithException(error) }
            }
        }
    }
    continuation.invokeOnCancellation {
        runCatching { onCancellation() }
        ioJob.cancel()
    }
}

/** Tracks native resources created incrementally by a blocking connect. */
internal class RelayCloseRegistry {
    private val lock = Any()
    private val actions = ArrayList<() -> Unit>()
    private var cancelled = false

    fun register(action: () -> Unit) {
        val closeNow = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                actions += action
                false
            }
        }
        if (closeNow) runCatching { action() }
    }

    fun close() {
        val pending = synchronized(lock) {
            cancelled = true
            actions.toList().also { actions.clear() }
        }
        pending.forEach { runCatching { it() } }
    }

    fun detach() {
        synchronized(lock) { actions.clear() }
    }
}
