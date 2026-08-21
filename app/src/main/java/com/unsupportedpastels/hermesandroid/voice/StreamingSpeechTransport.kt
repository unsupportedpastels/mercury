package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.gateway.WsTicketClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds the same-origin `wss://…/api/audio/speak-stream` URL. Speech sockets
 * authenticate exactly like the chat socket: a fresh single-use ticket minted
 * via `POST /api/auth/ws-ticket`, never a bearer token in the URL. The URL is
 * never logged (it carries the ticket).
 */
internal fun speakStreamUrl(origin: ServerOrigin, authQuery: String?, profile: String): String {
    val encodedProfile = URLEncoder.encode(profile.take(64), StandardCharsets.UTF_8.name())
    val query = listOfNotNull(authQuery, "profile=$encodedProfile").joinToString("&")
    return "${origin.webSocketValue}/api/audio/speak-stream?$query"
}

/** Seam the ViewModel uses to open speech sockets (fake-able in tests). */
fun interface SpeechStreamConnector {
    suspend fun connect(
        origin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): SpeechStreamSocket
}

/**
 * Opens streaming-speech sockets. Each [connect] mints a fresh single-use
 * ticket — tickets are consumed by the server on upgrade, so reuse would fail
 * closed anyway. Dedicated to `/api/audio/speak-stream`; the chat socket and
 * its connection lifecycle are untouched.
 */
class StreamingSpeechTransport(
    private val ticketClient: WsTicketClient,
    private val socketFactory: SpeechWebSocketFactory,
) : SpeechStreamConnector {
    override suspend fun connect(
        origin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): SpeechStreamSocket {
        val authQuery = when (val current = credential) {
            is HermesCredential.NativeBearer -> {
                val ticket = ticketClient.mintTicket(origin, current)
                "ticket=${URLEncoder.encode(ticket.ticket, StandardCharsets.UTF_8.name())}"
            }
            is HermesCredential.LoopbackSession ->
                "token=${current.encodedWebSocketToken(origin)}"
            HermesCredential.None -> null
        }
        return try {
            socketFactory.connect(speakStreamUrl(origin, authQuery, profile))
        } catch (_: CancellationException) {
            throw CancellationException("Hermes speech connection cancelled")
        } catch (_: Exception) {
            // The factory has seen a signed URL. Discard arbitrary causes that
            // may echo its ticket/session token.
            throw SpeechStreamConnectException()
        }
    }
}

/** Ktor-backed speech socket supporting the binary PCM frames the server sends. */
class KtorSpeechWebSocketFactory(
    private val client: HttpClient,
) : SpeechWebSocketFactory {
    override suspend fun connect(url: String): SpeechStreamSocket {
        return try {
            KtorSpeechSocket(client.webSocketSession { url(url) })
        } catch (_: CancellationException) {
            throw CancellationException("Hermes speech connection cancelled")
        } catch (_: Exception) {
            throw SpeechStreamConnectException()
        }
    }
}

/** Bounded connect failure — carries no URL/ticket detail. */
class SpeechStreamConnectException : Exception("Could not connect Hermes speech stream")

private class KtorSpeechSocket(
    private val session: WebSocketSession,
) : SpeechStreamSocket {
    override suspend fun sendText(text: String) {
        session.send(text)
    }

    override suspend fun receiveFrame(): SpeechSocketFrame? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            when (frame) {
                is Frame.Text -> return SpeechSocketFrame.Text(String(frame.data, StandardCharsets.UTF_8))
                is Frame.Binary -> return SpeechSocketFrame.Binary(frame.data)
                else -> Unit
            }
        }
    }

    override suspend fun close() {
        session.cancel()
    }
}
