package com.unsupportedpastels.mercury.core.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** APNs routing namespace. Wire values are exact and never inferred from build mode. */
enum class PushEnvironment(val wireValue: String) {
    SANDBOX("sandbox"),
    PRODUCTION("production"),
}

/** Pure parsing and capability policy shared by every native adapter. */
object PushEnvironmentPolicy {
    const val CAPABILITY_VERSION = 1
    const val GENERIC_REGISTER_VERSION = 2

    fun parse(value: String?): PushEnvironment? = when (value) {
        PushEnvironment.SANDBOX.wireValue -> PushEnvironment.SANDBOX
        PushEnvironment.PRODUCTION.wireValue -> PushEnvironment.PRODUCTION
        else -> null
    }

    /** Swift-friendly parser result without requiring native enum ownership. */
    fun canonicalValue(value: String?): String? = parse(value)?.wireValue

    fun supportsVersion2Generic(status: Map<*, *>?, environment: PushEnvironment): Boolean {
        val capabilities = status?.get("capabilities") as? Map<*, *> ?: return false
        val advertised = capabilities["push_environments"] as? Map<*, *> ?: return false
        if (advertised["version"] !is Int || advertised["version"] != CAPABILITY_VERSION) return false
        if (advertised["generic_register_version"] !is Int ||
            advertised["generic_register_version"] != GENERIC_REGISTER_VERSION) return false
        val raw = advertised["environments"] as? List<*> ?: return false
        val parsed = raw.map { parse(it as? String) ?: return false }
        if (parsed.distinct().size != parsed.size) return false
        return environment in parsed
    }

    /** Swift-friendly capability adapter entry point. */
    fun supportsVersion2GenericValue(status: Map<*, *>?, environmentValue: String?): Boolean =
        parse(environmentValue)?.let { supportsVersion2Generic(status, it) } ?: false

    /** 0 means unsupported, 1 means legacy sandbox params, 2 means v2 params. */
    fun genericRegisterVersion(environment: PushEnvironment, legacySupported: Boolean, status: Map<*, *>?): Int =
        when {
            supportsVersion2Generic(status, environment) -> GENERIC_REGISTER_VERSION
            environment == PushEnvironment.SANDBOX && legacySupported -> 1
            else -> 0
        }

    fun genericRegisterVersionValue(environmentValue: String?, legacySupported: Boolean, status: Map<*, *>?): Int =
        parse(environmentValue)?.let { genericRegisterVersion(it, legacySupported, status) } ?: 0

    /** JSON boundary used by Swift so NSNumber bridging cannot turn integers into booleans. */
    fun genericRegisterVersionJson(environmentValue: String?, legacySupported: Boolean, statusJson: String?): Int {
        val environment = parse(environmentValue) ?: return 0
        val legacyVersion = if (environment == PushEnvironment.SANDBOX && legacySupported) 1 else 0
        val root = statusJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return legacyVersion
        val capabilities = root["capabilities"] as? JsonObject ?: return legacyVersion
        val advertised = capabilities["push_environments"] as? JsonObject
        val supportsV2 = advertised?.let { capability ->
            fun exactInt(name: String): Int? = (capability[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val values = capability["environments"] as? JsonArray
            val parsed = values?.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.let(::parse) }
            exactInt("version") == CAPABILITY_VERSION &&
                exactInt("generic_register_version") == GENERIC_REGISTER_VERSION &&
                parsed != null && parsed.all { it != null } && parsed.filterNotNull().distinct().size == parsed.size &&
                environment in parsed
        } == true
        return when {
            supportsV2 -> GENERIC_REGISTER_VERSION
            environment == PushEnvironment.SANDBOX && legacySupported -> 1
            else -> 0
        }
    }
}
