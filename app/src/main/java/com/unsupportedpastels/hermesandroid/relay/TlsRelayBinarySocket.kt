package com.unsupportedpastels.hermesandroid.relay

import android.util.Base64
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val RELAY_SOCKET_CONNECT_TIMEOUT_MILLIS = 10_000
private const val RELAY_CONNECT_DEADLINE_MILLIS = 20_000L
private const val MAX_RELAY_HANDSHAKE_LINE_BYTES = 4_096
private const val MAX_RELAY_HANDSHAKE_HEADERS = 64
private const val MAX_RELAY_WEBSOCKET_MESSAGE_BYTES = 65_535
private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/** Minimal RFC 6455 client for Relay binary records with an exact Authorization upgrade. */
class TlsRelayBinarySocketFactory(
    private val random: SecureRandom = SecureRandom(),
) : RelayBinarySocketFactory, RelayDiagnosticSocketFactory {
    override suspend fun connect(url: String, routingToken: String?): RelayBinarySocket =
        connectInternal(url, routingToken, null)

    override suspend fun connectWithDiagnostics(
        url: String,
        routingToken: String?,
        attempt: RelayDiagnosticAttempt,
    ): RelayBinarySocket = connectInternal(url, routingToken, attempt)

    private suspend fun connectInternal(
        url: String,
        routingToken: String?,
        diagnosticAttempt: RelayDiagnosticAttempt?,
    ): RelayBinarySocket {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: fail(RelayConnectionFailure.ProtocolViolation)
        if (uri.scheme != "wss" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) {
            fail(RelayConnectionFailure.ProtocolViolation)
        }
        if (routingToken != null && !validRoutingToken(routingToken)) {
            fail(RelayConnectionFailure.ProtocolViolation)
        }
        val port = if (uri.port == -1) 443 else uri.port
        if (port !in 1..65_535) fail(RelayConnectionFailure.ProtocolViolation)

        val resources = RelayCloseRegistry()
        try {
            val result = withTimeoutOrNull(RELAY_CONNECT_DEADLINE_MILLIS) {
                runRelayBlockingIo(
                    onCancellation = resources::close,
                    onResultCancellation = { socket -> socket.closeImmediately() },
                ) {
                    val raw = Socket()
                    resources.register { runCatching { raw.close() } }
                    diagnosticAttempt?.recordOpenStageStarted(RelayDiagnosticOpenStage.Dns)
                    val address = InetSocketAddress(uri.host, port)
                    // An unresolved address still fails in the original Socket.connect call.
                    if (!address.isUnresolved) {
                        diagnosticAttempt?.recordOpenStageSucceeded(RelayDiagnosticOpenStage.Dns)
                        diagnosticAttempt?.recordOpenStageStarted(RelayDiagnosticOpenStage.Tcp)
                    }
                    raw.connect(address, RELAY_SOCKET_CONNECT_TIMEOUT_MILLIS)
                    diagnosticAttempt?.recordOpenStageSucceeded(RelayDiagnosticOpenStage.Tcp)

                    diagnosticAttempt?.recordOpenStageStarted(RelayDiagnosticOpenStage.Tls)
                    val tls = HttpsURLConnection.getDefaultSSLSocketFactory()
                        .createSocket(raw, uri.host, port, true) as SSLSocket
                    resources.register { runCatching { tls.close() } }
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.soTimeout = (RELAY_CONNECT_DEADLINE_MILLIS / 2).toInt()
                    tls.startHandshake()
                    diagnosticAttempt?.recordOpenStageSucceeded(RelayDiagnosticOpenStage.Tls)

                    diagnosticAttempt?.recordOpenStageStarted(RelayDiagnosticOpenStage.Upgrade)
                    val websocketKey = ByteArray(16).also(random::nextBytes).base64()
                    val path = buildString {
                        append(uri.rawPath.takeUnless(String::isNullOrEmpty) ?: "/")
                        uri.rawQuery?.let { append('?').append(it) }
                    }
                    val host = if (port == 443) uri.host else "${uri.host}:$port"
                    val request = buildRelayUpgradeRequest(path, host, websocketKey, routingToken)
                    tls.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                    tls.outputStream.flush()
                    validateHandshake(tls.inputStream, websocketKey)
                    tls.soTimeout = 0
                    diagnosticAttempt?.recordOpenStageSucceeded(RelayDiagnosticOpenStage.Upgrade)

                    TlsRelayBinarySocket(tls, random).also { socket ->
                        resources.register { socket.closeImmediately() }
                    }
                }
            }
            if (result == null) {
                currentCoroutineContext().ensureActive()
                diagnosticAttempt?.recordTimeout(RelayDiagnosticPhase.Open)
                resources.close()
                fail(RelayConnectionFailure.Offline)
            }
            resources.detach()
            return result
        } catch (cancelled: CancellationException) {
            diagnosticAttempt?.recordCancellation()
            resources.close()
            throw cancelled
        } catch (error: RelayConnectionException) {
            diagnosticAttempt?.recordOpenFailure(error)
            resources.close()
            throw error
        } catch (error: Exception) {
            diagnosticAttempt?.recordOpenFailure(error)
            resources.close()
            fail(RelayConnectionFailure.Offline)
        }
    }

    private fun validateHandshake(input: InputStream, key: String) {
        val status = input.readAsciiLine() ?: fail(RelayConnectionFailure.Offline)
        val statusCode = status.split(' ').getOrNull(1)?.toIntOrNull()
        if (statusCode == 401 || statusCode == 403) fail(RelayConnectionFailure.NotAuthorized)
        if (statusCode != 101) fail(RelayConnectionFailure.Offline)
        val headers = LinkedHashMap<String, String>()
        repeat(MAX_RELAY_HANDSHAKE_HEADERS) {
            val line = input.readAsciiLine() ?: fail(RelayConnectionFailure.Offline)
            if (line.isEmpty()) {
                val expectedAccept = MessageDigest.getInstance("SHA-1")
                    .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
                    .base64()
                if (!headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true) ||
                    !headers["connection"].orEmpty().split(',').any { it.trim().equals("upgrade", true) } ||
                    headers["sec-websocket-accept"] != expectedAccept
                ) fail(RelayConnectionFailure.ProtocolViolation)
                return
            }
            val separator = line.indexOf(':')
            if (separator <= 0) fail(RelayConnectionFailure.ProtocolViolation)
            headers[line.substring(0, separator).lowercase(Locale.US)] = line.substring(separator + 1).trim()
        }
        fail(RelayConnectionFailure.ProtocolViolation)
    }

    private fun validRoutingToken(value: String): Boolean =
        value.isNotEmpty() && value.length <= 1_024 && value.all { it.code in 0x21..0x7e }
}

