package com.unsupportedpastels.hermesandroid.connection

/** Maximum number of server origins retained in the local catalog. */
const val MAX_SERVER_CATALOG_ENTRIES = 8

/** Maximum number of user-controlled display-label characters retained locally. */
const val MAX_SERVER_LABEL_CHARS = 80

/** Maximum number of non-secret installation-identifier characters retained locally. */
const val MAX_INSTALL_ID_CHARS = 128

enum class ServerConnectionMode {
    Direct,
    ExternalSshTunnel,
}

/**
 * Local metadata for one server origin.
 *
 * This type intentionally contains only normalized origin identity and local UI metadata. It must
 * never grow credentials, tokens, transcript content, or assumptions about the remote host.
 */
data class ServerCatalogEntry(
    val origin: ServerOrigin,
    val label: String = "",
    val lastUsedEpochSeconds: Long? = null,
    val connectionMode: ServerConnectionMode = ServerConnectionMode.Direct,
    val lastSeenInstallId: String? = null,
) {
    init {
        require(label.length <= MAX_SERVER_LABEL_CHARS) { "Server label is too long" }
        require(label.none(Char::isISOControl)) { "Server label contains a control character" }
        require(lastUsedEpochSeconds == null || lastUsedEpochSeconds >= 0L) {
            "Server last-used time is invalid"
        }
        require(lastSeenInstallId == null || lastSeenInstallId.none(Char::isISOControl)) {
            "Server installation identifier contains a control character"
        }
        require(lastSeenInstallId == null || lastSeenInstallId.length <= MAX_INSTALL_ID_CHARS) {
            "Server installation identifier is too long"
        }
    }

    val displayLabel: String
        get() = label.ifBlank { origin.value }

    fun normalized(): ServerCatalogEntry = copy(label = label.trim())
}

/** Bounded, deduplicated local server catalog plus the selected active origin. */
data class ServerCatalog(
    val entries: List<ServerCatalogEntry>,
    val activeOrigin: ServerOrigin?,
) {
    init {
        require(entries.size <= MAX_SERVER_CATALOG_ENTRIES) { "Server catalog is too large" }
        require(entries.map(ServerCatalogEntry::origin).distinct().size == entries.size) {
            "Server catalog contains duplicate origins"
        }
        require(activeOrigin == null || entries.any { it.origin == activeOrigin }) {
            "Active server is not in the catalog"
        }
    }

    val activeEntry: ServerCatalogEntry?
        get() = entries.firstOrNull { it.origin == activeOrigin }

    companion object {
        fun empty(): ServerCatalog = ServerCatalog(emptyList(), null)

        fun single(entry: ServerCatalogEntry): ServerCatalog =
            ServerCatalog(listOf(entry.normalized()), entry.origin)

        /**
         * Normalizes entries from local persistence. Later duplicate rows replace earlier metadata,
         * while the original insertion order is retained. If the stored active origin is missing,
         * the first retained origin becomes active rather than activating an unknown server.
         */
        fun normalized(
            entries: Iterable<ServerCatalogEntry>,
            activeOrigin: ServerOrigin?,
        ): ServerCatalog {
            val deduplicated = linkedMapOf<ServerOrigin, ServerCatalogEntry>()
            entries.forEach { entry ->
                deduplicated[entry.origin] = entry.normalized()
            }
            val bounded = deduplicated.values.toMutableList()
            val resolvedActive = activeOrigin?.takeIf { origin ->
                bounded.any { it.origin == origin }
            } ?: bounded.firstOrNull()?.origin
            while (bounded.size > MAX_SERVER_CATALOG_ENTRIES) {
                val removable = bounded
                    .withIndex()
                    .filter { (_, entry) -> entry.origin != resolvedActive }
                    .minWithOrNull(
                        compareBy<IndexedValue<ServerCatalogEntry>>(
                            { it.value.lastUsedEpochSeconds ?: Long.MIN_VALUE },
                            { it.index },
                        ),
                    )
                    ?: IndexedValue(bounded.lastIndex, bounded.last())
                bounded.removeAt(removable.index)
            }
            val finalActive = resolvedActive?.takeIf { origin ->
                bounded.any { it.origin == origin }
            } ?: bounded.firstOrNull()?.origin
            return ServerCatalog(bounded, finalActive)
        }
    }
}
