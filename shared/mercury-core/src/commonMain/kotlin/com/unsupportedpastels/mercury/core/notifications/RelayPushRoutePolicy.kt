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
        if (!PushPreviewFieldPolicy.validSessionId(session) || !PushPreviewFieldPolicy.validProfile(profile)) return null
        return RelayPushSessionRoute(session, profile)
    }
}
