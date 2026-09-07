package com.unsupportedpastels.mercury.core.origin

/** Bounds and eviction for the local server picker, decided once for both clients. */
object ServerCatalogPolicy {
    const val MAX_ENTRIES = 8
    const val MAX_LABEL_CHARS = 80

    /**
     * Indices to keep (in original order) when a catalog exceeds [maxEntries].
     * The least recently used non-active entry goes first; a never-used entry
     * counts as oldest; ties evict the earliest index. The active entry is
     * never evicted while any other entry remains.
     */
    fun retainedIndices(
        lastUsedEpochSeconds: List<Long?>,
        activeIndex: Int?,
        maxEntries: Int = MAX_ENTRIES,
    ): List<Int> {
        val kept = lastUsedEpochSeconds.indices.toMutableList()
        while (kept.size > maxEntries) {
            val removable = kept.filter { it != activeIndex }
                .minWithOrNull(compareBy<Int>({ lastUsedEpochSeconds[it] ?: Long.MIN_VALUE }, { it }))
                ?: kept.last()
            kept.remove(removable)
        }
        return kept
    }
}
