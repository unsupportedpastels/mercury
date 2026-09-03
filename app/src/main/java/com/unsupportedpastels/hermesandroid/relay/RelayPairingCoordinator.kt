package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayFingerprint
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayPairingAck
import com.unsupportedpastels.mercury.core.relay.RelayApprovalProbe
import com.unsupportedpastels.mercury.core.relay.RelayApprovalProbeResult
import com.unsupportedpastels.mercury.core.relay.RelayPairingPayload
import com.unsupportedpastels.mercury.core.relay.RelayProtocolException
import com.unsupportedpastels.mercury.core.relay.RelayProtocolFailure
import com.unsupportedpastels.mercury.core.relay.RelaySecureChannel
import com.unsupportedpastels.mercury.core.relay.RelayTargetCodec
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RelayPairingFailure {
    MalformedQr,
    UnsupportedVersion,
    MissingRelayOrigin,
    ExpiredOffer,
    OfferRejected,
    Offline,
    ProtocolViolation,
    StorageFailed,
    TargetLimitReached,
}

class RelayPairingException(
    val failure: RelayPairingFailure,
) : Exception("Mercury Relay pairing failed")

class RelayPairingCoordinator(
    private val socketFactory: RelayBinarySocketFactory,
    private val targets: RelayTargetRepository,
    private val crypto: RelayCrypto = AndroidRelayCrypto,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val makeId: () -> String = { UUID.randomUUID().toString() },
    private val makeDeviceKey: () -> ByteArray = { AndroidRelayCrypto.randomBytes(32) },
    private val deterministicEphemeralPrivateKey: ByteArray? = null,
) {
    suspend fun pair(scannedText: String): RelayPairedTarget {
        val payload = try {
            RelayPairingPayload.parse(scannedText, nowEpochSeconds())
        } catch (error: RelayProtocolException) {
            throw RelayPairingException(error.failure.toPairingFailure())
        }
        val existing = try {
            targets.load()
        } catch (_: Exception) {
            payload.capability.fill(0)
            throw RelayPairingException(RelayPairingFailure.StorageFailed)
        }
        if (existing.size >= RelayTargetCodec.maxTargets) {
            payload.capability.fill(0)
            throw RelayPairingException(RelayPairingFailure.TargetLimitReached)
        }
        val deviceKey = makeDeviceKey()
        if (deviceKey.size != 32) {
            payload.capability.fill(0)
            throw RelayPairingException(RelayPairingFailure.ProtocolViolation)
        }
        val socket = try {
            socketFactory.connect(payload.deviceSocketUrl, payload.pairingRoutingToken)
        } catch (cancelled: CancellationException) {
            payload.capability.fill(0)
            throw cancelled
        } catch (_: Exception) {
            payload.capability.fill(0)
            throw RelayPairingException(RelayPairingFailure.Offline)
        }

        try {
            val channel = RelaySecureChannel(
                crypto = crypto,
                isInitiator = true,
                staticPrivateKey = deviceKey,
                installationId = payload.installationId,
                remoteStaticPublicKey = payload.hostPublicKey,
                deterministicEphemeralPrivateKey = deterministicEphemeralPrivateKey,
            )
            socket.send(channel.writeHandshake())
            val second = socket.receive() ?: throw RelayPairingException(RelayPairingFailure.Offline)
            channel.readHandshake(second)
            socket.send(channel.writeHandshake(payload.capability))
            payload.capability.fill(0)
            val ack = socket.receive() ?: throw RelayPairingException(RelayPairingFailure.OfferRejected)
            val pairingAck = RelayPairingAck.parseEnvelope(channel.decrypt(ack))
            val fingerprint = RelayFingerprint.shortAuthenticationString(crypto, channel.channelBinding)
            channel.close()
            closeSocket(socket)
            val target = RelayPairedTarget(
                id = makeId(),
                label = "",
                relayOrigin = payload.relayOrigin,
                installationId = payload.installationId.copyOf(),
                hostPublicKey = payload.hostPublicKey.copyOf(),
                deviceId = pairingAck.deviceId,
                deviceStaticPrivateKey = deviceKey.copyOf(),
                fingerprint = fingerprint,
                status = RelayTargetStatus.Pending,
                createdAtEpochSeconds = maxOf(0, nowEpochSeconds()),
                lastUsedEpochSeconds = null,
                relayRoutingToken = pairingAck.relayRoutingToken,
            )
            return try {
                targets.add(target)
            } catch (error: RelayTargetStoreException) {
                throw RelayPairingException(
                    if (error.failure == RelayTargetStoreFailure.TargetLimitReached) {
                        RelayPairingFailure.TargetLimitReached
                    } else {
                        RelayPairingFailure.StorageFailed
                    }
                )
            } catch (_: Exception) {
                throw RelayPairingException(RelayPairingFailure.StorageFailed)
            }
        } catch (cancelled: CancellationException) {
            payload.capability.fill(0)
            closeSocket(socket)
            throw cancelled
        } catch (error: RelayPairingException) {
            payload.capability.fill(0)
            closeSocket(socket)
            throw error
        } catch (_: RelayProtocolException) {
            payload.capability.fill(0)
            closeSocket(socket)
            throw RelayPairingException(RelayPairingFailure.ProtocolViolation)
        } catch (_: Exception) {
            payload.capability.fill(0)
            closeSocket(socket)
            throw RelayPairingException(RelayPairingFailure.ProtocolViolation)
        }
    }

    suspend fun probeApproval(target: RelayPairedTarget, profile: String): Boolean {
        val connected = try {
            RelayConnector.connect(target, profile, socketFactory, crypto)
        } catch (_: Exception) {
            return false
        }
        val chat = RelayHermesChatSocket(connected)
        return try {
            chat.sendText("{\"jsonrpc\":\"2.0\",\"id\":\"pairing-probe\",\"method\":\"gateway.ping\",\"params\":{}}")
            val approved = withTimeoutOrNull(10_000) {
                repeat(32) {
                    val response = chat.receiveText() ?: return@withTimeoutOrNull false
                    when (RelayApprovalProbe.evaluateGatewayPing(response)) {
                        RelayApprovalProbeResult.Approved -> return@withTimeoutOrNull true
                        RelayApprovalProbeResult.Rejected -> return@withTimeoutOrNull false
                        RelayApprovalProbeResult.Ignore -> Unit
                    }
                }
                false
            } == true
            if (approved) targets.markApproved(target.id, nowEpochSeconds())
            approved
        } catch (_: Exception) {
            false
        } finally {
            closeChat(chat)
        }
    }

    private suspend fun closeSocket(socket: RelayBinarySocket) = withContext(NonCancellable) {
        runCatching { socket.close() }
    }

    private suspend fun closeChat(chat: RelayHermesChatSocket) = withContext(NonCancellable) {
        runCatching { chat.close() }
    }

    private fun RelayProtocolFailure.toPairingFailure(): RelayPairingFailure = when (this) {
        RelayProtocolFailure.UnsupportedVersion -> RelayPairingFailure.UnsupportedVersion
        RelayProtocolFailure.MissingRelayOrigin -> RelayPairingFailure.MissingRelayOrigin
        RelayProtocolFailure.ExpiredOffer -> RelayPairingFailure.ExpiredOffer
        RelayProtocolFailure.MalformedPayload,
        RelayProtocolFailure.UnsupportedScheme,
        RelayProtocolFailure.InvalidEnvelope,
        -> RelayPairingFailure.MalformedQr
    }
}
