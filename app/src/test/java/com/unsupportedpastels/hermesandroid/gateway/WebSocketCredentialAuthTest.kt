package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class WebSocketCredentialAuthTest {
    @Test
    fun nativeBearerMintsFreshTicketForEveryChatConnectionAndUsesOnlyTicketQuery() = runTest {
        val origin = ServerOrigin.parse("https://hermes.example")
        val credential = HermesCredential.NativeBearer.create("native-secret")
        val ticketClient = SequencedTicketClient(listOf("ticket one", "ticket/two"))
        val socketFactory = UrlRecordingChatSocketFactory()
        val gateway = HermesChatGateway(
            origin = origin,
            credential = credential,
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )

        gateway.connect().close()
        gateway.connect().close()

        assertEquals(2, ticketClient.calls)
        assertEquals(
            listOf(
                mapOf("ticket" to "ticket one"),
                mapOf("ticket" to "ticket/two"),
            ),
            socketFactory.urls.map(::decodedQuery),
        )
    }

    @Test
    fun loopbackSessionSkipsTicketMintingAndUsesOnlyEncodedTokenQuery() = runTest {
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        val ticketClient = SequencedTicketClient(emptyList())
        val socketFactory = UrlRecordingChatSocketFactory()
        val gateway = HermesChatGateway(
            origin = origin,
            credential = HermesCredential.LoopbackSession.create(origin, "session +/?& token"),
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )

        gateway.connect().close()

        assertEquals(0, ticketClient.calls)
        assertEquals(listOf(mapOf("token" to "session +/?& token")), socketFactory.urls.map(::decodedQuery))
    }

    @Test
    fun noneSkipsTicketMintingAndConnectsChatWithoutAuthQuery() = runTest {
        val origin = ServerOrigin.parse("http://10.0.1.2")
        val ticketClient = SequencedTicketClient(emptyList())
        val socketFactory = UrlRecordingChatSocketFactory()
        val gateway = HermesChatGateway(
            origin = origin,
            credential = HermesCredential.None,
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )

        gateway.connect().close()

        assertEquals(0, ticketClient.calls)
        assertEquals(listOf("ws://10.0.1.2/api/ws"), socketFactory.urls)
    }

    @Test
    fun chatSocketFailureDropsCredentialBearingUrlFromEntireCauseChain() = runTest {
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        val secretsAndCredentials = listOf(
            "ticket-secret" to HermesCredential.NativeBearer.create("native-secret"),
            "session-secret" to HermesCredential.LoopbackSession.create(origin, "session-secret"),
        )

        secretsAndCredentials.forEach { (secret, credential) ->
            val failure = runCatching {
                HermesChatGateway(
                    origin = origin,
                    credential = credential,
                    ticketClient = SequencedTicketClient(listOf("ticket-secret")),
                    socketFactory = SecretEchoingChatSocketFactory(),
                    parentScope = backgroundScope,
                ).connect()
            }.exceptionOrNull()

            assertTrue(failure is HermesChatTransportException)
            assertEquals(null, failure?.cause)
            assertFalse(failure.toString().contains(secret))
        }
    }

    @Test
    fun chatSocketCancellationPreservesCancellationWithoutCredentialThrowableGraph() = runTest {
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        val secret = "cancellation-session-secret"
        val failure = runCatching {
            HermesChatGateway(
                origin = origin,
                credential = HermesCredential.LoopbackSession.create(origin, secret),
                ticketClient = SequencedTicketClient(emptyList()),
                socketFactory = SecretEchoingChatCancellationFactory(),
                parentScope = backgroundScope,
            ).connect()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(null, failure?.cause)
        assertTrue(failure?.suppressed?.isEmpty() == true)
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun loopbackSessionOriginMismatchIsRejectedBeforeTicketOrSocketNetwork() = runTest {
        val boundOrigin = ServerOrigin.parse("http://127.0.0.1:9119")
        val requestedOrigin = ServerOrigin.parse("http://127.0.0.1:19119")
        val token = "origin-bound-secret"
        val ticketClient = SequencedTicketClient(emptyList())
        val socketFactory = UrlRecordingChatSocketFactory()
        val gateway = HermesChatGateway(
            origin = requestedOrigin,
            credential = HermesCredential.LoopbackSession.create(boundOrigin, token),
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )

        val failure = runCatching { gateway.connect() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure.toString().contains(token))
        assertEquals(0, ticketClient.calls)
        assertTrue(socketFactory.urls.isEmpty())
    }
}

private class SequencedTicketClient(private val tickets: List<String>) : WsTicketClient {
    var calls = 0

    override suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ): WsTicket = WsTicket(tickets[calls++], 30)
}

private class UrlRecordingChatSocketFactory : ChatWebSocketFactory {
    val urls = mutableListOf<String>()

    override suspend fun connect(url: String): HermesChatSocket {
        urls += url
        return object : HermesChatSocket {
            override suspend fun sendText(text: String) = Unit
            override suspend fun receiveText(): String? = null
            override suspend fun close() = Unit
        }
    }
}

private class SecretEchoingChatSocketFactory : ChatWebSocketFactory {
    override suspend fun connect(url: String): HermesChatSocket =
        throw IllegalStateException("failed $url", IllegalArgumentException(url))
}

private class SecretEchoingChatCancellationFactory : ChatWebSocketFactory {
    override suspend fun connect(url: String): HermesChatSocket {
        val failure = CancellationException("cancelled $url")
        failure.initCause(IllegalStateException(url))
        failure.addSuppressed(IllegalArgumentException(url))
        throw failure
    }
}

private fun decodedQuery(url: String): Map<String, String> =
    URI(url).rawQuery
        ?.split('&')
        ?.filter(String::isNotEmpty)
        ?.associate { part ->
            val (key, value) = part.split('=', limit = 2)
            java.net.URLDecoder.decode(key, Charsets.UTF_8.name()) to
                java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }
        .orEmpty()
