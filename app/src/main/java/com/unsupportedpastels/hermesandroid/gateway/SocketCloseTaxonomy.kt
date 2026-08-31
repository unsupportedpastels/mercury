package com.unsupportedpastels.hermesandroid.gateway

/**
 * The WebSocket close codes a released `hermes serve` uses that HAM must act on
 * (answers doc §21). Application codes live in the 4000-4999 private range; the
 * server also uses the standard `1011` for internal errors.
 */
object HermesSocketCloseCodes {
    /** The credential presented on the upgrade was rejected. */
    const val CREDENTIAL_REJECTED: Int = 4401

    /** Host/Origin/request policy, or a feature boundary for this socket. */
    const val POLICY_REJECTED: Int = 4403

    /** The feature backing this socket is disabled or unavailable. */
    const val FEATURE_UNAVAILABLE: Int = 4404

    /** Peer/client boundary used by some dashboard sockets. */
    const val PEER_BOUNDARY: Int = 4408

    /** Server-side internal error. */
    const val SERVER_ERROR: Int = 1011
}

/**
 * What a socket close means for the credential that opened it. This is the
 * socket-side counterpart of the REST rejection taxonomy: exactly one class may
 * invalidate a credential or trigger a rebootstrap, and every other class is as
 * inert for the credential as HTTP 403 is for a REST read.
 *
 * Both socket transports (chat gateway and streaming speech) share this
 * classifier so the two cannot drift apart, and so socket recovery joins the
 * one REST recovery epoch rather than running a competing state machine.
 */
enum class SocketCloseClass {
    /** `4401` — the credential itself is no longer accepted. */
    CredentialRejected,

    /** `4403` — authenticated but refused by policy or a feature boundary. */
    PolicyRejected,

    /** `4404` — the feature is disabled or not available on this server. */
    FeatureUnavailable,

    /** `4408` — this peer/client is outside the socket's supported boundary. */
    PeerBoundary,

    /** `1011` — the server failed internally; the credential is untouched. */
    ServerError,

    /** Any other code, and a close that carried no code at all. */
    TransportFailure,
}

/**
 * Maps a close code to its actionable class. An absent code (an abrupt drop, or
 * a transport that never delivered a close frame) is a transport failure, never
 * a credential rejection — the same rule that keeps a dead tunnel out of the
 * REST credential taxonomy.
 */
fun classifySocketClose(code: Int?): SocketCloseClass = when (code) {
    HermesSocketCloseCodes.CREDENTIAL_REJECTED -> SocketCloseClass.CredentialRejected
    HermesSocketCloseCodes.POLICY_REJECTED -> SocketCloseClass.PolicyRejected
    HermesSocketCloseCodes.FEATURE_UNAVAILABLE -> SocketCloseClass.FeatureUnavailable
    HermesSocketCloseCodes.PEER_BOUNDARY -> SocketCloseClass.PeerBoundary
    HermesSocketCloseCodes.SERVER_ERROR -> SocketCloseClass.ServerError
    else -> SocketCloseClass.TransportFailure
}

/**
 * True only for a definitive credential rejection. Every caller that is about
 * to invalidate a credential, rebootstrap, or reconnect for authorization
 * reasons must gate on this rather than on "the socket closed".
 */
val SocketCloseClass.allowsCredentialRecovery: Boolean
    get() = this == SocketCloseClass.CredentialRejected
