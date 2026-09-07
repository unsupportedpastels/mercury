package com.unsupportedpastels.mercury.core.profiles

/**
 * Pure profile-name bounds shared by the native clients.
 *
 * Hermes exposes the same catalog through two official transports with
 * intentionally different legacy decoder behavior. REST trims names and
 * accepts any nonblank value up to 64 UTF-16 code units, while the gateway
 * JSON-RPC adapter admits only the existing lowercase ASCII profile grammar.
 * Keep the modes explicit so one transport cannot silently tighten or loosen
 * the other.
 */
object ProfileCatalogPolicy {
    const val maxProfileCharacters = 64
    const val maxRestProfiles = 32
    const val maxRpcProfiles = 64

    /** REST `/api/profiles` semantics: trim, bound, deduplicate, then cap. */
    fun sanitizeRestNames(names: List<String>): List<String> =
        names.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= maxProfileCharacters }
            .distinct()
            .take(maxRestProfiles)
            .toList()

    /** `profiles.list` JSON-RPC semantics: exact lowercase ASCII grammar. */
    fun sanitizeRpcNames(names: List<String>): List<String> =
        names.asSequence()
            .filter(::isValidRpcName)
            .distinct()
            .take(maxRpcProfiles)
            .toList()

    fun isValidRpcName(name: String): Boolean {
        if (name.length !in 1..maxProfileCharacters) return false
        if (!isAsciiLowerOrDigit(name.first())) return false
        return name.drop(1).all { isAsciiLowerOrDigit(it) || it == '_' || it == '-' }
    }

    private fun isAsciiLowerOrDigit(value: Char): Boolean =
        value in 'a'..'z' || value in '0'..'9'
}
