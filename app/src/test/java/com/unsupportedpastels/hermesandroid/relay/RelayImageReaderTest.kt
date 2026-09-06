package com.unsupportedpastels.hermesandroid.relay

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RelayImageReaderTest {
    @Test fun capabilityGatesOldHosts() = runTest {
        val calls = mutableListOf<String>()
        val error = runCatching {
            RelayImageReader().read("default", "/tmp/a.png") { method, _ ->
                calls += method
                Json.parseToJsonElement("{}").let { it as kotlinx.serialization.json.JsonObject }
            }
        }.exceptionOrNull()
        assertTrue(error is RelayImageUnsupportedException)
        assertEquals(listOf("relay.status"), calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun serializesCompleteResponsesAndRejectsInvalidPathsBeforeDispatch() = runTest {
        val reader = RelayImageReader()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var statuses = 0
        var reads = 0
        val request: suspend (String, kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject = { method, params ->
            val json = if (method == "relay.status") {
                statuses++
                """{"capabilities":{"image_read":{"method":"relay.image.read","max_bytes":2097152,"mime_types":["image/png"]}}}"""
            } else {
                assertEquals(setOf("profile", "path"), params.keys)
                reads++
                release.await()
                """{"mime_type":"image/png","size":8,"base64":"iVBORw0KGgo="}"""
            }
            Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        }
        val first = async { reader.read("default", "/tmp/a.png", request) }
        val second = async { reader.read("default", "/tmp/b.png", request) }
        runCurrent()
        assertEquals(1, statuses)
        assertEquals(1, reads)
        first.cancel()
        runCurrent()
        assertEquals(1, statuses) // cancelled view must drain before the next read
        release.complete(Unit)
        assertTrue(runCatching { first.await() }.isFailure)
        second.await()
        assertEquals(2, reads)
        for (path in listOf("https://example.com/a.png", "/tmp/../a.png", "/tmp/\n.png", "/" + "é".repeat(2048))) {
            assertTrue(runCatching { reader.read("default", path) { _, _ -> error("Must not dispatch") } }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun validatesExactSizeMimeAndCanonicalBase64() {
        fun decode(mime: String = "image/png", size: Int = 8, base64: String = "iVBORw0KGgo=") =
            RelayImageReader.decode(Json.parseToJsonElement("""{"mime_type":"$mime","size":$size,"base64":"$base64"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(8, decode().size)
        assertTrue(runCatching { decode(size = 7) }.isFailure)
        assertTrue(runCatching { decode(mime = "image/jpeg") }.isFailure)
        assertTrue(runCatching { decode(mime = "image/svg+xml") }.isFailure)
        assertTrue(runCatching { decode(base64 = "iVBORw0KGgo") }.isFailure)
        assertTrue(runCatching { decode(size = 2097153) }.isFailure)
    }
}
