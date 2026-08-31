package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.HermesConnectionClient
import com.unsupportedpastels.hermesandroid.connection.toHermesCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Authenticated transport seam kept separate so repository tests never need a server. */
interface HostFilesTransport {
    suspend fun list(scope: HostFileScope, path: String?): HostFileListing
    suspend fun read(scope: HostFileScope, entry: HostFileEntry): HostFileContent
    suspend fun download(scope: HostFileScope, entry: HostFileEntry): HostFileContent
}

/** Bridges the official REST client to the repository without persisting credentials or bytes. */
class HermesConnectionHostFilesTransport(
    private val client: HermesConnectionClient,
    private val accessTokenProvider: suspend (HostFileScope) -> String?,
) : HostFilesTransport {
    override suspend fun list(scope: HostFileScope, path: String?): HostFileListing =
        client.loadHostFiles(scope.origin, accessTokenProvider(scope).toHermesCredential(), path)

    override suspend fun read(scope: HostFileScope, entry: HostFileEntry): HostFileContent =
        client.readManagedFile(scope.origin, accessTokenProvider(scope).toHermesCredential(), entry.path)

    override suspend fun download(scope: HostFileScope, entry: HostFileEntry): HostFileContent =
        client.downloadManagedFile(scope.origin, accessTokenProvider(scope).toHermesCredential(), entry.path)
}

/**
 * Read-only, generation-guarded browser state. The server's canonical paths are
 * passed back unchanged; this class never joins or constructs a path.
 */
class HostFilesRepository(
    private val transport: HostFilesTransport,
    initialScope: HostFileScope,
) {
    private val mutableState = MutableStateFlow(HostFilesSnapshot(initialScope))
    val state: StateFlow<HostFilesSnapshot> = mutableState.asStateFlow()

    private var generation: Long = 0

    fun updateScope(scope: HostFileScope) {
        if (scope == mutableState.value.scope) return
        generation += 1
        mutableState.value = HostFilesSnapshot(scope)
    }

    suspend fun browse(path: String? = null): HostFileListing {
        val safePath = path?.let {
            validCanonicalHostFilePath(it)
                ?: throw IllegalArgumentException("Host-file path is invalid")
        }
        val requestGeneration = generation
        val requestScope = mutableState.value.scope
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        return try {
            val listing = transport.list(requestScope, safePath)
            ensureCurrent(requestScope, requestGeneration)
            mutableState.value = mutableState.value.copy(
                listing = listing,
                loading = false,
                error = null,
            )
            listing
        } catch (cancelled: CancellationException) {
            if (generation == requestGeneration && mutableState.value.scope == requestScope) {
                mutableState.value = mutableState.value.copy(loading = false)
            }
            throw cancelled
        } catch (error: Exception) {
            if (generation == requestGeneration && mutableState.value.scope == requestScope) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = error.message?.take(160)?.takeIf(String::isNotBlank)
                        ?: "Could not load host files",
                )
            }
            throw error
        }
    }

    suspend fun refresh(): HostFileListing = browse(mutableState.value.listing?.path)

    suspend fun up(): HostFileListing? {
        val parentPath = mutableState.value.listing?.parentPath ?: return null
        return browse(parentPath)
    }

    fun setFilter(value: String) {
        mutableState.value = mutableState.value.copy(filter = value.take(256))
    }

    fun filteredEntries(): List<HostFileEntry> {
        val query = mutableState.value.filter.trim()
        if (query.isEmpty()) return mutableState.value.listing?.entries.orEmpty()
        return mutableState.value.listing?.entries.orEmpty().filter { entry ->
            entry.name.contains(query, ignoreCase = true) || entry.path.contains(query, ignoreCase = true)
        }
    }

    suspend fun read(entry: HostFileEntry): HostFileContent {
        require(!entry.isDirectory) { "Cannot read a host folder as a file" }
        require(validCanonicalHostFilePath(entry.path) != null) { "Host-file path is invalid" }
        return transport.read(currentScope(), entry)
    }

    suspend fun download(entry: HostFileEntry): HostFileContent {
        require(!entry.isDirectory) { "Cannot download a host folder as a file" }
        require(validCanonicalHostFilePath(entry.path) != null) { "Host-file path is invalid" }
        return transport.download(currentScope(), entry)
    }

    private fun currentScope(): HostFileScope = mutableState.value.scope

    private fun ensureCurrent(scope: HostFileScope, requestGeneration: Long) {
        if (generation != requestGeneration || mutableState.value.scope != scope) {
            throw CancellationException("Host-file scope was replaced")
        }
    }
}
