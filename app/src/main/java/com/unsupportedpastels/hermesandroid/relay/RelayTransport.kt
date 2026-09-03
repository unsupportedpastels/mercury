package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSocket
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayAdmissionEnvelope
import com.unsupportedpastels.mercury.core.relay.RelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayFraming
import com.unsupportedpastels.mercury.core.relay.RelayFrameReassembler
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayPairingPayload
import com.unsupportedpastels.mercury.core.relay.RelaySecureChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RelayBinarySocket {
    suspend fun send(data: ByteArray)
    suspend fun receive(): ByteArray?
    suspend fun close()
}

fun interface RelayBinarySocketFactory {
    suspend fun connect(url: String, routingToken: String?): RelayBinarySocket
}

enum class RelayConnectionFailure {
    Offline,
    NotAuthorized,
    ProtocolViolation,
}

class RelayConnectionException(
    val failure: RelayConnectionFailure,
) : Exception("Mercury Relay connection failed")

class RelayConnectedChannel internal constructor(
    internal val socket: RelayBinarySocket,
    internal val channel: RelaySecureChannel,
    val channelBinding: ByteArray,
)

object RelayConnector {
    suspend fun connect(
        target: RelayPairedTarget,
        profile: String,
        socketFactory: RelayBinarySocketFactory,
        crypto: RelayCrypto = AndroidRelayCrypto,
        resumeCursor: Long? = null,
        deterministicEphemeralPrivateKey: ByteArray? = null,
    ): RelayConnectedChannel {
        val url = RelayPairingPayload.deviceSocketUrl(target.relayOrigin, target.installationId)
            ?: throw RelayConnectionException(RelayConnectionFailure.ProtocolViolation)
        val socket = try {
            socketFactory.connect(url, target.relayRoutingToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RelayConnectionException) {
            throw error
        } catch (_: Exception) {
            throw RelayConnectionException(RelayConnectionFailure.Offline)
        }
        try {
            val channel = RelaySecureChannel(
                crypto = crypto,
                isInitiator = true,
                staticPrivateKey = target.deviceStaticPrivateKey,
                installationId = target.installationId,
                remoteStaticPublicKey = target.hostPublicKey,
                deterministicEphemeralPrivateKey = deterministicEphemeralPrivateKey,
            )
            socket.send(channel.writeHandshake())
            val second = socket.receive()
                ?: throw RelayConnectionException(RelayConnectionFailure.NotAuthorized)
            channel.readHandshake(second)
            socket.send(channel.writeHandshake())
            val envelope = RelayAdmissionEnvelope.controllerOpen(target.deviceId, profile, resumeCursor)
            socket.send(channel.encrypt(envelope))
            return RelayConnectedChannel(socket, channel, channel.channelBinding)
        } catch (cancelled: CancellationException) {
            socket.close()
            throw cancelled
        } catch (error: RelayConnectionException) {
            socket.close()
            throw error
        } catch (_: Exception) {
            socket.close()
            throw RelayConnectionException(RelayConnectionFailure.ProtocolViolation)
        }
    }
}

class RelayHermesChatSocket(
    connected: RelayConnectedChannel,
    private val randomMessageId: () -> ByteArray = {
        AndroidRelayCrypto.randomBytes(RelayFraming.messageIdSize)
    },
) : HermesChatSocket {
    private val socket = connected.socket
    private val channel = connected.channel
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
                throw cancelled
            } catch (_: Exception) {
                throw HermesChatTransportException("Could not send a Mercury Relay chat frame")
            }
        }
    }

    override suspend fun receiveText(): String? = receiveMutex.withLock {
        while (!closed.get()) {
            val ciphertext = try {
                socket.receive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw HermesChatTransportException("Mercury Relay connection failed")
            } ?: return@withLock null
            try {
                val record = cryptoMutex.withLock { channel.decrypt(ciphertext) }
                val message = reassembler.push(record) ?: continue
                return@withLock message.decodeToString(throwOnInvalidSequence = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                close()
                throw HermesChatProtocolException("Mercury Relay channel failed")
            }
        }
        null
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        cryptoMutex.withLock { channel.close() }
        socket.close()
    }
}
