package com.unsupportedpastels.mercury.core.sessions

/** The only predicates the session lists understand, parsed out of the search box. */
data class SessionListFilterSpec(
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val archivedOnly: Boolean = false,
)

object SessionListFilterPolicy {
    const val MAX_QUERY_CHARS = 128

    private val predicatePattern = Regex("(?i)\\bis:(pinned|archived)\\b")
    private val pinnedPattern = Regex("(?i)\\bis:pinned\\b")
    private val archivedPattern = Regex("(?i)\\bis:archived\\b")
    private val whitespace = Regex("\\s+")

    /** Splits `is:pinned` / `is:archived` out of a search box value; the rest is the text query. */
    fun parse(value: String): SessionListFilterSpec {
        val bounded = value.trim().take(MAX_QUERY_CHARS)
        val query = predicatePattern.replace(bounded, " ").replace(whitespace, " ").trim().take(MAX_QUERY_CHARS)
        return SessionListFilterSpec(
            query = query,
            pinnedOnly = pinnedPattern.containsMatchIn(bounded),
            archivedOnly = archivedPattern.containsMatchIn(bounded),
        )
    }

    /** The canonical search box text for a spec: query first, then predicates. */
    fun format(spec: SessionListFilterSpec): String = buildList {
        spec.query.trim().takeIf(String::isNotEmpty)?.let(::add)
        if (spec.pinnedOnly) add("is:pinned")
        if (spec.archivedOnly) add("is:archived")
    }.joinToString(" ")
}
