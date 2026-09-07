package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.readBodyTextBounded
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets

private const val MAX_TICKET_RESPONSE_BYTES = 16 * 1024

class KtorWsTicketClient(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WsTicketClient {
    override suspend fun mintTicket(origin: ServerOrigin, accessToken: String): WsTicket {
        return try {
            val response = client.post("${origin.value}/api/auth/ws-ticket") {
                accessToken.takeIf(String::isNotBlank)?.let(::bearerAuth)
            }
            val body = response.readBodyTextBounded(MAX_TICKET_RESPONSE_BYTES)
            if (!response.status.isSuccess()) {
                // 401/403 means the presented access token is stale/rejected — an
                // auth condition a refresh or sign-in must heal. Surfacing it as a
                // transport error spins the reconnect/recovery loop against a token
                // the server will never accept (the outage-reconnect wedge).
                if (response.status.value == 401 || response.status.value == 403) {
                    throw HermesChatUnauthorizedException(
                        "Hermes ticket request was unauthorized (HTTP ${response.status.value})",
                    )
                }
                throw HermesChatTransportException(
                    "Hermes ticket request returned HTTP ${response.status.value}",
                )
            }
            val value = json.parseToJsonElement(body).jsonObject
            val ticket = value.stringValue("ticket")
                ?.takeIf(String::isNotBlank)
                ?: throw HermesChatProtocolException("Hermes ticket response was incomplete")
            val ttlSeconds = value.longValue("ttl_seconds")
                ?: throw HermesChatProtocolException("Hermes ticket response was incomplete")
            WsTicket(ticket, ttlSeconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not mint Hermes chat ticket", error)
        }
    }
}

class KtorChatWebSocketFactory(
    private val client: HttpClient,
) : ChatWebSocketFactory {
    override suspend fun connect(url: String): HermesChatSocket {
        return try {
            KtorHermesChatSocket(client.webSocketSession { url(url) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not connect to Hermes chat", error)
        }
    }
}

private class KtorHermesChatSocket(
    private val session: WebSocketSession,
) : HermesChatSocket {
    override suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun receiveText(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return String(frame.data, StandardCharsets.UTF_8)
        }
    }

    override suspend fun close() {
        session.cancel()
    }
}
