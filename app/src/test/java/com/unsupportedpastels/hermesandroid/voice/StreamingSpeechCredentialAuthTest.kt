package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.gateway.WsTicket
import com.unsupportedpastels.hermesandroid.gateway.WsTicketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class StreamingSpeechCredentialAuthTest {
    @Test
    fun nativeBearerMintsFreshTicketForEverySpeechConnectionAndUsesOnlyTicketAuthQuery() = runTest {
        val ticketClient = SpeechTicketClient(listOf("speech one", "speech/two"))
        val socketFactory = UrlRecordingSpeechSocketFactory()
        val transport = StreamingSpeechTransport(ticketClient, socketFactory)
        val origin = ServerOrigin.parse("https://hermes.example")
        val credential = HermesCredential.NativeBearer.create("native-secret")

        transport.connect(origin, credential, "default profile")
        transport.connect(origin, credential, "default profile")

        assertEquals(2, ticketClient.calls)
        assertEquals(
            listOf(
                mapOf("ticket" to "speech one", "profile" to "default profile"),
                mapOf("ticket" to "speech/two", "profile" to "default profile"),
            ),
            socketFactory.urls.map(::speechDecodedQuery),
        )
    }

    @Test
    fun loopbackSessionSkipsTicketMintingAndUsesOnlyEncodedTokenAuthQueryForSpeech() = runTest {
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        val ticketClient = SpeechTicketClient(emptyList())
        val socketFactory = UrlRecordingSpeechSocketFactory()
        val transport = StreamingSpeechTransport(ticketClient, socketFactory)

        transport.connect(
            origin,
            HermesCredential.LoopbackSession.create(origin, "speech +/?& token"),
            "voice profile",
        )

        assertEquals(0, ticketClient.calls)
        assertEquals(
            listOf(mapOf("token" to "speech +/?& token", "profile" to "voice profile")),
            socketFactory.urls.map(::speechDecodedQuery),
        )
    }

    @Test
    fun noneSkipsTicketMintingAndUsesNoSpeechAuthQuery() = runTest {
        val origin = ServerOrigin.parse("http://10.0.1.2")
        val ticketClient = SpeechTicketClient(emptyList())
        val socketFactory = UrlRecordingSpeechSocketFactory()

        StreamingSpeechTransport(ticketClient, socketFactory)
            .connect(origin, HermesCredential.None, "default")

        assertEquals(0, ticketClient.calls)
        assertEquals(listOf(mapOf("profile" to "default")), socketFactory.urls.map(::speechDecodedQuery))
    }

    @Test
    fun speechSocketFailureDropsCredentialBearingUrlFromEntireCauseChain() = runTest {
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        val secretsAndCredentials = listOf(
            "speech-ticket-secret" to HermesCredential.NativeBearer.create("native-secret"),
            "speech-session-secret" to
                HermesCredential.LoopbackSession.create(origin, "speech-session-secret"),
        )

        secretsAndCredentials.forEach { (secret, credential) ->
            val failure = runCatching {
                StreamingSpeechTransport(
                    SpeechTicketClient(listOf("speech-ticket-secret")),
                    SecretEchoingSpeechSocketFactory(),
                ).connect(origin, credential, "default")
            }.exceptionOrNull()

            assertTrue(failure is SpeechStreamConnectException)
            assertEquals(null, failure?.cause)
            assertFalse(failure.toString().contains(secret))
        }
    }

    @Test
    fun speechSocketCancellationPreservesCancellationWithoutCredentialThrowableGraph() = runTest {
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        val secret = "speech-cancellation-session-secret"
        val failure = runCatching {
            StreamingSpeechTransport(
                SpeechTicketClient(emptyList()),
                SecretEchoingSpeechCancellationFactory(),
            ).connect(
                origin,
                HermesCredential.LoopbackSession.create(origin, secret),
                "default",
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(null, failure?.cause)
        assertTrue(failure?.suppressed?.isEmpty() == true)
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun loopbackSessionOriginMismatchStopsSpeechBeforeTicketOrSocketNetwork() = runTest {
        val boundOrigin = ServerOrigin.parse("http://127.0.0.1:9119")
        val requestedOrigin = ServerOrigin.parse("http://127.0.0.1:19119")
        val token = "speech-origin-secret"
        val ticketClient = SpeechTicketClient(emptyList())
        val socketFactory = UrlRecordingSpeechSocketFactory()

        val failure = runCatching {
            StreamingSpeechTransport(ticketClient, socketFactory).connect(
                requestedOrigin,
                HermesCredential.LoopbackSession.create(boundOrigin, token),
                "default",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure.toString().contains(token))
        assertEquals(0, ticketClient.calls)
        assertTrue(socketFactory.urls.isEmpty())
    }
}

private class SpeechTicketClient(private val tickets: List<String>) : WsTicketClient {
    var calls = 0

    override suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ) = WsTicket(tickets[calls++], 30)
}

private class UrlRecordingSpeechSocketFactory : SpeechWebSocketFactory {
    val urls = mutableListOf<String>()

    override suspend fun connect(url: String): SpeechStreamSocket {
        urls += url
        return object : SpeechStreamSocket {
            override suspend fun sendText(text: String) = Unit
            override suspend fun receiveFrame(): SpeechSocketFrame? = null
            override suspend fun close() = Unit
        }
    }
}

private class SecretEchoingSpeechSocketFactory : SpeechWebSocketFactory {
    override suspend fun connect(url: String): SpeechStreamSocket =
        throw IllegalStateException("failed $url", IllegalArgumentException(url))
}

private class SecretEchoingSpeechCancellationFactory : SpeechWebSocketFactory {
    override suspend fun connect(url: String): SpeechStreamSocket {
        val failure = CancellationException("cancelled $url")
        failure.initCause(IllegalStateException(url))
        failure.addSuppressed(IllegalArgumentException(url))
        throw failure
    }
}

private fun speechDecodedQuery(url: String): Map<String, String> =
    URI(url).rawQuery.split('&').associate { part ->
        val (key, value) = part.split('=', limit = 2)
        URLDecoder.decode(key, Charsets.UTF_8.name()) to
            URLDecoder.decode(value, Charsets.UTF_8.name())
    }
