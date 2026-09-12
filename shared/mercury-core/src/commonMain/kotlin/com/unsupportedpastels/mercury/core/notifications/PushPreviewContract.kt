package com.unsupportedpastels.mercury.core.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Frozen Mercury Relay encrypted-notification preview v1 contract. */
object PushPreviewContract {
    const val VERSION = 1
    const val REGISTER_METHOD = "relay.push.preview.register"
    const val UNREGISTER_METHOD = "relay.push.unregister"
    const val AEAD = "CHACHA20-POLY1305"
    const val ENVELOPE_ALGORITHM = "C20P"
    const val DOMAIN = "mercury.push-preview.v1"
    const val MAX_PLAINTEXT_BYTES = 1280
    const val MAX_TITLE_UTF8_BYTES = 160
    const val MAX_BODY_UTF8_BYTES = 640
    const val MAX_ROUTE_SESSION_UTF8_BYTES = 128
    const val MAX_ROUTE_PROFILE_UTF8_BYTES = 64

    data class Capability(val maxPlaintextBytes: Int, val maxTitleUtf8Bytes: Int, val maxBodyUtf8Bytes: Int)

    /** Fail closed unless every security-relevant field exactly matches v1. */
    fun capability(status: JsonObject): Capability? {
        val caps = status["capabilities"] as? JsonObject ?: return null
        val value = caps["push_previews"] as? JsonObject ?: return null
        fun integer(name: String): Int? = (value[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        fun string(name: String): String? = (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (integer("version") != VERSION || string("register_method") != REGISTER_METHOD ||
            string("unregister_method") != UNREGISTER_METHOD || string("aead") != AEAD) return null
        val plaintext = integer("max_plaintext_bytes") ?: return null
        val title = integer("max_title_utf8_bytes") ?: return null
        val body = integer("max_body_utf8_bytes") ?: return null
        if (plaintext !in 1..MAX_PLAINTEXT_BYTES || title !in 1..MAX_TITLE_UTF8_BYTES || body !in 1..MAX_BODY_UTF8_BYTES) return null
        return Capability(plaintext, title, body)
    }

    /** The registration preference matrix accepts actual JSON booleans only. */
    fun validPreference(value: JsonPrimitive?): Boolean = value?.takeUnless { it.isString }?.booleanOrNull != null

    /** uint32-big-endian byte length followed by UTF-8 bytes for each frozen field. */
    fun aad(environment: String, wakeHandle: String, eventId: String, keyId: String): ByteArray {
        val fields = listOf(DOMAIN, VERSION.toString(), ENVELOPE_ALGORITHM, environment, wakeHandle, eventId, keyId)
        val output = ArrayList<Byte>()
        fields.forEach { field ->
            val bytes = field.encodeToByteArray()
            require(bytes.size <= Int.MAX_VALUE)
            output += ((bytes.size ushr 24) and 0xff).toByte()
            output += ((bytes.size ushr 16) and 0xff).toByte()
            output += ((bytes.size ushr 8) and 0xff).toByte()
            output += (bytes.size and 0xff).toByte()
            output.addAll(bytes.toList())
        }
        return output.toByteArray()
    }
}
