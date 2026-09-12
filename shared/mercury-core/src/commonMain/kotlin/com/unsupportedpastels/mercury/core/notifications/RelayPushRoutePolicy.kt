package com.unsupportedpastels.mercury.core.notifications

/** Resolved durable destination, not a live runtime ID or a navigation command. */
data class RelayPushSessionRoute(val durableSessionId: String, val profile: String)

/**
 * Shared authority for Relay push v1/v2 advertisement and `relay.push.resolve`.
 * Plain wire maps accept actual booleans and strings only. Native adapters bridge
 * their JSON value types; they do not trim, canonicalize or validate route fields.
 * Missing, unresolved and malformed responses all retain the existing Home fallback.
 * Preserve the preview branch's existing route bounds through its shared contract.
 */
object RelayPushRoutePolicy {
    const val MAX_SESSION_UTF8_BYTES = PushPreviewContract.MAX_ROUTE_SESSION_UTF8_BYTES
    const val MAX_PROFILE_UTF8_BYTES = PushPreviewContract.MAX_ROUTE_PROFILE_UTF8_BYTES

    fun supports(status: Map<*, *>?): Boolean = capability(status, "push_notifications_v1")

    fun supportsSessionResolution(status: Map<*, *>?): Boolean = capability(status, "push_notifications_v2")

    private fun capability(status: Map<*, *>?, key: String): Boolean =
        ((status?.get("capabilities") as? Map<*, *>)?.get(key) as? Boolean) == true

    fun resolvedSessionRoute(result: Map<*, *>?): RelayPushSessionRoute? {
        if (result?.get("resolved") as? Boolean != true) return null
        val session = result["durable_session_id"] as? String ?: return null
        val profile = result["profile"] as? String ?: return null
        if (!validField(session, MAX_SESSION_UTF8_BYTES) || !validField(profile, MAX_PROFILE_UTF8_BYTES)) return null
        return RelayPushSessionRoute(session, profile)
    }

    private fun validField(value: String, maxBytes: Int): Boolean {
        if (value.encodeToByteArray().size !in 1..maxBytes) return false
        var index = 0
        while (index < value.length) {
            val first = value[index++].code
            val scalar = if (first in 0xd800..0xdbff && index < value.length && value[index].code in 0xdc00..0xdfff) {
                0x10000 + ((first - 0xd800) shl 10) + (value[index++].code - 0xdc00)
            } else first
            if (legacyControlScalar(scalar)) return false
        }
        return true
    }

    // Frozen from the existing Foundation CharacterSet.controlCharacters contract,
    // characterized through the production Swift adapter for every Unicode scalar.
    // Do not substitute Char.isISOControl or platform Unicode categories: those
    // omit format characters, differ by Unicode version, and lose Foundation's
    // plane-14 low-byte membership behavior. Changing that behavior is not this move.
    private fun legacyControlScalar(value: Int): Boolean = when (value) {
        in 0x0000..0x001f, in 0x007f..0x009f, 0x00ad,
        in 0x0600..0x0605, 0x061c, 0x06dd, 0x070f, in 0x0890..0x0891, 0x08e2,
        0x180e, in 0x200b..0x200f, in 0x202a..0x202e, in 0x2060..0x2064,
        in 0x2066..0x206f, 0xfeff, in 0xfff9..0xfffb,
        0x110bd, 0x110cd, in 0x13430..0x1343f, in 0x1bca0..0x1bca3,
        in 0x1d173..0x1d17a -> true
        in 0xe0000..0xeffff -> (value and 0xff) == 1 || (value and 0xff) in 0x20..0x7f
        else -> false
    }
}
