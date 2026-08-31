package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import java.net.URI
import java.util.Locale

const val LOCALHOST_TUNNEL_MESSAGE =
    "localhost is ambiguous between IPv4 and IPv6 on Android. Use 127.0.0.1."

const val TUNNEL_ORIGIN_MESSAGE =
    "External SSH tunnel connections require 127.0.0.1 or [::1]."

const val CLEARTEXT_POLICY_MESSAGE =
    "Non-loopback origins require HTTPS. Cleartext HTTP is allowed only for 127.0.0.1 or [::1] " +
        "in External SSH tunnel mode."

sealed interface OriginTransportDecision {
    data object Allowed : OriginTransportDecision

    data class Rejected(
        val failure: TunnelConnectionFailure,
        val message: String,
    ) : OriginTransportDecision
}

fun ServerOrigin.scheme(): String = URI(value).scheme.lowercase(Locale.ROOT)

fun ServerOrigin.hostName(): String = URI(value).host.removeSurrounding("[", "]")

fun ServerOrigin.isLocalhostName(): Boolean = hostName() == "localhost"

fun evaluateOriginTransport(
    origin: ServerOrigin,
    mode: ServerConnectionMode,
): OriginTransportDecision {
    if (mode == ServerConnectionMode.ExternalSshTunnel) {
        if (origin.isLocalhostName()) {
            return OriginTransportDecision.Rejected(
                TunnelConnectionFailure.InvalidTunnelOrigin,
                LOCALHOST_TUNNEL_MESSAGE,
            )
        }
        if (!origin.isLoopback) {
            return OriginTransportDecision.Rejected(
                TunnelConnectionFailure.InvalidTunnelOrigin,
                TUNNEL_ORIGIN_MESSAGE,
            )
        }
    }
    if (origin.scheme() == "http") {
        val tunnelLoopback =
            mode == ServerConnectionMode.ExternalSshTunnel && origin.isLoopback
        if (!tunnelLoopback) {
            return OriginTransportDecision.Rejected(
                TunnelConnectionFailure.CleartextPolicyBlocked,
                CLEARTEXT_POLICY_MESSAGE,
            )
        }
    }
    return OriginTransportDecision.Allowed
}
