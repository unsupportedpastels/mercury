package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.HttpHermesConnectionClient
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.toHermesCredential
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFilesTransportTest {
    @Test
    fun officialListingRetainsFilesDirectoriesAndServerScopeMetadataTolerantly() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/files", request.url.encodedPath)
            assertEquals("/srv", request.url.parameters["path"])
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            respond(
                """{
                    "path":"/srv","parent":"/","root":"/","locked_root":"/srv","can_change_path":false,
                    "entries":[
                      {"name":"docs","path":"/srv/docs","is_directory":true,"size":null,"mime_type":null},
                      {"name":"notes.txt","path":"/srv/notes.txt","is_directory":false,"size":5,"mime_type":"text/plain","mtime":1.5},
                      {"name":"bad/row","path":"relative","is_directory":false,"size":-1},
                      {"name":"unknown","path":"/srv/unknown","is_directory":false,"type":"socket"}
                    ]
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val listing = HttpHermesConnectionClient(HttpClient(engine)).loadHostFiles(
            ServerOrigin.parse("https://hermes.example"),
            "access".toHermesCredential(),
            "/srv",
        )

        assertEquals("/srv", listing.path)
        assertEquals("/", listing.root)
        assertEquals("/srv", listing.lockedRoot)
        assertTrue(!listing.canChangePath)
        assertEquals(listOf("docs", "notes.txt"), listing.entries.map { it.name })
        assertEquals(5L, listing.entries[1].size)
    }

    @Test
    fun readAndDownloadEnforceAuthenticatedRoutesAndBoundedContent() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/api/files/read" -> respond(
                    """{"name":"notes.txt","path":"/srv/notes.txt","size":5,"mime_type":"text/plain","data_url":"data:text/plain;base64,aGVsbG8="}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/files/download" -> respond(
                    "hello".toByteArray(),
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("text/plain"),
                        HttpHeaders.ContentLength to listOf("5"),
                    ),
                )
                else -> error("Unexpected ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))
        val origin = ServerOrigin.parse("https://hermes.example")

        val read = client.readManagedFile(origin, "access".toHermesCredential(), "/srv/notes.txt")
        val download = client.downloadManagedFile(origin, "access".toHermesCredential(), "/srv/notes.txt")

        assertEquals("hello", String(read.bytes))
        assertEquals("hello", String(download.bytes))
        assertEquals("text/plain", download.mimeType)
    }

    @Test
    fun invalidPathDoesNotDispatchAndCancellationIsPreserved() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            throw CancellationException("cancelled")
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))
        val origin = ServerOrigin.parse("https://hermes.example")

        val invalid = runCatching { client.downloadManagedFile(origin, "access".toHermesCredential(), "/srv/../secret") }.exceptionOrNull()
        val cancelled = runCatching { client.downloadManagedFile(origin, "access".toHermesCredential(), "/srv/file.txt") }.exceptionOrNull()

        assertTrue(invalid is com.unsupportedpastels.hermesandroid.connection.HermesConnectionException)
        assertTrue(cancelled is CancellationException)
        assertEquals(1, requests)
    }
}
