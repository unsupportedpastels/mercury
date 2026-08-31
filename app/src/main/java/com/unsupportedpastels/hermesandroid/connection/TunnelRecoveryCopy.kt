package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure

const val DEFAULT_TUNNEL_ORIGIN = "http://127.0.0.1:9119"

const val CREDENTIAL_REJECTED_USER_MESSAGE =
    "Hermes tunnel authorization failed after refreshing the session. Verify that the tunnel " +
        "points to the expected Hermes instance, then retry."

const val BOOTSTRAP_REJECTED_USER_MESSAGE =
    "Hermes tunnel authorization bootstrap was rejected. Verify that the tunnel points to the " +
        "expected Hermes instance, then retry."

const val GATED_TUNNEL_TARGET_MESSAGE =
    "Hermes tunnel target requires OAuth authorization"

const val LOOPBACK_SECURITY_WARNING =
    "Any other app on this device can reach the forwarded port and obtain the same session token. " +
        "Android loopback is not exclusive to this app."

const val EXPERIMENTAL_FEATURE_LABEL = "Experimental"

const val EXPERIMENTAL_FEATURE_REASONS =
    "External SSH tunnel is Experimental because HAM scrapes a session token from the dashboard " +
        "HTML rather than a published API, depends on a third-party SSH app's lifecycle, uses a " +
        "shared device-wide loopback, and has no capability flag to negotiate against."

const val TUNNEL_UNAVAILABLE_TITLE = "SSH tunnel unavailable"

const val TUNNEL_UNAVAILABLE_BODY =
    "This app cannot start the tunnel. Start or reconnect the local port forward in Termius or " +
        "your SSH app, then retry. Retry reconnects this app only."

enum class TunnelRecoveryAction {
    Retry,
    ConnectionSetup,
    Cancel,
    AcceptNewServer,
}

data class TunnelRecoveryCopy(
    val failure: TunnelConnectionFailure,
    val title: String,
    val body: String,
    val actions: List<TunnelRecoveryAction>,
)

fun tunnelRecoveryCopy(
    failure: TunnelConnectionFailure?,
    connectionState: ConnectionState,
    connectionError: String?,
): TunnelRecoveryCopy? {
    if (failure == null) return null
    // Classified failures, including InstallationChanged, win over ConnectionState
    // so Recovering + InstallationChanged still shows accept/cancel.
    return when (failure) {
        TunnelConnectionFailure.TunnelUnavailable -> TunnelRecoveryCopy(
            failure = failure,
            title = TUNNEL_UNAVAILABLE_TITLE,
            body = TUNNEL_UNAVAILABLE_BODY,
            actions = listOf(TunnelRecoveryAction.Retry),
        )
        TunnelConnectionFailure.CredentialRejected -> TunnelRecoveryCopy(
            failure = failure,
            title = "Authorization failed",
            body = CREDENTIAL_REJECTED_USER_MESSAGE,
            actions = listOf(
                TunnelRecoveryAction.Retry,
                TunnelRecoveryAction.ConnectionSetup,
                TunnelRecoveryAction.Cancel,
            ),
        )
        TunnelConnectionFailure.NotHermesEndpoint -> TunnelRecoveryCopy(
            failure = failure,
            title = "Wrong service on this port",
            body = NOT_HERMES_ENDPOINT_MESSAGE,
            actions = listOf(TunnelRecoveryAction.Retry, TunnelRecoveryAction.ConnectionSetup),
        )
        TunnelConnectionFailure.ProtocolIncompatible -> TunnelRecoveryCopy(
            failure = failure,
            title = "Protocol incompatible",
            body = PROTOCOL_INCOMPATIBLE_MESSAGE,
            actions = listOf(TunnelRecoveryAction.ConnectionSetup),
        )
        TunnelConnectionFailure.InstallationChanged -> TunnelRecoveryCopy(
            failure = failure,
            title = "Hermes installation changed",
            body = INSTALLATION_CHANGED_MESSAGE,
            actions = listOf(TunnelRecoveryAction.AcceptNewServer, TunnelRecoveryAction.Cancel),
        )
        TunnelConnectionFailure.InvalidTunnelOrigin -> TunnelRecoveryCopy(
            failure = failure,
            title = "Invalid tunnel origin",
            body = connectionError ?: TUNNEL_ORIGIN_MESSAGE,
            actions = listOf(TunnelRecoveryAction.ConnectionSetup),
        )
        TunnelConnectionFailure.CleartextPolicyBlocked -> TunnelRecoveryCopy(
            failure = failure,
            title = "Cleartext blocked",
            body = connectionError ?: CLEARTEXT_POLICY_MESSAGE,
            actions = listOf(TunnelRecoveryAction.ConnectionSetup),
        )
        TunnelConnectionFailure.BootstrapRejected -> if (isGatedTunnelTarget(connectionError)) {
            TunnelRecoveryCopy(
                failure = failure,
                title = "Gated Hermes server",
                body = "This tunnel points at a gated Hermes server that requires the advertised " +
                    "OAuth sign-in path. Use Hermes Cloud or Server URL instead of External SSH tunnel.",
                actions = listOf(TunnelRecoveryAction.ConnectionSetup),
            )
        } else {
            TunnelRecoveryCopy(
                failure = failure,
                title = "Bootstrap unavailable",
                body = BOOTSTRAP_REJECTED_USER_MESSAGE,
                actions = listOf(TunnelRecoveryAction.Retry, TunnelRecoveryAction.ConnectionSetup),
            )
        }
    }
}

fun isGatedTunnelTarget(connectionError: String?): Boolean =
    connectionError?.contains("OAuth", ignoreCase = true) == true

sealed interface TunnelTestResult {
    data object Success : TunnelTestResult

    data class Failure(
        val failure: TunnelConnectionFailure,
        val message: String,
    ) : TunnelTestResult
}