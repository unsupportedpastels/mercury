package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Close-code taxonomy per answers doc §21. Only `4401` means the credential was
 * rejected; every other code leaves the credential alone, the same way HTTP 403
 * does for REST. Both socket transports share this classifier so there is one
 * taxonomy rather than two drifting ones.
 */
class SocketCloseTaxonomyTest {
    @Test
    fun onlyTheCredentialRejectionCodeAllowsCredentialRecovery() {
        val expected = mapOf(
            4401 to SocketCloseClass.CredentialRejected,
            4403 to SocketCloseClass.PolicyRejected,
            4404 to SocketCloseClass.FeatureUnavailable,
            4408 to SocketCloseClass.PeerBoundary,
            1011 to SocketCloseClass.ServerError,
        )

        expected.forEach { (code, classification) ->
            assertEquals("close code $code", classification, classifySocketClose(code))
        }
        assertEquals(
            listOf(SocketCloseClass.CredentialRejected),
            SocketCloseClass.entries.filter { it.allowsCredentialRecovery },
        )
    }

    @Test
    fun anyUnlistedOrAbsentCodeIsATransportFailure() {
        listOf(null, 1000, 1001, 1006, 1012, 3000, 4400, 4402, 4409, 4500).forEach { code ->
            assertEquals(
                "close code $code",
                SocketCloseClass.TransportFailure,
                classifySocketClose(code),
            )
        }
        assertFalse(SocketCloseClass.TransportFailure.allowsCredentialRecovery)
    }
}

/**
 * The chat gateway's read loop is a genuinely concurrent reader, so these use
 * real dispatchers and synchronise on the fake socket's own teardown signal
 * rather than on virtual time.
 */
class ChatSocketCloseClassTest {
    private val origin = ServerOrigin.parse("http://127.0.0.1:19119")
    private val loopback = HermesCredential.LoopbackSession.create(origin, "session-token")

    @Test
    fun everyCloseCodeReachesTheSessionWithItsOwnClassification() = withReaderScope { scope ->
        val cases = mapOf(
            4401 to SocketCloseClass.CredentialRejected,
            4403 to SocketCloseClass.PolicyRejected,
            4404 to SocketCloseClass.FeatureUnavailable,
            4408 to SocketCloseClass.PeerBoundary,
            1011 to SocketCloseClass.ServerError,
            1006 to SocketCloseClass.TransportFailure,
        )

        cases.forEach { (code, classification) ->
            val socket = CodedCloseSocket()
            val session = scope.connect(socket)
            socket.awaitReaderParked()

            socket.closePeer(code)
            socket.awaitReaderFinished()

            assertEquals("close code $code", classification, session.closeClass)
        }
    }

    @Test
    fun aSocketClosedWithoutACodeStaysATransportFailure() = withReaderScope { scope ->
        val socket = CodedCloseSocket()
        val session = scope.connect(socket)
        socket.awaitReaderParked()

        socket.closePeer(null)
        socket.awaitReaderFinished()

        assertEquals(SocketCloseClass.TransportFailure, session.closeClass)
    }

    /** Our own teardown must never look like a rejection from the server. */
    @Test
    fun aSessionClosedByThisClientReportsNoPeerClassification() = withReaderScope { scope ->
        val socket = CodedCloseSocket()
        val session = scope.connect(socket)
        socket.awaitReaderParked()

        session.close()
        socket.awaitReaderFinished()

        assertNull(session.closeClass)
    }

    @Test
    fun aPendingRequestFailsWithTheClassifiedCloseCode() = withReaderScope { scope ->
        val socket = CodedCloseSocket()
        val session = scope.connect(socket)
        socket.awaitReaderParked()

        val pending = scope.async {
            runCatching { session.setReasoning(RuntimeSessionId("runtime-1"), "medium") }
        }
        socket.awaitSentFrame()
        socket.closePeer(4401)

        val failure = pending.await().exceptionOrNull()
        val closed = failure as? HermesChatSocketClosedException
        assertTrue("expected a classified close, got $failure", closed != null)
        assertEquals(SocketCloseClass.CredentialRejected, closed?.closeClass)
        assertEquals(4401, closed?.closeCode)
    }

    /** OAuth parity re-audit: the loopback path must never mint a ticket. */
    @Test
    fun theLoopbackChatSocketNeverCallsTheTicketEndpoint() = withReaderScope { scope ->
        val ticketClient = ForbiddenTicketClient()
        HermesChatGateway(
            origin = origin,
            credential = loopback,
            ticketClient = ticketClient,
            socketFactory = SingleSocketFactory(CodedCloseSocket()),
            parentScope = scope,
        ).connect()

        assertEquals(0, ticketClient.calls)
    }

    private suspend fun CoroutineScope.connect(socket: CodedCloseSocket): HermesChatSession =
        HermesChatGateway(
            origin = origin,
            credential = loopback,
            ticketClient = ForbiddenTicketClient(),
            socketFactory = SingleSocketFactory(socket),
            parentScope = this,
        ).connect()

    private fun withReaderScope(body: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(kotlinx.coroutines.Job))
        try {
            withTimeout(READER_TIMEOUT_MILLIS) { body(scope) }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val READER_TIMEOUT_MILLIS = 10_000L
    }
}

private class SingleSocketFactory(private val socket: HermesChatSocket) : ChatWebSocketFactory {
    override suspend fun connect(url: String): HermesChatSocket = socket
}

private class ForbiddenTicketClient : WsTicketClient {
    var calls = 0
        private set

    override suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ): WsTicket {
        calls += 1
        throw AssertionError("The loopback path must not mint a WebSocket ticket")
    }
}

/** Fake chat socket whose peer close carries an application close code. */
private class CodedCloseSocket : HermesChatSocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val readerParked = CompletableDeferred<Unit>()
    private val readerFinished = CompletableDeferred<Unit>()
    private val sentFrame = CompletableDeferred<String>()

    @Volatile
    private var peerCloseCode: Int? = null

    override suspend fun sendText(text: String) {
        sentFrame.complete(text)
    }

    override suspend fun receiveText(): String? {
        readerParked.complete(Unit)
        return incoming.receiveCatching().getOrNull()
    }

    override suspend fun closeCode(): Int? = peerCloseCode

    override suspend fun close() {
        incoming.close()
        readerFinished.complete(Unit)
    }

    fun closePeer(code: Int?) {
        peerCloseCode = code
        incoming.close()
    }

    /** The read loop is suspended on the socket, so a close will be observed. */
    suspend fun awaitReaderParked() = readerParked.await()

    /** The read loop has published its classification and torn the socket down. */
    suspend fun awaitReaderFinished() = readerFinished.await()

    suspend fun awaitSentFrame(): String = sentFrame.await()
}
