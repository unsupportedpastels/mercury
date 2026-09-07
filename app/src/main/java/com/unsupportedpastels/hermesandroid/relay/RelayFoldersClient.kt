package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HermesChatMethodNotFoundException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.mercury.core.files.RelayFoldersContract
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class RelayFoldersUnsupportedException : HermesChatProtocolException(
    RelayFoldersContract.unsupportedMessage,
)

class RelayFoldersInvalidInputException : HermesChatProtocolException(
    "Choose an absolute server folder and a valid single folder name.",
)

class RelayFoldersInvalidResponseException : HermesChatProtocolException(
    "The Relay host returned an invalid folder listing.",
)

/**
 * Capability-gated Relay folder operations. The request callback must use the
 * already-admitted chat controller; this class never opens a second connection.
 */
class RelayFoldersClient {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(
        profile: String,
        path: String?,
        ensureCurrent: suspend () -> Unit = {},
        request: suspend (String, JsonObject) -> JsonObject,
    ): HostDirectoryListing = mutex.withLock {
        val params = parseParams(
            RelayFoldersContract.listParams(profile, path),
        )
        perform(
            method = RelayFoldersContract.listMethod,
            params = params,
            request = request,
            ensureCurrent = ensureCurrent,
        )
    }

    suspend fun create(
        profile: String,
        parentPath: String,
        name: String,
        ensureCurrent: suspend () -> Unit = {},
        request: suspend (String, JsonObject) -> JsonObject,
    ): HostDirectoryListing = mutex.withLock {
        val params = parseParams(
            RelayFoldersContract.createParams(profile, parentPath, name),
        )
        // This method is deliberately one-shot. The caller must not retry after
        // an ambiguous transport failure because the host may already have made
        // the directory.
        perform(
            method = RelayFoldersContract.createMethod,
            params = params,
            request = request,
            ensureCurrent = ensureCurrent,
        )
    }

    private suspend fun perform(
        method: String,
        params: JsonObject,
        request: suspend (String, JsonObject) -> JsonObject,
        ensureCurrent: suspend () -> Unit,
    ): HostDirectoryListing {
        ensureCurrent()
        val status = try {
            request("relay.status", buildJsonObject {})
        } catch (_: HermesChatMethodNotFoundException) {
            throw RelayFoldersUnsupportedException()
        }
        ensureCurrent()
        if (!RelayFoldersContract.supports(status.toString())) {
            throw RelayFoldersUnsupportedException()
        }

        ensureCurrent()
        val result = try {
            // Drain a dispatched RPC before releasing the shared reader permit;
            // cancellation is checked again after returning to the parent context.
            withContext(NonCancellable) { request(method, params) }
        } catch (_: HermesChatMethodNotFoundException) {
            throw RelayFoldersUnsupportedException()
        }
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val decoded = RelayFoldersContract.decodeListing(result.toString())
            ?: throw RelayFoldersInvalidResponseException()
        return HostDirectoryListing(
            path = decoded.path,
            directories = decoded.entries.map { HostDirectoryEntry(it.name, it.path) },
            parentPath = decoded.parentPath,
            root = decoded.root,
            lockedRoot = decoded.lockedRoot,
            canChangePath = decoded.canChangePath,
        )
    }

    private fun parseParams(value: String?): JsonObject =
        value?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: throw RelayFoldersInvalidInputException()
}
