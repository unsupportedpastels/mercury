package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManagedVideoDownloadSafetyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val origin = ServerOrigin.parse("https://hermes.example")

    @Test fun rejectsErrorHeadersBeforeBodyEof() = assertEarlyRejection(
        HttpStatusCode.InternalServerError, "video/mp4", null, 0, "HTTP 500",
    )

    @Test fun rejectsNonVideoHeadersBeforeBodyEof() = assertEarlyRejection(
        HttpStatusCode.OK, "text/plain", null, 0, "not a video",
    )

    @Test fun rejectsDeclaredOversizeBeforeBodyEof() = assertEarlyRejection(
        HttpStatusCode.OK, "video/mp4", "9", 0, "too large",
    )

    @Test fun rejectsUnknownLengthAtCapBeforeBodyEof() = assertEarlyRejection(
        HttpStatusCode.OK, "video/mp4", null, 9, "too large",
    )

    private fun assertEarlyRejection(
        status: HttpStatusCode,
        mimeType: String,
        length: String?,
        prefixBytes: Int,
        expectedMessage: String,
    ) = runBlocking {
        // The producer cannot send EOF until the test releases it. A buffering
        // request or a chunk reader waiting beyond cap+1 times out instead.
        val body = ByteChannel(autoFlush = true)
        val headersReturned = CompletableDeferred<Unit>()
        val releaseEof = CompletableDeferred<Unit>()
        val producer = launch {
            if (prefixBytes > 0) body.writeFully(ByteArray(prefixBytes))
            releaseEof.await()
            body.close()
        }
        val engine = MockEngine {
            headersReturned.complete(Unit)
            respond(body, status, headersOf(*buildList {
                add(HttpHeaders.ContentType to listOf(mimeType))
                length?.let { add(HttpHeaders.ContentLength to listOf(it)) }
            }.toTypedArray()))
        }
        val http = HttpClient(engine)
        val destination = File(temporaryFolder.root, "clip.mp4")
        val download = async {
            try {
                HttpHermesConnectionClient(http).streamManagedVideoToFile(
                    origin, null, "/clip.mp4", destination, maxBytes = 8,
                )
                null
            } catch (error: HermesConnectionException) {
                error
            }
        }
        try {
            withTimeout(5_000) { headersReturned.await() }
            val failure = withTimeout(5_000) { download.await() }
            assertTrue(failure?.message.orEmpty().contains(expectedMessage))
            assertFalse(releaseEof.isCompleted)
            assertTrue(body.isClosedForRead)
            assertFalse(destination.exists())
            assertFalse(File(destination.path + ".part").exists())
        } finally {
            download.cancelAndJoin()
            releaseEof.complete(Unit)
            producer.cancelAndJoin()
            http.close()
        }
    }

    @Test fun failedRenameDoesNotCopyOverDestination() = runBlocking {
        val destination = temporaryFolder.newFolder("clip.mp4")
        val http = HttpClient(MockEngine {
            respond(byteArrayOf(1, 2, 3), headers = headersOf(HttpHeaders.ContentType, "video/mp4"))
        })
        try {
            val failure = runCatching {
                HttpHermesConnectionClient(http).streamManagedVideoToFile(
                    origin, null, "/clip.mp4", destination, maxBytes = 8,
                )
            }.exceptionOrNull()
            assertTrue("Publication must fail closed", failure is HermesConnectionException)
            assertTrue("Existing destination must not be replaced by a copy", destination.isDirectory)
            assertEquals(0, destination.listFiles()!!.size)
            assertFalse(File(destination.path + ".part").exists())
        } finally {
            http.close()
        }
    }
}
