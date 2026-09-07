package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSocket
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.mercury.core.relay.RelayPlatformCrypto
import com.unsupportedpastels.mercury.core.relay.RelayAdmissionEnvelope
import com.unsupportedpastels.mercury.core.relay.RelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayFraming
import com.unsupportedpastels.mercury.core.relay.RelayFrameReassembler
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayPairingPayload
import com.unsupportedpastels.mercury.core.relay.RelaySecureChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val RELAY_NOISE_NEGOTIATION_TIMEOUT_MILLIS = 10_000L

interface RelayBinarySocket {
    suspend fun send(data: ByteArray)
    suspend fun receive(): ByteArray?
    suspend fun close()
    /** Router/host WebSocket close code seen on this socket, when known. */
    fun lastCloseCode(): Int? = null
}

/** Router close code: no Hermes host is attached to this installation. */
const val RELAY_CLOSE_NO_HOST = 4004

fun interface RelayBinarySocketFactory {
    suspend fun connect(url: String, routingToken: String?): RelayBinarySocket
}

enum class RelayConnectionFailure {
    Offline,
    /** The host closed the channel: this device is pending, denied, or revoked. */
    NotAuthorized,
    ProtocolViolation,
    /** The router refused the socket: the pairing's routing token is missing, expired, or invalid. */
    RoutingRejected,
    /** The router accepted the socket but no Hermes host is attached right now. */
    NoHost,
}

class RelayConnectionException(
    val failure: RelayConnectionFailure,
) : Exception("Mercury Relay connection failed")

class RelayConnectedChannel internal constructor(
    internal val socket: RelayBinarySocket,
    internal val channel: RelaySecureChannel,
    val channelBinding: ByteArray,
    internal val diagnosticAttempt: RelayDiagnosticAttempt? = null,
)

object RelayConnector {
    suspend fun connect(
        target: RelayPairedTarget,
        profile: String,
        socketFactory: RelayBinarySocketFactory,
        crypto: RelayCrypto = RelayPlatformCrypto,
        resumeCursor: Long? = null,
        recoveryVersion: Int? = null,
        deterministicEphemeralPrivateKey: ByteArray? = null,
        diagnostics: RelayDiagnostics = RelayDiagnostics.shared,
        /** Lease channel; null is the legacy default channel (one lease per device). */
        leaseChannel: String? = null,
    ): RelayConnectedChannel {
        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Connection)
        val url = RelayPairingPayload.deviceSocketUrl(target.relayOrigin, target.installationId)
            ?: run {
                attempt.recordFailure(RelayDiagnosticPhase.Attempt, RelayDiagnosticReason.ProtocolViolation)
                throw RelayConnectionException(RelayConnectionFailure.ProtocolViolation)
            }
        val socket = try {
            socketFactory.connectRelaySocket(url, target.relayRoutingToken, attempt)
        } catch (cancelled: CancellationException) {
            attempt.recordCancellation()
            throw cancelled
        } catch (error: RelayConnectionException) {
            attempt.recordFailure(RelayDiagnosticPhase.Open, error.failure.toDiagnosticReason())
            throw error
        } catch (_: Exception) {
            attempt.recordFailure(RelayDiagnosticPhase.Open, RelayDiagnosticReason.Offline)
            throw RelayConnectionException(RelayConnectionFailure.Offline)
        }
        val socketOpened = true
        attempt.recordSuccess(RelayDiagnosticPhase.Open)

        var channel: RelaySecureChannel? = null
        var phase = RelayDiagnosticPhase.Handshake
        var closeReason = RelayDiagnosticCloseReason.LocalFailure
        try {
            val createdChannel = RelaySecureChannel(
                crypto = crypto,
                isInitiator = true,
                staticPrivateKey = target.deviceStaticPrivateKey,
                installationId = target.installationId,
                remoteStaticPublicKey = target.hostPublicKey,
                deterministicEphemeralPrivateKey = deterministicEphemeralPrivateKey,
            )
            channel = createdChannel
            val admitted = withTimeoutOrNull(RELAY_NOISE_NEGOTIATION_TIMEOUT_MILLIS) {
                socket.send(createdChannel.writeHandshake())
                val second = socket.receive()
                    ?: throw RelayConnectionException(
                        if (socket.lastCloseCode() == RELAY_CLOSE_NO_HOST) RelayConnectionFailure.NoHost
                        else RelayConnectionFailure.NotAuthorized,
                    )
                createdChannel.readHandshake(second)
                attempt.recordSuccess(RelayDiagnosticPhase.Handshake)
                phase = RelayDiagnosticPhase.Admission
                socket.send(createdChannel.writeHandshake())
                val envelope = RelayAdmissionEnvelope.controllerOpen(
                    target.deviceId, profile, resumeCursor, recoveryVersion, leaseChannel,
                    RelayDeviceIdentity.name,
                )
                socket.send(createdChannel.encrypt(envelope))
                attempt.recordSuccess(RelayDiagnosticPhase.Admission)
                true
            }
            if (admitted != true) {
                currentCoroutineContext().ensureActive()
                attempt.recordTimeout(phase)
                closeReason = RelayDiagnosticCloseReason.Timeout
                throw RelayConnectionException(RelayConnectionFailure.Offline)
            }
            return RelayConnectedChannel(socket, createdChannel, createdChannel.channelBinding, attempt)
        } catch (cancelled: CancellationException) {
            attempt.recordCancellation()
            closeRelayResources(socket, channel)
            if (socketOpened) attempt.recordDisconnect(RelayDiagnosticCloseReason.Cancelled)
            throw cancelled
        } catch (error: RelayConnectionException) {
            attempt.recordFailure(phase, error.failure.toDiagnosticReason())
            closeRelayResources(socket, channel)
            if (socketOpened) attempt.recordDisconnect(closeReason)
            throw error
        } catch (_: Exception) {
            attempt.recordFailure(phase, RelayDiagnosticReason.ProtocolViolation)
            closeRelayResources(socket, channel)
            if (socketOpened) attempt.recordDisconnect(RelayDiagnosticCloseReason.ProtocolViolation)
            throw RelayConnectionException(RelayConnectionFailure.ProtocolViolation)
        }
    }
}

