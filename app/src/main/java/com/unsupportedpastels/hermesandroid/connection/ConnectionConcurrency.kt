package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal suspend fun <Probe, SavedToken : Any> probeAndLoadSavedTokenConcurrently(
    probe: suspend () -> Probe,
    loadSavedToken: suspend () -> SavedToken?,
    needsSavedToken: (Probe) -> Boolean,
): Pair<Probe, SavedToken?> = supervisorScope {
    val savedToken = async { loadSavedToken() }
    try {
        val probeResult = probe()
        if (needsSavedToken(probeResult)) {
            probeResult to savedToken.await()
        } else {
            savedToken.cancel()
            probeResult to null
        }
    } catch (cancelled: CancellationException) {
        savedToken.cancel()
        throw cancelled
    } catch (error: Throwable) {
        savedToken.cancel()
        throw error
    }
}

internal suspend fun <Authentication, Metadata> authenticateAndPrefetchConcurrently(
    authenticate: suspend () -> Authentication,
    prefetchMetadata: suspend () -> Metadata,
    discardMetadata: suspend (Metadata) -> Unit,
): Pair<Authentication, Result<Metadata>> = supervisorScope {
    val authentication = async { authenticate() }
    val metadata = async { prefetchMetadata() }
    try {
        val authenticated = authentication.await()
        val prefetched = try {
            Result.success(metadata.await())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        authenticated to prefetched
    } catch (error: Throwable) {
        metadata.cancel()
        val completedMetadata = withContext(NonCancellable) {
            runCatching { metadata.await() }.getOrNull()
        }
        completedMetadata?.let { runCatching { discardMetadata(it) } }
        throw error
    }
}
