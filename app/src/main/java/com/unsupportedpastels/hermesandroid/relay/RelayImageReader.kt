package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HermesChatMethodNotFoundException
import java.util.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class RelayImageUnsupportedException : Exception("This Relay host does not support image reads")

/** One reader per connection owner: serialize entire responses, not only outbound frames. */
class RelayImageReader {
    private val mutex = Mutex()

    suspend fun read(
        profile: String,
        path: String,
        request: suspend (String, JsonObject) -> JsonObject,
    ): ByteArray = mutex.withLock {
        require(path.startsWith('/') && path.encodeToByteArray().size in 2..4096 &&
            path.none { it.code < 32 || it.code in 127..159 } &&
            path.split('/').none { it == ".." } && !path.contains("://")) { "Invalid image path" }
        val status = try {
            request("relay.status", buildJsonObject {})
        } catch (_: HermesChatMethodNotFoundException) {
            throw RelayImageUnsupportedException()
        }
        val capability = (status["capabilities"] as? JsonObject)?.get("image_read") as? JsonObject
        if (capability?.get("method") != JsonPrimitive("relay.image.read") ||
            capability["max_bytes"] != JsonPrimitive(MAX_BYTES) ||
            (capability["mime_types"] as? JsonArray)?.any { it is JsonPrimitive && it.isString && it.content in MIME_TYPES } != true
        ) throw RelayImageUnsupportedException()
        val result = try {
            // Drain an already dispatched image even when its view disappears. Releasing the
            // permit early would overlap the next response in the bounded Relay queue.
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                request("relay.image.read", buildJsonObject { put("profile", profile); put("path", path) })
            }
        } catch (_: HermesChatMethodNotFoundException) {
            throw RelayImageUnsupportedException()
        }
        currentCoroutineContext().ensureActive()
        decode(result).also {
            require(result["mime_type"] in (capability["mime_types"] as JsonArray)) { "Invalid image response" }
        }
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private val MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")

        fun decode(result: JsonObject): ByteArray {
            fun invalid(): Nothing = throw IllegalArgumentException("Invalid image response")
            val mime = (result["mime_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
            val size = (result["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalid()
            val encoded = (result["base64"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
            if (mime !in MIME_TYPES || size !in 1..MAX_BYTES || encoded.length != ((size + 2) / 3) * 4) invalid()
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { invalid() }
            if (bytes.size != size || Base64.getEncoder().encodeToString(bytes) != encoded) invalid()
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
            val detected = when {
                starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
                starts(255, 216, 255) -> "image/jpeg"
                starts(71, 73, 70, 56, 55, 97) || starts(71, 73, 70, 56, 57, 97) -> "image/gif"
                starts(66, 77) -> "image/bmp"
                starts(82, 73, 70, 70) && bytes.size >= 12 && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
                else -> invalid()
            }
            if (detected != mime) invalid()
            return bytes
        }
    }
}
