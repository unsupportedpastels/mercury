package com.unsupportedpastels.mercury.core.relay

import com.unsupportedpastels.mercury.core.origin.OriginParseResult
import com.unsupportedpastels.mercury.core.origin.ServerOriginPolicy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object RelayProtocolPolicy {
    const val scheme = "mercury-relay"
    const val protocolMajor = 1
    const val installationIdBytes = 32
    const val hostPublicKeyBytes = 32
    const val capabilityBytes = 32
    const val deviceIdBytes = 16
    const val maxQrPayloadBytes = 1_024
    const val maxOriginCharacters = 256
    const val maxEnvelopeBytes = 512
    const val maxProfileCharacters = 128
    const val maxRoutingTokenCharacters = 1_024
    const val maxExpirySkewSeconds = 900L
    const val fingerprintHexCharacters = 16
}

enum class RelayProtocolFailure {
    MalformedPayload,
    UnsupportedScheme,
    UnsupportedVersion,
    MissingRelayOrigin,
    ExpiredOffer,
    InvalidEnvelope,
}

class RelayProtocolException(
    val failure: RelayProtocolFailure,
) : Exception("Mercury Relay protocol validation failed")

@OptIn(ExperimentalEncodingApi::class)
object RelayBase64 {
    fun standardEncode(data: ByteArray): String = Base64.Default.encode(data)

    fun decodeExact(text: String, count: Int): ByteArray? {
        if (text.length > 4 * ((count + 2) / 3) + 4) return null
        return runCatching { Base64.Default.decode(text) }
            .getOrNull()
            ?.takeIf { it.size == count }
    }

    fun urlSafeEncode(data: ByteArray): String = standardEncode(data)
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')

    fun urlSafeDecodeExact(text: String, count: Int): ByteArray? {
        if (text.any { it.code > 0x7f } || '=' in text || text.length != (4 * count + 2) / 3) return null
        var padded = text.replace('-', '+').replace('_', '/')
        while (padded.length % 4 != 0) padded += "="
        return decodeExact(padded, count)
    }
}

