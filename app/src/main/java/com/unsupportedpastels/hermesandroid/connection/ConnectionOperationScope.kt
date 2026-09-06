package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Identity captured before suspension; contains no credential or transient ticket. */
internal data class ConnectionOperationScope(
    val origin: ServerOrigin,
    val relayTargetId: String?,
    val profile: String,
    val generation: Long,
    val profileGeneration: Long,
)

/** Shared native publication guard for connection/profile-bound operations. */
internal class ConnectionOperationGuard(private val current: () -> ConnectionOperationScope?) {
    fun currentScope(): ConnectionOperationScope? = current()
    fun isCurrent(scope: ConnectionOperationScope): Boolean = current() == scope

    suspend fun ensureCurrent(scope: ConnectionOperationScope) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(scope)) throw CancellationException("Connection or profile was replaced")
    }

    suspend fun <T> run(scope: ConnectionOperationScope, block: suspend () -> T): T {
        ensureCurrent(scope)
        val result = block()
        ensureCurrent(scope)
        return result
    }
}