internal suspend fun closeRelayResources(
    socket: RelayBinarySocket?,
    channel: RelaySecureChannel?,
) = withContext(NonCancellable) {
    runCatching { channel?.close() }
    runCatching { socket?.close() }
}

class RelayHermesChatSocket(
    connected: RelayConnectedChannel,
    private val randomMessageId: () -> ByteArray = {
        RelayPlatformCrypto.randomBytes(RelayFraming.messageIdSize)
    },
) : HermesChatSocket {
    private val socket = connected.socket
    private val channel = connected.channel
    private val diagnosticAttempt = connected.diagnosticAttempt
    private val channelId = connected.channelBinding.copyOfRange(0, RelayFraming.channelIdSize)
    private val reassembler = RelayFrameReassembler(channelId)
    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()
    private val cryptoMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun sendText(text: String) {
        if (closed.get()) throw HermesChatTransportException("Mercury Relay channel is closed")
        sendMutex.withLock {
            try {
                val messageId = randomMessageId()
                val records = RelayFraming.encodeMessage(channelId, messageId, text.encodeToByteArray())
                for (record in records) {
                    val ciphertext = cryptoMutex.withLock { channel.encrypt(record) }
                    socket.send(ciphertext)
                }
            } catch (cancelled: CancellationException) {
                closeWithReason(RelayDiagnosticCloseReason.Cancelled)
                throw cancelled
            } catch (_: Exception) {
                closeWithReason(
                    reason = RelayDiagnosticCloseReason.LocalFailure,
                    failureReason = RelayDiagnosticReason.TransportFailure,
                )
                throw HermesChatTransportException("Could not send a Mercury Relay chat frame")
            }
        }
    }

    override suspend fun receiveText(): String? = receiveMutex.withLock {
        while (!closed.get()) {
            val ciphertext = try {
                socket.receive()
            } catch (cancelled: CancellationException) {
                closeWithReason(RelayDiagnosticCloseReason.Cancelled)
                throw cancelled
            } catch (_: Exception) {
                closeWithReason(
                    reason = RelayDiagnosticCloseReason.LocalFailure,
                    failureReason = RelayDiagnosticReason.TransportFailure,
                )
                throw HermesChatTransportException("Mercury Relay connection failed")
            } ?: run {
                closeWithReason(RelayDiagnosticCloseReason.RemoteEndOfStream)
                return@withLock null
            }
            try {
                val record = cryptoMutex.withLock { channel.decrypt(ciphertext) }
                val message = reassembler.push(record) ?: continue
                return@withLock message.decodeToString(throwOnInvalidSequence = true)
            } catch (cancelled: CancellationException) {
                closeWithReason(RelayDiagnosticCloseReason.Cancelled)
                throw cancelled
            } catch (_: Exception) {
                closeWithReason(
                    reason = RelayDiagnosticCloseReason.ProtocolViolation,
                    failureReason = RelayDiagnosticReason.ProtocolViolation,
                )
                throw HermesChatProtocolException("Mercury Relay channel failed")
            }
        }
        null
    }

    override suspend fun close() {
        closeWithReason(RelayDiagnosticCloseReason.Explicit)
    }

    private suspend fun closeWithReason(
        reason: RelayDiagnosticCloseReason,
        failureReason: RelayDiagnosticReason? = null,
    ) {
        failureReason?.let { diagnosticAttempt?.recordFailure(RelayDiagnosticPhase.Disconnect, it) }
        if (reason == RelayDiagnosticCloseReason.Cancelled) diagnosticAttempt?.recordCancellation()
        withContext(NonCancellable) {
            if (!closed.compareAndSet(false, true)) return@withContext
            runCatching { cryptoMutex.withLock { channel.close() } }
            runCatching { socket.close() }
            diagnosticAttempt?.recordDisconnect(reason)
        }
    }
}
