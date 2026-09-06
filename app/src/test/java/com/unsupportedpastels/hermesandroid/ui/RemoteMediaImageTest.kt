package com.unsupportedpastels.hermesandroid.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun inetAddress(value: String): InetAddress = InetAddress.getByName(value)

class RemoteMediaImageTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpsMediaUrls() {
        val credentialedUrl = "https://user" + ":secret@cdn.example/image.png"
        assertTrue(validateRemoteMediaUrl("https://cdn.example/image.png"))
        assertFalse(validateRemoteMediaUrl("http://cdn.example/image.png"))
        assertFalse(validateRemoteMediaUrl(credentialedUrl))
        assertFalse(validateRemoteMediaUrl("https://cdn.example:8443/image.png"))
        assertFalse(validateRemoteMediaUrl("https://127.0.0.1/image.png"))
        assertFalse(validateRemoteMediaUrl("https://localhost/image.png"))
    }

    @Test
    fun rejectsHostsThatResolveToNonPublicAddresses() {
        assertFalse(hostResolvesToPublicNetwork("127.0.0.1"))
        assertFalse(hostResolvesToPublicNetwork("169.254.169.254"))
        assertFalse(hostResolvesToPublicNetwork("10.0.0.5"))
        assertFalse(hostResolvesToPublicNetwork("192.168.1.10"))
        assertFalse(hostResolvesToPublicNetwork("172.16.0.1"))
        assertFalse(hostResolvesToPublicNetwork("0.0.0.0"))
        assertFalse(hostResolvesToPublicNetwork("100.64.0.1"))
        assertTrue(hostResolvesToPublicNetwork("93.184.216.34"))
        assertTrue(hostResolvesToPublicNetwork("8.8.8.8"))
    }

    @Test
    fun rejectsHostResolvingToPrivateWhenAnyResolvedAddressIsPrivate() {
        // A hostname (DNS rebinding target) that also resolves to a private address is rejected.
        assertFalse(
            hostResolvesToPublicNetwork(
                "public.example.com",
                resolve = { listOf(inetAddress("93.184.216.34"), inetAddress("10.0.0.5")) },
            ),
        )
        assertTrue(
            hostResolvesToPublicNetwork(
                "public.example.com",
                resolve = { listOf(inetAddress("93.184.216.34"), inetAddress("8.8.8.8")) },
            ),
        )
    }

    @Test
    fun downloaderRefusesPrivateResolvingHostBeforeIssuingRequest() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond(
                content = ByteArray(16),
                headers = headersOf(HttpHeaders.ContentType, "image/png"),
            )
        }) { configureRemoteImageHttpClient() }

        val downloader = RemoteImageDownloader(
            client = client,
            resolveHost = { host ->
                when (host) {
                    "internal.example.com" -> listOf(inetAddress("127.0.0.1"))
                    "cdn.example" -> listOf(inetAddress("93.184.216.34"))
                    else -> emptyList()
                }
            },
        )

        val rejected = downloader.download("https://internal.example.com/image.png")
        assertTrue(rejected is RemoteImageDownloadResult.InvalidUrl)
        assertEquals(0, requests)

        val accepted = downloader.download("https://cdn.example/image.png")
        assertTrue(accepted is RemoteImageDownloadResult.Success)
        assertEquals(1, requests)
        client.close()
    }

    @Test
    fun acceptsImageHostPathsButRejectsNonImageAndMalformedPaths() {
        assertTrue(validateGatewayMediaPath("/workspace/project/generated.jpg"))
        assertTrue(validateGatewayMediaPath("/workspace/project/generated.PNG"))
        assertFalse(validateGatewayMediaPath("relative/generated.jpg"))
        assertFalse(validateGatewayMediaPath("/workspace/project/notes.txt"))
        assertFalse(validateGatewayMediaPath("/workspace/project/no-extension"))
    }

    @Test
    fun acceptsVideoHostPathsButRejectsNonVideoAndMalformedPaths() {
        assertTrue(validateGatewayVideoPath("/workspace/scene-00/preview.mp4"))
        assertTrue(validateGatewayVideoPath("/workspace/scene-00/preview.WEBM"))
        assertFalse(validateGatewayVideoPath("relative/preview.mp4"))
        assertFalse(validateGatewayVideoPath("/workspace/scene-00/generated.jpg"))
        assertFalse(validateGatewayVideoPath("/workspace/scene-00/no-extension"))
    }

    @Test
    fun oversizedImageBodyIsRejectedBeforeDecode() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = ByteArray(2_049),
                headers = headersOf(HttpHeaders.ContentType, "image/png"),
            )
        }) { configureRemoteImageHttpClient() }

        val result = RemoteImageDownloader(
            client,
            maxBytes = 2_048,
            resolveHost = { listOf(inetAddress("93.184.216.34")) },
        ).download("https://cdn.example/image.png")

        assertTrue(result is RemoteImageDownloadResult.TooLarge)
        client.close()
    }

    @Test
    fun redirectIsNotFollowed() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond(
                content = ByteArray(0),
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/image.png"),
            )
        }) { configureRemoteImageHttpClient() }

        val result = RemoteImageDownloader(
            client,
            resolveHost = { listOf(inetAddress("93.184.216.34")) },
        ).download("https://cdn.example/image.png")

        assertTrue(result is RemoteImageDownloadResult.HttpFailure)
        assertEquals(302, (result as RemoteImageDownloadResult.HttpFailure).statusCode)
        assertEquals(1, requests)
        client.close()
    }
}
