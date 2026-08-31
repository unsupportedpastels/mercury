package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val MINIMUM_HERMES_VERSION = "0.20.4"

const val NOT_HERMES_ENDPOINT_MESSAGE =
    "The local port is occupied by a different service. Choose another local port."

const val PROTOCOL_INCOMPATIBLE_MESSAGE =
    "This Hermes version is not supported. HAM requires $MINIMUM_HERMES_VERSION or newer, " +
        "with no speculative fallback."

const val INSTALLATION_CHANGED_MESSAGE =
    "This local port now appears to lead to a different Hermes installation."

data class HermesStatusSnapshot(
    val version: String,
    val releaseDate: String?,
    val authRequired: Boolean,
    val authFlows: List<String>,
    val installId: String?,
)

sealed interface HermesStatusProbe {
    data class Accepted(val status: HermesStatusSnapshot) : HermesStatusProbe

    data class Rejected(
        val failure: TunnelConnectionFailure,
        val message: String,
    ) : HermesStatusProbe
}

sealed interface InstallationContinuity {
    data object Unchanged : InstallationContinuity

    data class Changed(
        val previousInstallId: String,
        val observedInstallId: String,
    ) : InstallationContinuity
}

fun evaluateInstallationContinuity(
    lastSeenInstallId: String?,
    observedInstallId: String?,
): InstallationContinuity {
    if (observedInstallId.isNullOrBlank() || lastSeenInstallId.isNullOrBlank()) {
        return InstallationContinuity.Unchanged
    }
    if (lastSeenInstallId == observedInstallId) return InstallationContinuity.Unchanged
    return InstallationContinuity.Changed(lastSeenInstallId, observedInstallId)
}

fun parseHermesStatusBody(body: String): HermesStatusProbe {
    val root = runCatching {
        Json.parseToJsonElement(body).jsonObject
    }.getOrNull() ?: return notHermes()
    val authRequired = runCatching {
        root["auth_required"]?.jsonPrimitive?.booleanOrNull
    }.getOrNull() ?: return notHermes()
    val version = runCatching {
        root["version"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }.getOrNull().orEmpty()
    if (!isSupportedHermesVersion(version)) {
        return HermesStatusProbe.Rejected(
            TunnelConnectionFailure.ProtocolIncompatible,
            PROTOCOL_INCOMPATIBLE_MESSAGE,
        )
    }
    val authFlows = root["auth_flows"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull()
    } ?: emptyList()
    val installId = runCatching {
        root["install_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_INSTALL_ID_CHARS)
    }.getOrNull()
    val releaseDate = runCatching {
        root["release_date"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
    return HermesStatusProbe.Accepted(
        HermesStatusSnapshot(
            version = version,
            releaseDate = releaseDate,
            authRequired = authRequired,
            authFlows = authFlows,
            installId = installId,
        ),
    )
}

fun isSupportedHermesVersion(version: String): Boolean {
    val parsed = parseNumericVersion(version) ?: return false
    val minimum = parseNumericVersion(MINIMUM_HERMES_VERSION) ?: return false
    return compareNumericVersions(parsed, minimum) >= 0
}

private fun notHermes(): HermesStatusProbe.Rejected = HermesStatusProbe.Rejected(
    TunnelConnectionFailure.NotHermesEndpoint,
    NOT_HERMES_ENDPOINT_MESSAGE,
)

private fun parseNumericVersion(version: String): List<Int>? {
    if (!version.matches(Regex("""\d+\.\d+\.\d+"""))) return null
    return version.split('.').map { it.toInt() }
}

private fun compareNumericVersions(left: List<Int>, right: List<Int>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val delta = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
        if (delta != 0) return delta
    }
    return 0
}

class HermesEndpointException(
    val failure: TunnelConnectionFailure,
    message: String,
) : HermesConnectionException(message)

internal fun HermesStatusProbe.requireAccepted(): HermesStatusSnapshot = when (this) {
    is HermesStatusProbe.Accepted -> status
    is HermesStatusProbe.Rejected -> throw HermesEndpointException(failure, message)
}