/** Internal serialization seam; never pass the returned credential-bearing request to diagnostics. */
internal fun buildRelayUpgradeRequest(path: String, host: String, key: String, routingToken: String?): String =
    buildString {
        append("GET $path HTTP/1.1\r\n")
        append("Host: $host\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: $key\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        routingToken?.let { append("Authorization: ").append("Bearer ").append(it).append("\r\n") }
        append("\r\n")
    }

private class TlsRelayBinarySocket(
    private val socket: SSLSocket,
    private val random: SecureRandom,
) : RelayBinarySocket {
    private data class WebSocketFrame(
        val finished: Boolean,
        val opcode: Int,
        val payload: ByteArray,
    )

    private val input = socket.inputStream
    private val output = socket.outputStream
    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun send(data: ByteArray) {
        if (data.size > MAX_RELAY_WEBSOCKET_MESSAGE_BYTES) fail(RelayConnectionFailure.ProtocolViolation)
        sendFrame(opcode = 0x2, payload = data)
    }

    override suspend fun receive(): ByteArray? = receiveMutex.withLock {
        var fragmented: ByteArray? = null
        while (!closed.get()) {
            val frame = readFrame() ?: return@withLock null
            val payload = frame.payload
            when (frame.opcode) {
                0x0 -> {
                    val prior = fragmented ?: failAndClose()
                    if (prior.size + payload.size > MAX_RELAY_WEBSOCKET_MESSAGE_BYTES) failAndClose()
                    fragmented = prior + payload
                    if (frame.finished) return@withLock fragmented
                }
                0x2 -> {
                    if (fragmented != null) failAndClose()
                    if (frame.finished) return@withLock payload
                    fragmented = payload
                }
                0x8 -> {
                    closeImmediately()
                    return@withLock null
                }
                0x9 -> sendFrame(opcode = 0xA, payload = payload)
                0xA -> Unit
                else -> failAndClose()
            }
        }
        null
    }

    private suspend fun sendFrame(opcode: Int, payload: ByteArray) = sendMutex.withLock {
        runRelayBlockingIo(onCancellation = ::closeImmediately) {
            if (closed.get()) fail(RelayConnectionFailure.Offline)
            val mask = ByteArray(4).also(random::nextBytes)
            output.write(0x80 or opcode)
            if (payload.size < 126) {
                output.write(0x80 or payload.size)
            } else {
                output.write(0x80 or 126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 0xff)
            }
            output.write(mask)
            val masked = ByteArray(payload.size) { index ->
                (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
            }
            output.write(masked)
            output.flush()
        }
    }

    private suspend fun readFrame(): WebSocketFrame? =
        runRelayBlockingIo(onCancellation = ::closeImmediately) {
            val first = input.read()
            if (first < 0) return@runRelayBlockingIo null
            val second = input.read()
            if (second < 0) throw EOFException()
            if (first and 0x70 != 0 || second and 0x80 != 0) failAndClose()
            val finished = first and 0x80 != 0
            val opcode = first and 0x0f
            var length = second and 0x7f
            if (length == 126) {
                length = (input.readRequired() shl 8) or input.readRequired()
            } else if (length == 127) {
                var value = 0L
                repeat(8) { value = (value shl 8) or input.readRequired().toLong() }
                if (value > MAX_RELAY_WEBSOCKET_MESSAGE_BYTES) failAndClose()
                length = value.toInt()
            }
            if (length > MAX_RELAY_WEBSOCKET_MESSAGE_BYTES || (opcode >= 0x8 && (!finished || length > 125))) {
                failAndClose()
            }
            WebSocketFrame(finished, opcode, input.readExactly(length))
        }

    override suspend fun close() = closeImmediately()

    internal fun closeImmediately() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }

    private fun failAndClose(): Nothing {
        closeImmediately()
        fail(RelayConnectionFailure.ProtocolViolation)
    }
}

private fun InputStream.readAsciiLine(): String? {
    val bytes = ArrayList<Byte>()
    while (bytes.size <= MAX_RELAY_HANDSHAKE_LINE_BYTES) {
        val value = read()
        if (value < 0) return if (bytes.isEmpty()) null else fail(RelayConnectionFailure.ProtocolViolation)
        if (value == '\n'.code) {
            if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
            return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        bytes += value.toByte()
    }
    fail(RelayConnectionFailure.ProtocolViolation)
}

private fun InputStream.readRequired(): Int = read().also { if (it < 0) throw EOFException() }

private fun InputStream.readExactly(size: Int): ByteArray = ByteArray(size).also { target ->
    var offset = 0
    while (offset < target.size) {
        val read = read(target, offset, target.size - offset)
        if (read < 0) throw EOFException()
        offset += read
    }
}

private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun fail(failure: RelayConnectionFailure): Nothing = throw RelayConnectionException(failure)