class RelayPairingPayload private constructor(
    val relayOrigin: String,
    val installationId: ByteArray,
    val hostPublicKey: ByteArray,
    val capability: ByteArray,
    val expiresAtEpochSeconds: Long,
    val pairingRoutingToken: String? = null,
) {
    val deviceSocketUrl: String
        get() = deviceSocketUrl(relayOrigin, installationId)
            ?: throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Throws(RelayProtocolException::class)
        fun parse(text: String, nowEpochSeconds: Long): RelayPairingPayload {
            if (text.encodeToByteArray().size > RelayProtocolPolicy.maxQrPayloadBytes) {
                throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            }
            val fields = runCatching { json.parseToJsonElement(text) as? JsonObject }
                .getOrNull()
                ?: throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            val scheme = fields.string("s")
                ?: throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            if (scheme != RelayProtocolPolicy.scheme) {
                throw RelayProtocolException(RelayProtocolFailure.UnsupportedScheme)
            }
            val version = (fields["v"] as? JsonPrimitive)?.longOrNull
                ?: throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            if (version != RelayProtocolPolicy.protocolMajor.toLong()) {
                throw RelayProtocolException(RelayProtocolFailure.UnsupportedVersion)
            }
            val origin = fields.string("o")
                ?: throw RelayProtocolException(RelayProtocolFailure.MissingRelayOrigin)
            if (origin.isEmpty()) throw RelayProtocolException(RelayProtocolFailure.MissingRelayOrigin)
            if (normalizeOrigin(origin) == null) {
                throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            }
            val installationId = fields.string("i")
                ?.let { RelayBase64.decodeExact(it, RelayProtocolPolicy.installationIdBytes) }
            val hostPublicKey = fields.string("k")
                ?.let { RelayBase64.decodeExact(it, RelayProtocolPolicy.hostPublicKeyBytes) }
            val capability = fields.string("c")
                ?.let { RelayBase64.decodeExact(it, RelayProtocolPolicy.capabilityBytes) }
            val pairingRoutingToken = fields.string("t")?.takeIf(::validRoutingToken)
            if (fields["t"] != null && pairingRoutingToken == null) {
                throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            }
            val expires = (fields["x"] as? JsonPrimitive)?.longOrNull
            if (installationId == null || hostPublicKey == null || capability == null || expires == null || expires <= 0) {
                throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            }
            if (expires > nowEpochSeconds + RelayProtocolPolicy.maxExpirySkewSeconds) {
                throw RelayProtocolException(RelayProtocolFailure.MalformedPayload)
            }
            if (expires <= nowEpochSeconds) {
                throw RelayProtocolException(RelayProtocolFailure.ExpiredOffer)
            }
            return RelayPairingPayload(
                origin,
                installationId,
                hostPublicKey,
                capability,
                expires,
                pairingRoutingToken,
            )
        }

        fun parseFailure(text: String, nowEpochSeconds: Long): RelayProtocolFailure? =
            try {
                parse(text, nowEpochSeconds)
                null
            } catch (error: RelayProtocolException) {
                error.failure
            }

        fun normalizeOrigin(origin: String): String? {
            if (origin.length > RelayProtocolPolicy.maxOriginCharacters || origin.endsWith('/')) return null
            val https = when {
                origin.startsWith("https://") -> origin
                origin.startsWith("wss://") -> "https://${origin.removePrefix("wss://")}"
                else -> return null
            }
            val parsed = ServerOriginPolicy.canonicalize(https)
            val canonical = (parsed as? OriginParseResult.Valid)?.origin ?: return null
            if (!canonical.startsWith("https://")) return null
            return "wss://${canonical.removePrefix("https://")}"
        }

        fun deviceSocketUrl(relayOrigin: String, installationId: ByteArray): String? {
            if (installationId.size != RelayProtocolPolicy.installationIdBytes) return null
            val origin = normalizeOrigin(relayOrigin) ?: return null
            return "$origin/v1/device/${RelayBase64.urlSafeEncode(installationId)}"
        }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull

        private fun validRoutingToken(value: String): Boolean =
            value.isNotEmpty() &&
                value.length <= RelayProtocolPolicy.maxRoutingTokenCharacters &&
                value.all { it.code in 0x21..0x7e }
    }
}

data class RelayPairingAcknowledgement(
    val deviceId: String,
    val relayRoutingToken: String?,
)

object RelayPairingAck {
    private val json = Json { ignoreUnknownKeys = false }

    @Throws(RelayProtocolException::class)
    fun parse(plaintext: ByteArray): String = parseEnvelope(plaintext).deviceId

    @Throws(RelayProtocolException::class)
    fun parseEnvelope(plaintext: ByteArray): RelayPairingAcknowledgement {
        if (plaintext.size > RelayProtocolPolicy.maxEnvelopeBytes) invalidEnvelope()
        val fields = runCatching { json.parseToJsonElement(plaintext.decodeToString()) as? JsonObject }
            .getOrNull()
            ?: invalidEnvelope()
        val allowedKeys = setOf("type", "device_id", "relay_token")
        if (fields.size !in 2..3 || fields.keys.any { it !in allowedKeys } ||
            fields.string("type") != "pairing.pending"
        ) invalidEnvelope()
        val deviceId = fields.string("device_id") ?: invalidEnvelope()
        if (RelayBase64.urlSafeDecodeExact(deviceId, RelayProtocolPolicy.deviceIdBytes) == null) invalidEnvelope()
        val relayRoutingToken = fields.string("relay_token")
        if (fields["relay_token"] != null &&
            (relayRoutingToken == null || !validRoutingToken(relayRoutingToken))
        ) invalidEnvelope()
        return RelayPairingAcknowledgement(deviceId, relayRoutingToken)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun validRoutingToken(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= RelayProtocolPolicy.maxRoutingTokenCharacters &&
            value.all { it.code in 0x21..0x7e }

    private fun invalidEnvelope(): Nothing =
        throw RelayProtocolException(RelayProtocolFailure.InvalidEnvelope)
}

enum class RelayApprovalProbeResult {
    Approved,
    Rejected,
    Ignore,
}

object RelayApprovalProbe {
    private val json = Json { ignoreUnknownKeys = true }

    fun evaluateGatewayPing(text: String): RelayApprovalProbeResult {
        val response = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return RelayApprovalProbeResult.Rejected
        val id = (response["id"] as? JsonPrimitive)?.contentOrNull
        if (id == "pairing-probe") {
            return if (response["error"] == null && response["result"] is JsonObject) {
                RelayApprovalProbeResult.Approved
            } else {
                RelayApprovalProbeResult.Rejected
            }
        }
        return if (id != null || (response["method"] as? JsonPrimitive)?.contentOrNull != null) {
            RelayApprovalProbeResult.Ignore
        } else {
            RelayApprovalProbeResult.Rejected
        }
    }

    fun isSuccessfulGatewayPing(text: String): Boolean =
        evaluateGatewayPing(text) == RelayApprovalProbeResult.Approved
}

object RelayAdmissionEnvelope {
    @Throws(RelayProtocolException::class)
    fun controllerOpen(deviceId: String, profile: String, resumeCursor: Long? = null): ByteArray {
        if (RelayBase64.urlSafeDecodeExact(deviceId, RelayProtocolPolicy.deviceIdBytes) == null) invalid()
        if (profile.isEmpty() || profile.length > RelayProtocolPolicy.maxProfileCharacters ||
            profile.any { !it.isAsciiLetterOrDigit() && it != '-' && it != '_' && it != '.' }
        ) {
            invalid()
        }
        if (resumeCursor != null && resumeCursor < 0) invalid()
        var envelope = "{\"device_id\":\"$deviceId\",\"profile\":\"$profile\""
        if (resumeCursor != null) envelope += ",\"resume_cursor\":$resumeCursor"
        envelope += ",\"type\":\"controller.open\"}"
        return envelope.encodeToByteArray().also {
            if (it.size > RelayProtocolPolicy.maxEnvelopeBytes) invalid()
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun invalid(): Nothing =
        throw RelayProtocolException(RelayProtocolFailure.InvalidEnvelope)
}

enum class RelayTargetStatus(val wireValue: String) {
    Pending("pending"),
    Approved("approved");

    companion object {
        fun fromWire(value: String): RelayTargetStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

class RelayPairedTarget(
    val id: String,
    val label: String,
    val relayOrigin: String,
    val installationId: ByteArray,
    val hostPublicKey: ByteArray,
    val deviceId: String,
    val deviceStaticPrivateKey: ByteArray,
    val fingerprint: String,
    val status: RelayTargetStatus,
    val createdAtEpochSeconds: Long,
    val lastUsedEpochSeconds: Long?,
    val relayRoutingToken: String? = null,
) {
    val displayLabel: String
        get() = label.ifEmpty { "Relay ${fingerprint.take(6)}" }

    fun copy(
        label: String = this.label,
        status: RelayTargetStatus = this.status,
        lastUsedEpochSeconds: Long? = this.lastUsedEpochSeconds,
        relayRoutingToken: String? = this.relayRoutingToken,
    ): RelayPairedTarget = RelayPairedTarget(
        id,
        label,
        relayOrigin,
        installationId.copyOf(),
        hostPublicKey.copyOf(),
        deviceId,
        deviceStaticPrivateKey.copyOf(),
        fingerprint,
        status,
        createdAtEpochSeconds,
        lastUsedEpochSeconds,
        relayRoutingToken,
    )
}

class RelayTargetCodecException : Exception("Saved Mercury Relay targets are invalid")

object RelayTargetCodec {
    const val maxTargets = 8
    const val maxLabelCharacters = 80
    const val maxPersistedBytes = 64 * 1024
    const val persistedVersion = 1

    private val json = Json { ignoreUnknownKeys = true }
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    @Throws(RelayTargetCodecException::class)
    fun encode(targets: List<RelayPairedTarget>): ByteArray {
        if (targets.size > maxTargets || targets.map { it.id.lowercase() }.toSet().size != targets.size) invalid()
        targets.forEach(::validate)
        val root = buildJsonObject {
            put("version", persistedVersion)
            put("targets", buildJsonArray {
                targets.forEach { target ->
                    add(buildJsonObject {
                        put("id", target.id)
                        put("label", target.label)
                        put("relayOrigin", target.relayOrigin)
                        put("installationID", RelayBase64.standardEncode(target.installationId))
                        put("hostPublicKey", RelayBase64.standardEncode(target.hostPublicKey))
                        put("deviceID", target.deviceId)
                        put("deviceStaticPrivateKey", RelayBase64.standardEncode(target.deviceStaticPrivateKey))
                        put("fingerprint", target.fingerprint)
                        put("status", target.status.wireValue)
                        put("createdAtEpochSeconds", target.createdAtEpochSeconds)
                        target.lastUsedEpochSeconds?.let { put("lastUsedEpochSeconds", it) }
                        target.relayRoutingToken?.let { put("relayRoutingToken", it) }
                    })
                }
            })
        }
        return root.toString().encodeToByteArray().also { if (it.size > maxPersistedBytes) invalid() }
    }

    @Throws(RelayTargetCodecException::class)
    fun decode(data: ByteArray): List<RelayPairedTarget> {
        if (data.size > maxPersistedBytes) invalid()
        val root = runCatching { json.parseToJsonElement(data.decodeToString()) as? JsonObject }
            .getOrNull()
            ?: invalid()
        if ((root["version"] as? JsonPrimitive)?.longOrNull != persistedVersion.toLong()) invalid()
        val rows = root["targets"] as? JsonArray ?: invalid()
        if (rows.size > maxTargets) invalid()
        val targets = rows.map { decodeRow(it as? JsonObject ?: invalid()) }
        if (targets.map { it.id.lowercase() }.toSet().size != targets.size) invalid()
        return targets
    }

    private fun decodeRow(row: JsonObject): RelayPairedTarget {
        fun string(name: String): String = (row[name] as? JsonPrimitive)?.contentOrNull ?: invalid()
        val target = RelayPairedTarget(
            id = string("id"),
            label = string("label"),
            relayOrigin = string("relayOrigin"),
            installationId = RelayBase64.decodeExact(string("installationID"), RelayProtocolPolicy.installationIdBytes) ?: invalid(),
            hostPublicKey = RelayBase64.decodeExact(string("hostPublicKey"), RelayProtocolPolicy.hostPublicKeyBytes) ?: invalid(),
            deviceId = string("deviceID"),
            deviceStaticPrivateKey = RelayBase64.decodeExact(string("deviceStaticPrivateKey"), 32) ?: invalid(),
            fingerprint = string("fingerprint"),
            status = RelayTargetStatus.fromWire(string("status")) ?: invalid(),
            createdAtEpochSeconds = (row["createdAtEpochSeconds"] as? JsonPrimitive)?.longOrNull ?: invalid(),
            lastUsedEpochSeconds = (row["lastUsedEpochSeconds"] as? JsonPrimitive)?.longOrNull,
            relayRoutingToken = (row["relayRoutingToken"] as? JsonPrimitive)?.contentOrNull,
        )
        validate(target)
        return target
    }

    private fun validate(target: RelayPairedTarget) {
        if (!uuidPattern.matches(target.id) || target.label.length > maxLabelCharacters ||
            target.label.any { it.code < 0x20 || it.code == 0x7f } ||
            RelayPairingPayload.normalizeOrigin(target.relayOrigin) == null ||
            target.installationId.size != RelayProtocolPolicy.installationIdBytes ||
            target.hostPublicKey.size != RelayProtocolPolicy.hostPublicKeyBytes ||
            target.deviceStaticPrivateKey.size != 32 ||
            RelayBase64.urlSafeDecodeExact(target.deviceId, RelayProtocolPolicy.deviceIdBytes) == null ||
            target.fingerprint.length != RelayProtocolPolicy.fingerprintHexCharacters ||
            target.createdAtEpochSeconds < 0 || (target.lastUsedEpochSeconds ?: 0) < 0 ||
            (target.relayRoutingToken != null && !validRoutingToken(target.relayRoutingToken))
        ) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw RelayTargetCodecException()

    private fun validRoutingToken(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= RelayProtocolPolicy.maxRoutingTokenCharacters &&
            value.all { it.code in 0x21..0x7e }
}
